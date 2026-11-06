from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.layers import Layer

BASE = "https://maps.example.org"


def test_list_layers(client):
    layers = {l["id"]: l for l in client.get("/api/v1/layers").json()["layers"]}
    assert {"public-services", "streets-places", "buildings-3d", "traffic", "safety-notices"} <= layers.keys()
    ps = layers["public-services"]
    assert ps["status"] == "implemented"
    assert ps["synthetic"] is True
    assert ps["feature_count"] == 48
    assert ps["latest_version"] == 1
    assert ps["license"] and ps["attribution"]
    assert ps["links"]["osmand_tile_template"] == f"{BASE}/tiles/public-services/{{0}}/{{1}}/{{2}}@2x.png"
    assert ps["links"]["osmand_tile_size"] == 512
    assert ps["data_availability"]["online_tiles"] == "available"
    assert ps["data_availability"]["offline_raster"] == "planned"


def test_planned_layers_expose_no_data(client):
    layer = client.get("/api/v1/layers/buildings-3d").json()
    assert layer["status"] == "planned"
    assert layer["links"]["features"] is None
    assert layer["links"]["tiles"] is None
    assert set(layer["data_availability"].values()) <= {"not_available", "planned"}


def test_filter_layers(client):
    r = client.get("/api/v1/layers", params={"category": "safety"}).json()["layers"]
    assert [l["id"] for l in r] == ["safety-notices"]
    assert client.get("/api/v1/layers", params={"category": "nope"}).status_code == 422


def test_unknown_layer(client):
    assert client.get("/api/v1/layers/does-not-exist").status_code == 404


def _layer(**overrides) -> dict:
    data = {
        "id": "x-layer", "title": {"ar": "س", "en": "x"}, "description": {"ar": "س", "en": "x"},
        "category": "community", "status": "experimental", "geometry_types": ["Point"],
        "license": "CC0-1.0", "attribution": "a", "source": {"name": "s"},
    }
    data.update(overrides)
    return data


@pytest.mark.parametrize("overrides", [
    {"id": "Bad_ID"},
    {"category": "safety"},  # safety must be verified_only
    {"osmand": {"map_source": True}},  # needs tiles
    {"status": "planned", "tiles": True},
    {"zoom": {"min": 15, "max": 3}},
    {"kinds": {"a": {"color": "red", "label": {"ar": "a", "en": "a"}}}},
])
def test_layer_definition_rules(overrides):
    with pytest.raises(ValidationError):
        Layer.model_validate(_layer(**overrides))


def test_layer_definition_ok():
    assert Layer.model_validate(_layer()).id == "x-layer"
