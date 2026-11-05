"""Command line tools: ``python -m app.cli <command>``.

Commands
  migrate                 apply database/migrations/*.sql
  sync-layers             copy layers/*.yaml into the database
  import <file.geojson>   validate and import a dataset (features or notices)
  seed                    migrate + sync-layers + import database/fixtures/*.geojson
  validate-dataset <f>    validate a dataset file without importing
  validate-layers         validate layers/*.yaml
  build-osf               build the OsmAnd package (works with --storage memory, no DB needed)
  validate-osf <f.osf>    structural validation of an .osf package
  osm-extract             (phase 2) download OSM public services via Overpass into a GeoJSON file
  gen-synthetic-fixture   regenerate the synthetic development fixture
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from app.config import Settings
from app.datasets import load_json, validate_dataset
from app.layers import LayerRegistry


def _settings(args) -> Settings:
    overrides = {}
    if getattr(args, "storage", None):
        overrides["storage"] = args.storage
    if getattr(args, "base_url", None):
        overrides["public_base_url"] = args.base_url
    return Settings(**overrides)


def _registry(settings: Settings) -> LayerRegistry:
    return LayerRegistry.from_dir(settings.layers_dir)


def _postgis(settings: Settings):
    from app.storage.postgis import PostgisStorage

    return PostgisStorage(settings.database_url, max_size=2)


def _print_report(label: str, report) -> int:
    for w in report.warnings:
        print(f"WARN  {label}: {w}")
    for e in report.errors:
        print(f"ERROR {label}: {e}")
    print(f"{'OK' if report.ok else 'FAILED'}: {label} ({len(report.errors)} errors, {len(report.warnings)} warnings)")
    return 0 if report.ok else 1


def cmd_migrate(args) -> int:
    settings = _settings(args)
    db = _postgis(settings)
    applied = db.migrate(settings.database_dir / "migrations")
    print("applied:", ", ".join(applied) if applied else "nothing (up to date)")
    db.close()
    return 0


def cmd_sync_layers(args) -> int:
    settings = _settings(args)
    db = _postgis(settings)
    layers = _registry(settings).all()
    db.sync_layers(layers)
    print(f"synchronised {len(layers)} layers")
    db.close()
    return 0


def _import(db, registry: LayerRegistry, path: Path) -> int:
    data = load_json(path)
    report = validate_dataset(data, registry)
    if _print_report(path.name, report):
        return 1
    version = db.import_collection(data)
    print(f"imported {path.name}: " + (f"new version {version.version}" if version else "no data change"))
    return 0


def cmd_import(args) -> int:
    settings = _settings(args)
    db = _postgis(settings)
    try:
        return _import(db, _registry(settings), Path(args.file))
    finally:
        db.close()


def cmd_seed(args) -> int:
    settings = _settings(args)
    db = _postgis(settings)
    try:
        db.migrate(settings.database_dir / "migrations")
        registry = _registry(settings)
        db.sync_layers(registry.all())
        status = 0
        for path in sorted((settings.database_dir / "fixtures").glob("*.geojson")):
            status |= _import(db, registry, path)
        return status
    finally:
        db.close()


def cmd_validate_dataset(args) -> int:
    settings = _settings(args)
    status = 0
    for f in args.files:
        status |= _print_report(Path(f).name, validate_dataset(load_json(Path(f)), _registry(settings)))
    return status


def cmd_validate_layers(args) -> int:
    settings = _settings(args)
    layers = _registry(settings).all()
    for layer in layers:
        print(f"OK  {layer.id:24} {layer.category:10} {layer.status}")
    return 0


def cmd_build_osf(args) -> int:
    from app.osmand.package import PackageOptions, build_osf
    from app.osmand.validate import validate_osf
    from app.storage import create_storage

    settings = _settings(args)
    storage = create_storage(settings)
    try:
        data = build_osf(_registry(settings), storage,
                         PackageOptions(base_url=settings.base_url, plugin_version=args.plugin_version,
                                        include_gpx=not args.no_gpx))
    finally:
        storage.close()
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)
    print(f"wrote {out} ({len(data)} bytes) for {settings.base_url}")
    return _print_report(out.name, validate_osf(data))


def cmd_validate_osf(args) -> int:
    from app.osmand.validate import validate_osf

    status = 0
    for f in args.files:
        status |= _print_report(Path(f).name, validate_osf(Path(f).read_bytes()))
    return status


def cmd_osm_extract(args) -> int:
    from app.osm import overpass_query, overpass_to_collection

    bbox = tuple(float(v) for v in args.bbox.split(","))
    body = urllib.parse.urlencode({"data": overpass_query(bbox)}).encode()
    req = urllib.request.Request(args.endpoint, data=body, headers={"User-Agent": "syria-maps/0.1 (+github)"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        payload = json.load(resp)
    retrieved = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    collection = overpass_to_collection(payload, retrieved)
    Path(args.output).write_text(json.dumps(collection, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"wrote {len(collection['features'])} features to {args.output}")
    return 0


# Deliberately abstract placement: points on a jittered grid inside a few boxes. They do not
# represent real facilities, and every name says "synthetic".
SYNTHETIC_CLUSTERS = [("A", 36.20, 33.45), ("B", 37.10, 36.15), ("C", 36.70, 34.70), ("D", 35.80, 35.50)]
SYNTHETIC_KINDS = [
    ("hospital", "مستشفى", "Hospital"), ("pharmacy", "صيدلية", "Pharmacy"), ("school", "مدرسة", "School"),
    ("fuel", "محطة وقود", "Fuel station"), ("bus_stop", "موقف حافلات", "Bus stop"), ("parking", "موقف سيارات", "Parking"),
]


def cmd_gen_synthetic_fixture(args) -> int:
    rnd = random.Random(20261105)
    features = []
    for cluster, lon0, lat0 in SYNTHETIC_CLUSTERS:
        for i, (kind, ar, en) in enumerate(SYNTHETIC_KINDS):
            for j in range(2):
                n = i * 2 + j + 1
                lon = round(lon0 + (i - 2.5) * 0.012 + rnd.uniform(-0.003, 0.003), 6)
                lat = round(lat0 + (j - 0.5) * 0.012 + rnd.uniform(-0.003, 0.003), 6)
                features.append({
                    "type": "Feature",
                    "geometry": {"type": "Point", "coordinates": [lon, lat]},
                    "properties": {
                        "id": f"synthetic-{cluster}-{kind}-{j + 1}",
                        "kind": kind,
                        "name_ar": f"{ar} تجريبي {cluster}{n} (بيانات اصطناعية)",
                        "name_en": f"Synthetic {en} {cluster}{n}",
                        "note": "SYNTHETIC DEVELOPMENT DATA — not a real facility",
                    },
                })
    collection = {
        "type": "FeatureCollection",
        "metadata": {
            "layer_id": "public-services",
            "source": "Syria Maps synthetic development fixture (generated by app.cli gen-synthetic-fixture)",
            "license": "CC0-1.0",
            "attribution": "Syria Maps contributors — synthetic test data",
            "synthetic": True,
        },
        "features": features,
    }
    Path(args.output).write_text(json.dumps(collection, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {len(features)} synthetic features to {args.output}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.cli", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    def add(name, fn, **kw):
        p = sub.add_parser(name, **kw)
        p.set_defaults(fn=fn)
        return p

    add("migrate", cmd_migrate)
    add("sync-layers", cmd_sync_layers)
    add("import", cmd_import).add_argument("file")
    add("seed", cmd_seed)
    add("validate-dataset", cmd_validate_dataset).add_argument("files", nargs="+")
    add("validate-layers", cmd_validate_layers)
    p = add("build-osf", cmd_build_osf)
    p.add_argument("--output", default="dist/syria-maps.osf")
    p.add_argument("--base-url", help="public https URL of the API (default: SYRIA_MAPS_PUBLIC_BASE_URL)")
    p.add_argument("--storage", choices=["postgis", "memory"], help="memory = build from database/fixtures")
    p.add_argument("--plugin-version", type=int, default=1)
    p.add_argument("--no-gpx", action="store_true", help="omit offline GPX snapshots")
    add("validate-osf", cmd_validate_osf).add_argument("files", nargs="+")
    p = add("osm-extract", cmd_osm_extract)
    p.add_argument("--bbox", default="35.5,32.3,42.4,37.4", help="minLon,minLat,maxLon,maxLat")
    p.add_argument("--endpoint", default="https://overpass-api.de/api/interpreter")
    p.add_argument("--output", default="osm-public-services.geojson")
    p = add("gen-synthetic-fixture", cmd_gen_synthetic_fixture)
    p.add_argument("--output", default="../database/fixtures/public-services.synthetic.geojson")

    args = parser.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
