"""File-based layer registry.

Every layer is described by one YAML file in ``layers/``. Contributors add a layer by
adding a file (see docs/adding-layers.md); no core code changes are needed.
"""

from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, Field, field_validator, model_validator

LAYER_ID_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
HEX_COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")

Category = Literal["base", "services", "buildings", "traffic", "safety", "community"]
Status = Literal["implemented", "experimental", "planned"]


class Localized(BaseModel):
    ar: str
    en: str


class Source(BaseModel):
    name: str
    url: str | None = None


class KindStyle(BaseModel):
    color: str = "#1565c0"
    label: Localized

    @field_validator("color")
    @classmethod
    def _color(cls, v: str) -> str:
        if not HEX_COLOR_RE.match(v):
            raise ValueError(f"color must be #RRGGBB, got {v!r}")
        return v.lower()


class ZoomRange(BaseModel):
    min: int = Field(0, ge=0, le=22)
    max: int = Field(19, ge=0, le=22)

    @model_validator(mode="after")
    def _order(self) -> "ZoomRange":
        if self.min > self.max:
            raise ValueError("zoom.min must be <= zoom.max")
        return self


class OsmandOptions(BaseModel):
    # Publish the layer's tile endpoint as a MAP_SOURCES entry in the .osf package.
    map_source: bool = False
    # Embed a GPX snapshot of the layer in the .osf package (offline waypoints).
    gpx_snapshot: bool = False


class Layer(BaseModel):
    id: str
    title: Localized
    description: Localized
    category: Category
    status: Status
    geometry_types: list[Literal["Point", "LineString", "Polygon", "MultiPolygon", "MultiLineString"]]
    license: str
    attribution: str
    source: Source
    synthetic: bool = False
    # Public safety layers only expose verified, unexpired records.
    verified_only: bool = False
    zoom: ZoomRange = ZoomRange()
    tiles: bool = False
    osmand: OsmandOptions = OsmandOptions()
    kinds: dict[str, KindStyle] = {}
    notes: Localized | None = None

    @field_validator("id")
    @classmethod
    def _id(cls, v: str) -> str:
        if not LAYER_ID_RE.match(v):
            raise ValueError("id must be lowercase kebab-case, e.g. 'public-services'")
        return v

    @model_validator(mode="after")
    def _consistency(self) -> "Layer":
        if self.category == "safety" and not self.verified_only:
            raise ValueError("safety layers must set verified_only: true")
        if self.osmand.map_source and not self.tiles:
            raise ValueError("osmand.map_source requires tiles: true")
        if self.status == "planned" and (self.tiles or self.osmand.gpx_snapshot):
            raise ValueError("planned layers cannot publish tiles or snapshots")
        return self

    def color_for(self, kind: str | None) -> str:
        style = self.kinds.get(kind or "")
        return style.color if style else "#455a64"


class LayerRegistry:
    def __init__(self, layers: list[Layer]):
        self._layers = {layer.id: layer for layer in layers}

    @classmethod
    def from_dir(cls, path: Path) -> "LayerRegistry":
        layers: list[Layer] = []
        for file in sorted(path.glob("*.yaml")):
            with file.open(encoding="utf-8") as fh:
                data = yaml.safe_load(fh)
            try:
                layer = Layer.model_validate(data)
            except Exception as exc:  # pragma: no cover - message enrichment only
                raise ValueError(f"{file.name}: {exc}") from exc
            if file.stem != layer.id:
                raise ValueError(f"{file.name}: file name must match layer id {layer.id!r}")
            layers.append(layer)
        if not layers:
            raise ValueError(f"no layer definitions found in {path}")
        return cls(layers)

    def all(self) -> list[Layer]:
        return list(self._layers.values())

    def get(self, layer_id: str) -> Layer | None:
        return self._layers.get(layer_id)


@lru_cache
def load_registry(path: str) -> LayerRegistry:
    return LayerRegistry.from_dir(Path(path))
