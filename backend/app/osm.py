"""Phase 2 (prepared): convert OpenStreetMap Overpass JSON into the project import format.

Data from OpenStreetMap is © OpenStreetMap contributors and licensed under ODbL-1.0.
Arabic names are preferred (``name:ar`` → ``name``), English from ``name:en``.
"""

from __future__ import annotations

from typing import Any

OSM_ATTRIBUTION = "© OpenStreetMap contributors"
OSM_LICENSE = "ODbL-1.0"

# OSM tag -> project kind for the public-services layer.
PUBLIC_SERVICE_TAGS: dict[tuple[str, str], str] = {
    ("amenity", "hospital"): "hospital",
    ("amenity", "clinic"): "clinic",
    ("amenity", "pharmacy"): "pharmacy",
    ("amenity", "school"): "school",
    ("amenity", "university"): "university",
    ("amenity", "fuel"): "fuel",
    ("highway", "bus_stop"): "bus_stop",
    ("amenity", "bus_station"): "bus_station",
    ("amenity", "parking"): "parking",
    ("amenity", "police"): "police",
    ("amenity", "fire_station"): "fire_station",
    ("amenity", "post_office"): "post_office",
}
KEEP_TAGS = ("addr:city", "addr:street", "opening_hours", "phone", "website", "operator", "healthcare", "emergency")


def overpass_query(bbox: tuple[float, float, float, float], timeout: int = 180) -> str:
    """Overpass QL for all public-service tags in (min_lon, min_lat, max_lon, max_lat)."""
    s, w, n, e = bbox[1], bbox[0], bbox[3], bbox[2]
    parts = [f'nwr["{k}"="{v}"]({s},{w},{n},{e});' for k, v in PUBLIC_SERVICE_TAGS]
    return f"[out:json][timeout:{timeout}];(\n  " + "\n  ".join(parts) + "\n);\nout center tags;"


def classify(tags: dict[str, str]) -> str | None:
    for (key, value), kind in PUBLIC_SERVICE_TAGS.items():
        if tags.get(key) == value:
            return kind
    return None


def overpass_to_collection(payload: dict[str, Any], retrieved_at: str) -> dict[str, Any]:
    features = []
    for el in payload.get("elements", []):
        tags = el.get("tags") or {}
        kind = classify(tags)
        if kind is None:
            continue
        if el["type"] == "node":
            lon, lat = el["lon"], el["lat"]
        elif "center" in el:
            lon, lat = el["center"]["lon"], el["center"]["lat"]
        else:
            continue
        props = {
            "id": f"osm:{el['type']}/{el['id']}",
            "kind": kind,
            "name_ar": tags.get("name:ar") or tags.get("name"),
            "name_en": tags.get("name:en"),
            "osm_url": f"https://www.openstreetmap.org/{el['type']}/{el['id']}",
        }
        props.update({k: tags[k] for k in KEEP_TAGS if k in tags})
        features.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [lon, lat]},
                         "properties": props})
    return {
        "type": "FeatureCollection",
        "metadata": {
            "layer_id": "osm-public-services",
            "source": f"OpenStreetMap via Overpass API, retrieved {retrieved_at}",
            "license": OSM_LICENSE,
            "attribution": OSM_ATTRIBUTION,
            "synthetic": False,
        },
        "features": features,
    }
