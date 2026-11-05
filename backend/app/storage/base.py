"""Storage interface shared by the PostGIS and in-memory implementations."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Protocol

from app.geo import BBox

VERIFICATION_STATUSES = ("unverified", "verified", "rejected")
NOTICE_TYPES = (
    "road_closure",
    "construction",
    "congestion",
    "restriction",
    "road_hazard",
    "safety_notice",
)


@dataclass
class Feature:
    id: int
    layer_id: str
    geometry: dict[str, Any]
    kind: str | None
    name_ar: str | None
    name_en: str | None
    properties: dict[str, Any]
    source: str
    license: str
    attribution: str
    is_synthetic: bool
    updated_at: datetime
    external_id: str | None = None
    distance_m: float | None = None


@dataclass
class Notice:
    id: int
    layer_id: str
    notice_type: str
    geometry: dict[str, Any]
    title_ar: str
    title_en: str | None
    description_ar: str | None
    description_en: str | None
    source_name: str
    source_url: str | None
    verification_status: str
    verified_at: datetime | None
    reported_at: datetime
    valid_from: datetime
    expires_at: datetime | None
    updated_at: datetime
    is_synthetic: bool = False


@dataclass
class DatasetVersion:
    layer_id: str
    version: int
    checksum: str
    feature_count: int
    created_at: datetime


@dataclass
class FeatureQuery:
    layer_id: str
    bbox: BBox | None = None
    kinds: list[str] = field(default_factory=list)
    text: str | None = None
    near: tuple[float, float] | None = None  # (lon, lat)
    radius_m: float | None = None
    limit: int = 100
    offset: int = 0


@dataclass
class NoticeQuery:
    now: datetime
    layer_ids: list[str] = field(default_factory=list)
    notice_types: list[str] = field(default_factory=list)
    bbox: BBox | None = None
    limit: int = 100
    offset: int = 0


class Storage(Protocol):
    def ping(self) -> bool: ...

    def query_features(self, q: FeatureQuery) -> tuple[list[Feature], int]: ...

    def get_feature(self, layer_id: str, feature_id: int) -> Feature | None: ...

    def iter_layer_features(self, layer_id: str) -> list[Feature]: ...

    def features_in_bbox(self, layer_id: str, bbox: BBox, limit: int) -> list[Feature]: ...

    def public_notices(self, q: NoticeQuery) -> tuple[list[Notice], int]: ...

    def latest_version(self, layer_id: str) -> DatasetVersion | None: ...

    def list_versions(self, layer_id: str | None = None) -> list[DatasetVersion]: ...

    def feature_counts(self) -> dict[str, int]: ...

    def close(self) -> None: ...
