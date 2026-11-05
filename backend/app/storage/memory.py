"""In-memory storage backed by GeoJSON files.

Used by the test suite and for Docker-less development (``SYRIA_MAPS_STORAGE=memory``).
Semantics mirror :mod:`app.storage.postgis`; the PostGIS integration tests check both
agree on the same fixture.
"""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path
from typing import Any

from shapely.geometry import Point, box, shape

from app.datasets import checksum, load_json, parse_time, utcnow
from app.geo import BBox, haversine_m
from app.storage.base import DatasetVersion, Feature, FeatureQuery, Notice, NoticeQuery


class MemoryStorage:
    def __init__(self) -> None:
        self._features: dict[str, list[Feature]] = {}
        self._shapes: dict[int, Any] = {}
        self._notices: list[Notice] = []
        self._versions: dict[str, list[DatasetVersion]] = {}
        self._next_id = 1

    @classmethod
    def from_files(cls, paths: list[Path]) -> "MemoryStorage":
        storage = cls()
        for path in paths:
            storage.load_collection(load_json(path))
        return storage

    def load_collection(self, data: dict[str, Any]) -> None:
        meta = data["metadata"]
        layer_id = meta["layer_id"]
        now = utcnow()
        if meta.get("kind") == "notices":
            for feat in data["features"]:
                self._notices.append(self._notice_from_geojson(layer_id, meta, feat, now))
            return
        rows = self._features.setdefault(layer_id, [])
        for feat in data["features"]:
            props = dict(feat.get("properties") or {})
            fid = self._next_id
            self._next_id += 1
            rows.append(
                Feature(
                    id=fid,
                    layer_id=layer_id,
                    geometry=feat["geometry"],
                    kind=props.pop("kind", None),
                    name_ar=props.pop("name_ar", None),
                    name_en=props.pop("name_en", None),
                    external_id=str(props.pop("id")),
                    properties=props,
                    source=meta["source"],
                    license=meta["license"],
                    attribution=meta["attribution"],
                    is_synthetic=bool(meta.get("synthetic", False)),
                    updated_at=now,
                )
            )
            self._shapes[fid] = shape(feat["geometry"])
        versions = self._versions.setdefault(layer_id, [])
        versions.append(
            DatasetVersion(
                layer_id=layer_id,
                version=len(versions) + 1,
                checksum=checksum(data["features"]),
                feature_count=len(rows),
                created_at=now,
            )
        )

    def _notice_from_geojson(self, layer_id: str, meta: dict, feat: dict, now) -> Notice:
        p = feat["properties"]
        nid = self._next_id
        self._next_id += 1
        return Notice(
            id=nid,
            layer_id=layer_id,
            notice_type=p["notice_type"],
            geometry=feat["geometry"],
            title_ar=p["title_ar"],
            title_en=p.get("title_en"),
            description_ar=p.get("description_ar"),
            description_en=p.get("description_en"),
            source_name=p["source_name"],
            source_url=p.get("source_url"),
            verification_status=p["verification_status"],
            verified_at=parse_time(p.get("verified_at")),
            reported_at=parse_time(p["reported_at"]),
            valid_from=parse_time(p["valid_from"]),
            expires_at=parse_time(p.get("expires_at")),
            updated_at=now,
            is_synthetic=bool(meta.get("synthetic", False)),
        )

    # -- Storage protocol -------------------------------------------------

    def ping(self) -> bool:
        return True

    def query_features(self, q: FeatureQuery) -> tuple[list[Feature], int]:
        rows = self._features.get(q.layer_id, [])
        bbox_shape = box(q.bbox.min_lon, q.bbox.min_lat, q.bbox.max_lon, q.bbox.max_lat) if q.bbox else None
        text = q.text.lower() if q.text else None
        matched: list[Feature] = []
        for f in rows:
            shp = self._shapes[f.id]
            if bbox_shape is not None and not bbox_shape.intersects(shp):
                continue
            if q.kinds and f.kind not in q.kinds:
                continue
            if text and text not in (f.name_ar or "").lower() and text not in (f.name_en or "").lower():
                continue
            distance = None
            if q.near is not None:
                # Distance to the closest point of the geometry (exact for points).
                nearest = shp.interpolate(shp.project(_point(*q.near))) if shp.geom_type != "Point" else shp
                distance = haversine_m(q.near[0], q.near[1], nearest.x, nearest.y)
                if q.radius_m is not None and distance > q.radius_m:
                    continue
            matched.append(_with_distance(f, distance))
        if q.near is not None:
            matched.sort(key=lambda f: (f.distance_m, f.id))
        return matched[q.offset : q.offset + q.limit], len(matched)

    def get_feature(self, layer_id: str, feature_id: int) -> Feature | None:
        return next((f for f in self._features.get(layer_id, []) if f.id == feature_id), None)

    def iter_layer_features(self, layer_id: str) -> list[Feature]:
        return list(self._features.get(layer_id, []))

    def features_in_bbox(self, layer_id: str, bbox: BBox, limit: int) -> list[Feature]:
        rows, _ = self.query_features(FeatureQuery(layer_id=layer_id, bbox=bbox, limit=limit))
        return rows

    def public_notices(self, q: NoticeQuery) -> tuple[list[Notice], int]:
        bbox_shape = box(q.bbox.min_lon, q.bbox.min_lat, q.bbox.max_lon, q.bbox.max_lat) if q.bbox else None
        matched = [
            n
            for n in self._notices
            if is_publishable(n, q.now)
            and (not q.layer_ids or n.layer_id in q.layer_ids)
            and (not q.notice_types or n.notice_type in q.notice_types)
            and (bbox_shape is None or bbox_shape.intersects(shape(n.geometry)))
        ]
        matched.sort(key=lambda n: (n.valid_from, n.id), reverse=True)
        return matched[q.offset : q.offset + q.limit], len(matched)

    def latest_version(self, layer_id: str) -> DatasetVersion | None:
        versions = self._versions.get(layer_id)
        return versions[-1] if versions else None

    def list_versions(self, layer_id: str | None = None) -> list[DatasetVersion]:
        if layer_id:
            return list(self._versions.get(layer_id, []))
        return [v for vs in self._versions.values() for v in vs]

    def feature_counts(self) -> dict[str, int]:
        return {k: len(v) for k, v in self._features.items()}

    def close(self) -> None:
        pass


def is_publishable(n: Notice, now) -> bool:
    """Single source of truth for the public notice rule (mirrored in SQL)."""
    return (
        n.verification_status == "verified"
        and n.verified_at is not None
        and bool(n.source_name.strip())
        and n.expires_at is not None
        and n.valid_from <= now < n.expires_at
    )


def _point(lon: float, lat: float) -> Point:
    return Point(lon, lat)


def _with_distance(f: Feature, distance: float | None) -> Feature:
    return f if distance is None else replace(f, distance_m=round(distance, 1))
