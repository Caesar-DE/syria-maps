from __future__ import annotations

import io
import json
import zipfile

import pytest

from app.osmand.package import PLUGIN_ID, PackageOptions, build_osf
from app.osmand.spec import FILE_SUBTYPES, ITEM_TYPES, MAP_SOURCE_KEYS
from app.osmand.validate import validate_osf

BASE = "https://maps.example.org"


@pytest.fixture
def osf(registry, storage) -> bytes:
    return build_osf(registry, storage, PackageOptions(base_url=BASE))


def items_of(data: bytes) -> dict:
    return json.loads(zipfile.ZipFile(io.BytesIO(data)).read("items.json"))


def rezip(data: bytes, items: dict | None = None, drop: str | None = None) -> bytes:
    src = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as zf:
        for info in src.infolist():
            if info.filename == drop:
                continue
            body = json.dumps(items).encode() if items is not None and info.filename == "items.json" else src.read(info)
            zf.writestr(info.filename, body)
    return out.getvalue()


def test_package_is_structurally_valid(osf):
    report = validate_osf(osf)
    assert report.ok, report.errors
    assert report.warnings == []


def test_package_contents(osf):
    zf = zipfile.ZipFile(io.BytesIO(osf))
    names = set(zf.namelist())
    assert names == {"items.json", "res/syria_maps_icon.png", "res/syria_maps_image.png",
                     "tracks/syria-maps-public-services.gpx"}
    root = items_of(osf)
    assert root["version"] == 1
    types = [i["type"] for i in root["items"]]
    assert types == ["PLUGIN", "RESOURCES", "MAP_SOURCES", "FILE"]
    assert set(types) <= ITEM_TYPES
    plugin = root["items"][0]
    assert plugin["pluginId"] == PLUGIN_ID
    assert plugin["name"]["ar"] == "خرائط سوريا" and plugin["name"][""]
    sources = root["items"][2]["items"]
    assert [s["name"] for s in sources] == ["Syria Maps - Public services from OpenStreetMap",
                                            "Syria Maps - Public services (synthetic)"]
    source = sources[1]
    assert set(source) == set(MAP_SOURCE_KEYS)
    # High-density tiles: the URL and the declared size must match (OsmAnd crops to tileSize).
    assert source["url"] == f"{BASE}/tiles/public-services/{{0}}/{{1}}/{{2}}@2x.png"
    assert source["tileSize"] == 512
    assert source["timesupported"] is True and source["expire"] == 60
    assert root["items"][3]["subtype"] in FILE_SUBTYPES


def test_package_is_reproducible(registry, storage):
    a = build_osf(registry, storage, PackageOptions(base_url=BASE))
    b = build_osf(registry, storage, PackageOptions(base_url=BASE))
    assert a == b


def test_planned_layers_not_packaged(osf):
    text = zipfile.ZipFile(io.BytesIO(osf)).read("items.json").decode()
    assert "buildings-3d" not in text and "safety" not in text


def test_osf_endpoint_matches_builder(client, osf):
    r = client.get("/osmand/syria-maps.osf")
    assert r.status_code == 200
    assert 'filename="syria-maps.osf"' in r.headers["content-disposition"]
    assert r.content == osf


def test_add_tile_source_link(client):
    body = client.get("/osmand/add-tile-source/public-services").json()
    assert body["url"].startswith("https://osmand.net/add-tile-source?name=Syria+Maps")
    assert "url_template=https%3A%2F%2Fmaps.example.org%2Ftiles%2Fpublic-services%2F%7B0%7D" in body["url"]
    assert "%402x" not in body["url"]  # the link has no tile-size parameter, so it must use 256 px tiles
    assert body["verified_on_device"] is False


