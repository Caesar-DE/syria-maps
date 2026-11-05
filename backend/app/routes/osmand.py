from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter, Depends, HTTPException, Request, Response

from app.cache import TTLCache
from app.config import Settings
from app.layers import LayerRegistry
from app.osmand.package import PackageOptions, build_osf, map_source_name, tile_url_template
from app.routes.deps import export_cache, get_registry, get_settings, get_storage, require_layer
from app.storage.base import Storage

router = APIRouter(prefix="/osmand", tags=["osmand"])


@router.get("/syria-maps.osf", summary="OsmAnd import package built for this server",
            responses={200: {"content": {"application/octet-stream": {}}}})
def osf_package(
    storage: Storage = Depends(get_storage),
    registry: LayerRegistry = Depends(get_registry),
    settings: Settings = Depends(get_settings),
    cache: TTLCache = Depends(export_cache),
):
    versions = tuple((v.layer_id, v.checksum) for v in storage.list_versions())
    data = cache.get_or_set(
        ("osf", settings.base_url, versions),
        lambda: build_osf(registry, storage, PackageOptions(base_url=settings.base_url)),
    )
    return Response(data, media_type="application/octet-stream",
                    headers={"Content-Disposition": 'attachment; filename="syria-maps.osf"'})


@router.get("/add-tile-source/{layer_id}", summary="osmand.net link that adds the layer as a tile source")
def add_tile_source_link(request: Request, layer_id: str, settings: Settings = Depends(get_settings)):
    """Alternative to the .osf: OsmAnd documents ``https://osmand.net/add-tile-source?...`` links
    that open the app and add an online tile source (see docs/osmand-integration.md)."""
    layer = require_layer(request, layer_id)
    if not layer.tiles or layer.status == "planned":
        raise HTTPException(404, f"layer {layer.id!r} has no tiles")
    query = urlencode({
        "name": map_source_name(layer),
        "min_zoom": layer.zoom.min,
        "max_zoom": layer.zoom.max,
        "url_template": tile_url_template(settings.base_url, layer),
    })
    return {"layer_id": layer.id, "url": f"https://osmand.net/add-tile-source?{query}",
            "verified_on_device": False}
