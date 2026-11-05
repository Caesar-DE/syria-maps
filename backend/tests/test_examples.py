from __future__ import annotations

import io
import json
import zipfile

from app.config import REPO_ROOT
from app.datasets import load_json, validate_dataset
from app.layers import Layer, LayerRegistry
from app.osmand.package import PackageOptions, build_osf

EXAMPLES = REPO_ROOT / "examples" / "community-layer"


def test_community_example_is_valid(registry):
    import yaml

    layer = Layer.model_validate(yaml.safe_load((EXAMPLES / "water-points.yaml").read_text(encoding="utf-8")))
    reg = LayerRegistry(registry.all() + [layer])
    report = validate_dataset(load_json(EXAMPLES / "water-points.sample.geojson"), reg)
    assert report.ok, report.errors


def test_items_example_is_current(registry, storage):
    """osmand/items.example.json must match what the builder produces."""
    data = build_osf(registry, storage, PackageOptions(base_url="https://maps.example.org"))
    built = json.loads(zipfile.ZipFile(io.BytesIO(data)).read("items.json"))
    assert built == load_json(REPO_ROOT / "osmand" / "items.example.json")
