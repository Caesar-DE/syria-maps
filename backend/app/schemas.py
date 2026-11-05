from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel

from app.layers import Layer


class Health(BaseModel):
    status: str
    storage: str
    database: str
    version: str


class DatasetVersionOut(BaseModel):
    layer_id: str
    version: int
    checksum: str
    feature_count: int
    created_at: datetime
    downloads: dict[str, str]


class LayerLinks(BaseModel):
    self: str
    features: str | None = None
    tiles: str | None = None
    osmand_tile_template: str | None = None
    downloads: dict[str, str] = {}


class LayerOut(Layer):
    feature_count: int
    latest_version: int | None
    data_availability: dict[str, str]
    links: LayerLinks


class LayerList(BaseModel):
    layers: list[LayerOut]
