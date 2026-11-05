"""Dataset exports: GeoJSON (with attribution) and GPX 1.1 waypoints for OsmAnd."""

from __future__ import annotations

import json
from datetime import datetime
from typing import Any
from xml.sax.saxutils import escape, quoteattr

from shapely.geometry import shape

from app.layers import Layer
from app.storage.base import DatasetVersion, Feature, Notice

OSMAND_GPX_NS = "https://osmand.net"


def feature_to_geojson(f: Feature) -> dict[str, Any]:
    props: dict[str, Any] = {
        "id": f.id,
        "external_id": f.external_id,
        "layer_id": f.layer_id,
        "kind": f.kind,
        "name_ar": f.name_ar,
        "name_en": f.name_en,
        **f.properties,
        "source": f.source,
        "license": f.license,
        "attribution": f.attribution,
        "synthetic": f.is_synthetic,
        "updated_at": f.updated_at.isoformat(),
    }
    if f.distance_m is not None:
        props["distance_m"] = f.distance_m
    return {"type": "Feature", "id": f.id, "geometry": f.geometry, "properties": props}


def notice_to_geojson(n: Notice) -> dict[str, Any]:
    def iso(dt: datetime | None) -> str | None:
        return dt.isoformat() if dt else None

    return {
        "type": "Feature",
        "id": n.id,
        "geometry": n.geometry,
        "properties": {
            "id": n.id,
            "layer_id": n.layer_id,
            "notice_type": n.notice_type,
            "title_ar": n.title_ar,
            "title_en": n.title_en,
            "description_ar": n.description_ar,
            "description_en": n.description_en,
            "source_name": n.source_name,
            "source_url": n.source_url,
            "verification_status": n.verification_status,
            "verified_at": iso(n.verified_at),
            "reported_at": iso(n.reported_at),
            "valid_from": iso(n.valid_from),
            "expires_at": iso(n.expires_at),
            "updated_at": iso(n.updated_at),
            "synthetic": n.is_synthetic,
        },
    }


def layer_geojson(layer: Layer, features: list[Feature], version: DatasetVersion | None) -> bytes:
    doc = {
        "type": "FeatureCollection",
        "metadata": {
            "layer_id": layer.id,
            "version": version.version if version else None,
            "checksum": version.checksum if version else None,
            "license": layer.license,
            "attribution": layer.attribution,
            "synthetic": layer.synthetic,
        },
        "features": [feature_to_geojson(f) for f in features],
    }
    return json.dumps(doc, ensure_ascii=False).encode("utf-8")


def layer_gpx(layer: Layer, features: list[Feature], version: DatasetVersion | None) -> bytes:
    """GPX 1.1 with one waypoint per feature (lines/polygons use a representative point)."""
    synthetic_note = " — SYNTHETIC DEVELOPMENT DATA, NOT REAL LOCATIONS" if layer.synthetic else ""
    out = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<gpx version="1.1" creator="Syria Maps" xmlns="http://www.topografix.com/GPX/1/1" '
        f'xmlns:osmand="{OSMAND_GPX_NS}">',
        "  <metadata>",
        f"    <name>{escape(layer.title.ar)} / {escape(layer.title.en)}</name>",
        f"    <desc>{escape(layer.attribution + synthetic_note)}</desc>",
        f"    <copyright author={quoteattr(layer.attribution)}>"
        f"<license>{escape(layer.license)}</license></copyright>",
    ]
    if version:
        out.append(f"    <keywords>syria-maps,{escape(layer.id)},v{version.version}</keywords>")
    out.append("  </metadata>")
    for f in features:
        pt = shape(f.geometry).representative_point()
        name = f.name_ar or f.name_en or f.external_id or str(f.id)
        kind_style = layer.kinds.get(f.kind or "")
        desc_parts = [p for p in (f.name_en, f"{layer.attribution}{synthetic_note}") if p]
        out += [
            f'  <wpt lat="{pt.y:.7f}" lon="{pt.x:.7f}">',
            f"    <name>{escape(name)}</name>",
            f"    <desc>{escape(' — '.join(desc_parts))}</desc>",
            f"    <type>{escape(kind_style.label.ar if kind_style else (f.kind or layer.title.ar))}</type>",
            "    <extensions>",
            f"      <osmand:color>{layer.color_for(f.kind)}</osmand:color>",
            "    </extensions>",
            "  </wpt>",
        ]
    out.append("</gpx>")
    return ("\n".join(out) + "\n").encode("utf-8")
