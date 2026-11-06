from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Path, Request, Response

from app.cache import TTLCache
from app.routes.deps import get_storage, require_layer, tile_cache
from app.storage.base import Storage
from app.tiles import render_tile

router = APIRouter(tags=["tiles"])


def _serve(request: Request, layer_id: str, z: int, x: int, y: int, scale: int,
           storage: Storage, cache: TTLCache) -> Response:
    layer = require_layer(request, layer_id)
    if not layer.tiles or layer.status == "planned":
        raise HTTPException(404, f"layer {layer.id!r} has no tiles")
    if x >= 2**z or y >= 2**z:
        raise HTTPException(404, "tile coordinates out of range")
    version = storage.latest_version(layer.id)
    key = (layer.id, version.checksum if version else None, z, x, y, scale)
    png = cache.get_or_set(key, lambda: render_tile(storage, layer, z, x, y, scale))
    return Response(png, media_type="image/png", headers={"Cache-Control": "public, max-age=3600"})


# Registered before the 256 px route: "{y}.png" would otherwise also match "5@2x.png".
@router.get(
    "/tiles/{layer_id}/{z}/{x}/{y}@2x.png",
    summary="High-density overlay tile (XYZ, 512 px) for 2-3x phone screens",
    responses={200: {"content": {"image/png": {}}}},
)
def tile_2x(
    request: Request,
    layer_id: str,
    z: int = Path(ge=0, le=22),
    x: int = Path(ge=0),
    y: int = Path(ge=0),
    storage: Storage = Depends(get_storage),
    cache: TTLCache = Depends(tile_cache),
):
    return _serve(request, layer_id, z, x, y, 2, storage, cache)


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
    return _serve(request, layer_id, z, x, y, 1, storage, cache)
