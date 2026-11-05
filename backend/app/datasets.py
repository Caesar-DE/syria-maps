"""Import format, validation and canonical exports for layer datasets.

Import format (GeoJSON FeatureCollection with a ``metadata`` member)::

    {
      "type": "FeatureCollection",
      "metadata": {
        "layer_id": "public-services",
        "source": "…", "license": "ODbL-1.0", "attribution": "…",
        "synthetic": true
      },
      "features": [
        {"type": "Feature", "geometry": {...},
         "properties": {"id": "…", "kind": "hospital", "name_ar": "…", "name_en": "…", ...}}
      ]
    }

Notice datasets use ``"kind": "notices"`` in metadata and per-feature notice fields.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from shapely.geometry import shape
from shapely.validation import explain_validity

from app.geo import in_syria_envelope
from app.layers import LayerRegistry
from app.storage.base import NOTICE_TYPES, VERIFICATION_STATUSES

RESERVED_PROPS = {"id", "kind", "name_ar", "name_en"}
REQUIRED_METADATA = ("layer_id", "source", "license", "attribution")
NOTICE_REQUIRED = ("notice_type", "title_ar", "source_name", "verification_status", "reported_at", "valid_from")


@dataclass
class ValidationReport:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def parse_time(value: Any) -> datetime | None:
    if value in (None, ""):
        return None
    dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if dt.tzinfo is None:
        raise ValueError(f"timestamp without timezone: {value!r}")
    return dt


def validate_dataset(data: dict[str, Any], registry: LayerRegistry) -> ValidationReport:
    report = ValidationReport()
    if data.get("type") != "FeatureCollection":
        report.errors.append("root must be a GeoJSON FeatureCollection")
        return report
    meta = data.get("metadata")
    if not isinstance(meta, dict):
        report.errors.append("missing 'metadata' object (layer_id, source, license, attribution)")
        return report
    for key in REQUIRED_METADATA:
        if not str(meta.get(key) or "").strip():
            report.errors.append(f"metadata.{key} is required")
    layer = registry.get(str(meta.get("layer_id")))
    if layer is None:
        report.errors.append(f"unknown layer_id {meta.get('layer_id')!r}")
        return report
    if layer.synthetic != bool(meta.get("synthetic", False)):
        report.errors.append("metadata.synthetic must match the layer definition")
    is_notices = meta.get("kind") == "notices"
    if layer.verified_only and not is_notices:
        report.errors.append("verified-only layers accept notice datasets only")

    features = data.get("features")
    if not isinstance(features, list):
        report.errors.append("'features' must be an array")
        return report

    seen_ids: set[str] = set()
    for i, feat in enumerate(features):
        where = f"features[{i}]"
        if not isinstance(feat, dict) or feat.get("type") != "Feature":
            report.errors.append(f"{where}: not a GeoJSON Feature")
            continue
        props = feat.get("properties") or {}
        geom = feat.get("geometry")
        try:
            shp = shape(geom)
        except Exception as exc:
            report.errors.append(f"{where}: invalid geometry ({exc})")
            continue
        if shp.is_empty or not shp.is_valid:
            report.errors.append(f"{where}: invalid geometry ({explain_validity(shp)})")
            continue
        if geom["type"] not in layer.geometry_types:
            report.errors.append(f"{where}: geometry {geom['type']} not allowed in layer {layer.id}")
        minx, miny, maxx, maxy = shp.bounds
        if not (in_syria_envelope(minx, miny) and in_syria_envelope(maxx, maxy)):
            report.warnings.append(f"{where}: geometry lies (partly) outside the Syria envelope")

        ext_id = str(props.get("id") or "")
        if not ext_id:
            report.errors.append(f"{where}: properties.id is required")
        elif ext_id in seen_ids:
            report.errors.append(f"{where}: duplicate id {ext_id!r}")
        seen_ids.add(ext_id)

        if is_notices:
            _validate_notice(props, where, report)
        else:
            kind = props.get("kind")
            if layer.kinds and kind not in layer.kinds:
                report.errors.append(f"{where}: kind {kind!r} not defined for layer {layer.id}")
            if not (props.get("name_ar") or props.get("name_en")):
                report.warnings.append(f"{where}: feature has no name")
            if layer.synthetic and "synthetic" not in str(props.get("name_en", "")).lower():
                report.errors.append(f"{where}: synthetic features must say 'synthetic' in name_en")
    return report


def _validate_notice(props: dict[str, Any], where: str, report: ValidationReport) -> None:
    for key in NOTICE_REQUIRED:
        if not props.get(key):
            report.errors.append(f"{where}: {key} is required for notices")
    if props.get("notice_type") and props["notice_type"] not in NOTICE_TYPES:
        report.errors.append(f"{where}: notice_type must be one of {NOTICE_TYPES}")
    status = props.get("verification_status")
    if status and status not in VERIFICATION_STATUSES:
        report.errors.append(f"{where}: verification_status must be one of {VERIFICATION_STATUSES}")
    try:
        times = {k: parse_time(props.get(k)) for k in ("reported_at", "valid_from", "expires_at", "verified_at")}
    except ValueError as exc:
        report.errors.append(f"{where}: {exc}")
        return
    if status == "verified":
        if not times["verified_at"]:
            report.errors.append(f"{where}: verified notices need verified_at")
        if not times["expires_at"]:
            report.errors.append(f"{where}: verified notices need expires_at")
    if times["expires_at"] and times["valid_from"] and times["expires_at"] <= times["valid_from"]:
        report.errors.append(f"{where}: expires_at must be after valid_from")


def canonical_geojson(features: list[dict[str, Any]]) -> bytes:
    return json.dumps(features, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def checksum(features: list[dict[str, Any]]) -> str:
    return hashlib.sha256(canonical_geojson(features)).hexdigest()


def utcnow() -> datetime:
    return datetime.now(timezone.utc)
