from __future__ import annotations

from fastapi import HTTPException, Request

from app.cache import TTLCache
from app.config import Settings
from app.geo import BBox
from app.layers import Layer, LayerRegistry
from app.storage.base import Storage


def get_storage(request: Request) -> Storage:
    return request.app.state.storage


def get_registry(request: Request) -> LayerRegistry:
    return request.app.state.registry


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def tile_cache(request: Request) -> TTLCache:
    return request.app.state.tile_cache


def export_cache(request: Request) -> TTLCache:
    return request.app.state.export_cache


def require_layer(request: Request, layer_id: str) -> Layer:
    layer = request.app.state.registry.get(layer_id)
    if layer is None:
        raise HTTPException(404, f"unknown layer {layer_id!r}")
    return layer


def parse_bbox(value: str | None) -> BBox | None:
    if value is None:
        return None
    try:
        return BBox.parse(value)
    except ValueError as exc:
        raise HTTPException(422, f"invalid bbox: {exc}") from None


def parse_near(value: str | None) -> tuple[float, float] | None:
    """``near=lat,lon`` (latitude first, as people usually write coordinates)."""
    if value is None:
        return None
    try:
        lat, lon = (float(p) for p in value.split(","))
    except ValueError:
        raise HTTPException(422, "near must be 'lat,lon'") from None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise HTTPException(422, "near is out of range")
    return lon, lat


def split_csv(value: str | None) -> list[str]:
    return [v.strip() for v in value.split(",") if v.strip()] if value else []
