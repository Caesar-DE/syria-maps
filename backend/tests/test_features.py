from __future__ import annotations

import pytest

from app.geo import haversine_m

URL = "/api/v1/layers/public-services/features"
# Cluster "A" of the synthetic fixture lies around lon 36.20 / lat 33.45.
BBOX_A = "36.1,33.4,36.3,33.5"


def test_all_features_have_attribution(client):
    body = client.get(URL, params={"limit": 1000}).json()
    assert body["numberMatched"] == 48
    for f in body["features"]:
        p = f["properties"]
        assert p["license"] == "CC0-1.0"
        assert p["attribution"]
        assert p["synthetic"] is True
        assert "Synthetic" in p["name_en"]
        assert p["updated_at"]


def test_bbox_query(client):
    body = client.get(URL, params={"bbox": BBOX_A, "limit": 1000}).json()
    assert body["numberMatched"] == 12
    for f in body["features"]:
        lon, lat = f["geometry"]["coordinates"]
        assert 36.1 <= lon <= 36.3 and 33.4 <= lat <= 33.5


def test_pagination(client):
    first = client.get(URL, params={"limit": 20}).json()
    assert first["numberReturned"] == 20
    assert [l["rel"] for l in first["links"]] == ["next"]
    nxt = client.get(first["links"][0]["href"]).json()
    assert nxt["offset"] == 20
    ids_first = {f["id"] for f in first["features"]}
    ids_next = {f["id"] for f in nxt["features"]}
    assert not ids_first & ids_next
    last = client.get(URL, params={"limit": 20, "offset": 40}).json()
    assert last["numberReturned"] == 8
    assert [l["rel"] for l in last["links"]] == ["prev"]


def test_kind_filter(client):
    body = client.get(URL, params={"kind": "hospital,pharmacy", "limit": 1000}).json()
    assert body["numberMatched"] == 16
    assert {f["properties"]["kind"] for f in body["features"]} == {"hospital", "pharmacy"}


def test_text_search_arabic(client):
    body = client.get(URL, params={"q": "صيدلية", "limit": 1000}).json()
    assert body["numberMatched"] == 8


def test_nearest_with_radius(client):
    lat, lon = 33.45, 36.20
    body = client.get(URL, params={"near": f"{lat},{lon}", "radius_m": 3000, "limit": 1000}).json()
    distances = [f["properties"]["distance_m"] for f in body["features"]]
    assert distances == sorted(distances)
    assert distances and max(distances) <= 3000
    for f in body["features"]:
        flon, flat = f["geometry"]["coordinates"]
        assert abs(haversine_m(lon, lat, flon, flat) - f["properties"]["distance_m"]) < 1


def test_public_services_alias(client):
    body = client.get("/api/v1/public-services", params={"kind": "school", "bbox": BBOX_A}).json()
    assert body["layer"]["id"] == "public-services"
    assert body["numberMatched"] == 2


def test_single_feature(client):
    fid = client.get(URL, params={"limit": 1}).json()["features"][0]["id"]
    r = client.get(f"{URL}/{fid}")
    assert r.status_code == 200 and r.json()["id"] == fid
    assert client.get(f"{URL}/999999").status_code == 404


@pytest.mark.parametrize("params", [
    {"bbox": "1,2,3"},
    {"bbox": "36.3,33.4,36.1,33.5"},  # min > max
    {"bbox": "a,b,c,d"},
    {"bbox": "200,0,201,1"},
    {"limit": 0},
    {"limit": 5000},
    {"offset": -1},
    {"kind": "casino"},
    {"near": "33.4"},
    {"near": "95,36"},
    {"radius_m": 100},  # without near
    {"q": "a"},  # too short
])
def test_input_validation(client, params):
    assert client.get(URL, params=params).status_code == 422


def test_planned_and_notice_layers_not_queryable(client):
    assert client.get("/api/v1/layers/buildings-3d/features").status_code == 404
    assert client.get("/api/v1/layers/safety-notices/features").status_code == 404
