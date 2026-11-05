from __future__ import annotations

import copy
import json
from xml.etree import ElementTree

import pytest

from app.datasets import validate_dataset
from app.osm import overpass_query, overpass_to_collection

BASE = "https://maps.example.org"


def test_dataset_listing_and_version(client):
    versions = client.get("/api/v1/datasets").json()
    assert [v["layer_id"] for v in versions] == ["public-services"]
    v = client.get("/api/v1/datasets/public-services/version").json()
    assert v["version"] == 1 and v["feature_count"] == 48 and len(v["checksum"]) == 64
    assert v["downloads"]["gpx"] == f"{BASE}/api/v1/datasets/public-services/download?format=gpx"
    assert client.get("/api/v1/datasets/traffic/version").status_code == 404


def test_download_links_resolve(client):
    v = client.get("/api/v1/datasets/public-services/version").json()
    for fmt, url in v["downloads"].items():
        r = client.get(url.removeprefix(BASE))
        assert r.status_code == 200, fmt
        assert f"syria-maps-public-services-v1.{fmt}" in r.headers["content-disposition"]


def test_geojson_download(client):
    r = client.get("/api/v1/datasets/public-services/download")
    assert r.headers["content-type"].startswith("application/geo+json")
    doc = r.json()
    assert doc["metadata"]["version"] == 1 and doc["metadata"]["synthetic"] is True
    assert len(doc["features"]) == 48


def test_gpx_download(client):
    r = client.get("/api/v1/datasets/public-services/download", params={"format": "gpx"})
    root = ElementTree.fromstring(r.content)
    ns = "{http://www.topografix.com/GPX/1/1}"
    assert root.tag == f"{ns}gpx" and root.get("version") == "1.1"
    wpts = root.findall(f"{ns}wpt")
    assert len(wpts) == 48
    assert "SYNTHETIC" in root.find(f"{ns}metadata/{ns}desc").text


def test_etag_not_modified(client):
    r = client.get("/api/v1/datasets/public-services/download")
    r2 = client.get("/api/v1/datasets/public-services/download", headers={"If-None-Match": r.headers["etag"]})
    assert r2.status_code == 304


def test_download_errors(client):
    assert client.get("/api/v1/datasets/public-services/download", params={"format": "kml"}).status_code == 422
    assert client.get("/api/v1/datasets/safety-notices/download").status_code == 404
    assert client.get("/api/v1/datasets/nope/download").status_code == 404


# -- dataset validation -----------------------------------------------------

def test_fixture_is_valid(fixture_data, registry):
    report = validate_dataset(fixture_data, registry)
    assert report.ok, report.errors
    assert not report.warnings


@pytest.mark.parametrize("mutate, message", [
    (lambda d: d["metadata"].pop("license"), "metadata.license"),
    (lambda d: d["metadata"].update(layer_id="nope"), "unknown layer_id"),
    (lambda d: d["metadata"].update(synthetic=False), "synthetic must match"),
    (lambda d: d["features"][0]["properties"].update(kind="casino"), "kind 'casino'"),
    (lambda d: d["features"][1]["properties"].update(id=d["features"][0]["properties"]["id"]), "duplicate id"),
    (lambda d: d["features"][0]["properties"].update(name_en="Hospital A1"), "synthetic features must say"),
    (lambda d: d["features"][0].update(geometry={"type": "Point", "coordinates": []}), "invalid geometry"),
    (lambda d: d["features"][0].update(geometry={"type": "LineString", "coordinates": [[36, 33], [36.1, 33.1]]}),
     "not allowed"),
])
def test_invalid_datasets(fixture_data, registry, mutate, message):
    data = copy.deepcopy(fixture_data)
    mutate(data)
    report = validate_dataset(data, registry)
    assert not report.ok
    assert any(message in e for e in report.errors), report.errors


def test_outside_syria_is_warning(fixture_data, registry):
    data = copy.deepcopy(fixture_data)
    data["features"][0]["geometry"]["coordinates"] = [13.4, 52.5]
    report = validate_dataset(data, registry)
    assert report.ok and any("outside" in w for w in report.warnings)


def _notice_dataset(**props) -> dict:
    p = {"id": "n1", "notice_type": "road_hazard", "title_ar": "خطر", "source_name": "Authority",
         "verification_status": "verified", "reported_at": "2026-11-01T00:00:00Z",
         "valid_from": "2026-11-01T00:00:00Z", "verified_at": "2026-11-01T01:00:00Z",
         "expires_at": "2026-11-02T00:00:00Z"}
    p.update(props)
    return {"type": "FeatureCollection",
            "metadata": {"layer_id": "safety-notices", "kind": "notices", "source": "s", "license": "l",
                         "attribution": "a"},
            "features": [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [36.3, 33.5]},
                          "properties": p}]}


def test_notice_validation(registry):
    assert validate_dataset(_notice_dataset(), registry).ok
    for bad in ({"expires_at": None}, {"verified_at": None}, {"source_name": ""},
                {"notice_type": "predicted_minefield"}, {"verification_status": "probably"},
                {"expires_at": "2026-10-01T00:00:00Z"}, {"valid_from": "2026-11-01T00:00:00"}):
        assert not validate_dataset(_notice_dataset(**bad), registry).ok, bad


def test_safety_layer_rejects_plain_features(fixture_data, registry):
    data = copy.deepcopy(fixture_data)
    data["metadata"]["layer_id"] = "safety-notices"
    data["metadata"]["synthetic"] = False
    assert any("verified-only" in e for e in validate_dataset(data, registry).errors)


# -- OSM conversion (phase 2) ----------------------------------------------

def test_overpass_conversion_prefers_arabic(registry):
    payload = {"elements": [
        {"type": "node", "id": 1, "lat": 33.5, "lon": 36.3,
         "tags": {"amenity": "pharmacy", "name": "Pharmacy X", "name:ar": "صيدلية س", "name:en": "Pharmacy X"}},
        {"type": "way", "id": 2, "center": {"lat": 33.51, "lon": 36.31},
         "tags": {"amenity": "school", "name": "مدرسة"}},
        {"type": "node", "id": 3, "lat": 33.5, "lon": 36.3, "tags": {"shop": "bakery"}},
    ]}
    col = overpass_to_collection(payload, "2026-11-05T00:00:00Z")
    assert [f["properties"]["name_ar"] for f in col["features"]] == ["صيدلية س", "مدرسة"]
    assert col["features"][1]["properties"]["osm_url"] == "https://www.openstreetmap.org/way/2"
    assert col["metadata"]["license"] == "ODbL-1.0"
    assert validate_dataset(json.loads(json.dumps(col)), registry).ok
    assert 'nwr["amenity"="hospital"](32.3,35.5,37.4,42.4);' in overpass_query((35.5, 32.3, 42.4, 37.4))