@pytest.mark.parametrize("bad", ["http://0.0.0.0:8000", "http://[::]:8000", "0.0.0.0:8000"])
def test_unreachable_base_url_is_refused(storage, registry, bad):
    from fastapi.testclient import TestClient

    from app.config import Settings, base_url_problem
    from app.main import create_app

    assert base_url_problem(bad)
    settings = Settings(storage="memory", public_base_url=bad)
    with TestClient(create_app(settings=settings, storage=storage, registry=registry)) as c:
        r = c.get("/osmand/syria-maps.osf")
        assert r.status_code == 503
        assert "SYRIA_MAPS_PUBLIC_BASE_URL" in r.json()["detail"]
        assert c.get("/osmand/add-tile-source/public-services").status_code == 503


@pytest.mark.parametrize("good", ["https://maps.example.org", "http://localhost:8000", "http://192.168.1.20:8000"])
def test_reachable_base_urls_are_accepted(good):
    from app.config import base_url_problem

    assert base_url_problem(good) is None


# -- validator catches broken packages ----------------------------------------

def _mutated(osf: bytes, fn) -> bytes:
    items = items_of(osf)
    fn(items)
    return rezip(osf, items)


@pytest.mark.parametrize("fn, message", [
    (lambda r: r.update(version=4), "root version"),
    (lambda r: r.update(items=[]), "non-empty"),
    (lambda r: r["items"].append({"type": "NAVIGATION_ICONS"}), "unknown type"),
    (lambda r: r["items"][0].update(pluginId="Syria Maps"), "pluginId"),
    (lambda r: r["items"][0]["name"].pop(""), "default ''"),
    (lambda r: r["items"][0].update(icon={"": "@missing.png"}), "missing.png"),
    (lambda r: r["items"][2]["items"][0].update(url="https://x/{0}/{1}.png"), "{2}"),
    (lambda r: r["items"][2]["items"][0].update(url="http://x.org/{0}/{1}/{2}.png"), "https"),
    (lambda r: r["items"][2]["items"][0].update(url="http://0.0.0.0:8000/t/{0}/{1}/{2}.png"), "listen address"),
    (lambda r: r["items"][2]["items"][0].update(minZoom="10"), "'minZoom' must be int"),
    (lambda r: r["items"][2]["items"][0].update(timesupported=1), "'timesupported' must be bool"),
    (lambda r: r["items"][2]["items"][0].update(name="Syria/Maps"), "becomes a folder"),
    (lambda r: r["items"][2]["items"][0].update(tileSize=300), "tileSize"),
    (lambda r: r["items"][2]["items"].append(dict(r["items"][2]["items"][0])), "duplicate name"),
    (lambda r: r["items"][3].update(subtype="render_style"), "FileSubtype"),
    (lambda r: r["items"][3].update(file="/tracks/nope.gpx"), "missing"),
    (lambda r: r["items"][1].update(pluginId="other.plugin"), "differs"),
])
def test_validator_rejects(osf, fn, message):
    report = validate_osf(_mutated(osf, fn))
    assert not report.ok
    assert any(message in e for e in report.errors), report.errors


def test_validator_rejects_non_zip_and_missing_items():
    assert not validate_osf(b"not a zip").ok
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("other.json", "{}")
    assert any("items.json" in e for e in validate_osf(buf.getvalue()).errors)


def test_validator_rejects_bad_gpx(osf):
    src = zipfile.ZipFile(io.BytesIO(osf))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as zf:
        for name in src.namelist():
            zf.writestr(name, b"<gpx" if name.endswith(".gpx") else src.read(name))
    assert any("well-formed" in e for e in validate_osf(out.getvalue()).errors)


def test_validator_rejects_zip_slip(osf):
    src = zipfile.ZipFile(io.BytesIO(osf))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as zf:
        for name in src.namelist():
            zf.writestr(name, src.read(name))
        zf.writestr("../evil.txt", "x")
    assert any("unsafe" in e for e in validate_osf(out.getvalue()).errors)


def test_localhost_url_is_only_warning(registry, storage):
    data = build_osf(registry, storage, PackageOptions(base_url="http://localhost:8000"))
    report = validate_osf(data)
    assert report.ok and any("development URL" in w for w in report.warnings)
