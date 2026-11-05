from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request, Response

from app.cache import TTLCache
from app.config import Settings
from app.exports import layer_geojson, layer_gpx
from app.routes.deps import export_cache, get_settings, get_storage, require_layer
from app.schemas import DatasetVersionOut
from app.storage.base import DatasetVersion, Storage

router = APIRouter(prefix="/api/v1/datasets", tags=["datasets"])

MEDIA = {"geojson": "application/geo+json", "gpx": "application/gpx+xml"}


def version_out(v: DatasetVersion, settings: Settings) -> DatasetVersionOut:
    base = f"{settings.base_url}/api/v1/datasets/{v.layer_id}/download"
    return DatasetVersionOut(**vars(v), downloads={fmt: f"{base}?format={fmt}" for fmt in MEDIA})


@router.get("", response_model=list[DatasetVersionOut], summary="All dataset versions")
def list_datasets(storage: Storage = Depends(get_storage), settings: Settings = Depends(get_settings)):
    return [version_out(v, settings) for v in storage.list_versions()]


@router.get("/{layer_id}/version", response_model=DatasetVersionOut, summary="Latest dataset version (update check)")
def dataset_version(request: Request, layer_id: str, storage: Storage = Depends(get_storage),
                    settings: Settings = Depends(get_settings)):
    layer = require_layer(request, layer_id)
    v = storage.latest_version(layer.id)
    if v is None:
        raise HTTPException(404, f"no dataset imported for layer {layer.id!r}")
    return version_out(v, settings)


@router.get("/{layer_id}/download", summary="Download a layer dataset (GeoJSON or GPX)")
def download(
    request: Request,
    layer_id: str,
    format: Literal["geojson", "gpx"] = "geojson",
    storage: Storage = Depends(get_storage),
    cache: TTLCache = Depends(export_cache),
):
    layer = require_layer(request, layer_id)
    if layer.verified_only:
        raise HTTPException(404, "notice layers are available through /api/v1/notices only")
    v = storage.latest_version(layer.id)
    if v is None:
        raise HTTPException(404, f"no dataset imported for layer {layer.id!r}")
    etag = f'"{layer.id}-v{v.version}-{v.checksum[:16]}-{format}"'
    headers = {"ETag": etag, "Cache-Control": "public, max-age=300"}
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers=headers)

    def build() -> bytes:
        features = storage.iter_layer_features(layer.id)
        return layer_geojson(layer, features, v) if format == "geojson" else layer_gpx(layer, features, v)

    body = cache.get_or_set((layer.id, v.version, v.checksum, format), build)
    filename = f"syria-maps-{layer.id}-v{v.version}.{format}"
    headers["Content-Disposition"] = f'attachment; filename="{filename}"'
    return Response(body, media_type=MEDIA[format], headers=headers)
