from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Path, Request, Response

from app.cache import TTLCache
from app.routes.deps import get_storage, require_layer, tile_cache
from app.storage.base import Storage
from app.tiles import render_tile

router = APIRouter(tags=["tiles"])


@router.get(
    "/tiles/{layer_id}/{z}/{x}/{y}.png",
    summary="Transparent overlay tile (XYZ, 256 px)",
    responses={200: {"content": {"image/png": {}}}},
)
def tile(
    request: Request,
    layer_id: str,
    z: int = Path(ge=0, le=22),
    x: int = Path(ge=0),
    y: int = Path(ge=0),
    storage: Storage = Depends(get_storage),
    cache: TTLCache = Depends(tile_cache),
):
    layer = require_layer(request, layer_id)
    if not layer.tiles or layer.status == "planned":
        raise HTTPException(404, f"layer {layer.id!r} has no tiles")
    if x >= 2**z or y >= 2**z:
        raise HTTPException(404, "tile coordinates out of range")
    version = storage.latest_version(layer.id)
    key = (layer.id, version.checksum if version else None, z, x, y)
    png = cache.get_or_set(key, lambda: render_tile(storage, layer, z, x, y))
    return Response(png, media_type="image/png", headers={"Cache-Control": "public, max-age=3600"})
