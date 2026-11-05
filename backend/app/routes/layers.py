from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request

from app.config import Settings
from app.layers import Category, Layer, LayerRegistry, Status
from app.osmand.package import tile_url_template
from app.routes.deps import get_registry, get_settings, get_storage, require_layer
from app.schemas import LayerLinks, LayerList, LayerOut
from app.storage.base import Storage

router = APIRouter(prefix="/api/v1", tags=["layers"])


def layer_out(layer: Layer, storage: Storage, settings: Settings, counts: dict[str, int]) -> LayerOut:
    base = settings.base_url
    version = storage.latest_version(layer.id)
    has_data = layer.status != "planned"
    downloads = (
        {fmt: f"{base}/api/v1/datasets/{layer.id}/download?format={fmt}" for fmt in ("geojson", "gpx")}
        if has_data and not layer.verified_only
        else {}
    )
    availability = {
        "online_tiles": "available" if layer.tiles and has_data else "not_available",
        "offline_gpx_in_osf": "available" if layer.osmand.gpx_snapshot and has_data else "not_available",
        "dataset_download": "available" if downloads else "not_available",
        "offline_raster": "planned",
    }
    links = LayerLinks(
        self=f"{base}/api/v1/layers/{layer.id}",
        features=f"{base}/api/v1/layers/{layer.id}/features" if has_data and not layer.verified_only else None,
        tiles=f"{base}/tiles/{layer.id}/{{z}}/{{x}}/{{y}}.png" if layer.tiles else None,
        osmand_tile_template=tile_url_template(base, layer) if layer.tiles else None,
        downloads=downloads,
    )
    return LayerOut(
        **layer.model_dump(),
        feature_count=counts.get(layer.id, 0),
        latest_version=version.version if version else None,
        data_availability=availability,
        links=links,
    )


@router.get("/layers", response_model=LayerList, summary="List available layers")
def list_layers(
    category: Category | None = None,
    status: Status | None = None,
    registry: LayerRegistry = Depends(get_registry),
    storage: Storage = Depends(get_storage),
    settings: Settings = Depends(get_settings),
):
    counts = storage.feature_counts()
    layers = [
        layer_out(l, storage, settings, counts)
        for l in registry.all()
        if (category is None or l.category == category) and (status is None or l.status == status)
    ]
    return LayerList(layers=layers)


@router.get("/layers/{layer_id}", response_model=LayerOut, summary="Layer metadata")
def get_layer(
    request: Request,
    layer_id: str,
    storage: Storage = Depends(get_storage),
    settings: Settings = Depends(get_settings),
):
    layer = require_layer(request, layer_id)
    return layer_out(layer, storage, settings, storage.feature_counts())
