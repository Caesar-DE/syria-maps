from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from app.config import Settings
from app.exports import feature_to_geojson
from app.layers import Layer
from app.routes.deps import get_settings, get_storage, parse_bbox, parse_near, require_layer, split_csv
from app.storage.base import FeatureQuery, Storage

router = APIRouter(prefix="/api/v1", tags=["features"])

PUBLIC_SERVICES_LAYER = "public-services"


def _feature_collection(request: Request, layer: Layer, storage: Storage, q: FeatureQuery) -> dict:
    rows, total = storage.query_features(q)
    params = {k: v for k, v in request.query_params.items() if k not in ("offset",)}
    links = []
    if q.offset + len(rows) < total:
        links.append({"rel": "next", "href": f"{request.url.path}?{urlencode({**params, 'offset': q.offset + q.limit})}"})
    if q.offset > 0:
        links.append({"rel": "prev", "href": f"{request.url.path}?{urlencode({**params, 'offset': max(0, q.offset - q.limit)})}"})
    return {
        "type": "FeatureCollection",
        "layer": {"id": layer.id, "license": layer.license, "attribution": layer.attribution,
                  "synthetic": layer.synthetic},
        "numberMatched": total,
        "numberReturned": len(rows),
        "offset": q.offset,
        "limit": q.limit,
        "links": links,
        "features": [feature_to_geojson(f) for f in rows],
    }


def _check_queryable(layer: Layer) -> None:
    if layer.status == "planned":
        raise HTTPException(404, f"layer {layer.id!r} is planned and has no data yet")
    if layer.verified_only:
        raise HTTPException(404, f"layer {layer.id!r} is published through /api/v1/notices only")


@router.get("/layers/{layer_id}/features", summary="Query features of a layer (GeoJSON)")
def layer_features(
    request: Request,
    layer_id: str,
    bbox: str | None = Query(None, description="minLon,minLat,maxLon,maxLat (WGS84)"),
    kind: str | None = Query(None, description="Comma separated kinds, e.g. hospital,pharmacy"),
    q: str | None = Query(None, min_length=2, max_length=100, description="Text search in Arabic/English names"),
    near: str | None = Query(None, description="lat,lon — sort by distance"),
    radius_m: float | None = Query(None, gt=0, le=100_000, description="Max distance from 'near' in metres"),
    limit: int = Query(100, ge=1),
    offset: int = Query(0, ge=0, le=1_000_000),
    storage: Storage = Depends(get_storage),
    settings: Settings = Depends(get_settings),
):
    layer = require_layer(request, layer_id)
    _check_queryable(layer)
    if limit > settings.max_page_size:
        raise HTTPException(422, f"limit must be <= {settings.max_page_size}")
    point = parse_near(near)
    if radius_m is not None and point is None:
        raise HTTPException(422, "radius_m requires near")
    kinds = split_csv(kind)
    unknown = [k for k in kinds if layer.kinds and k not in layer.kinds]
    if unknown:
        raise HTTPException(422, f"unknown kind(s) {unknown}; allowed: {sorted(layer.kinds)}")
    query = FeatureQuery(layer_id=layer.id, bbox=parse_bbox(bbox), kinds=kinds, text=q,
                         near=point, radius_m=radius_m, limit=limit, offset=offset)
    return _feature_collection(request, layer, storage, query)


@router.get("/layers/{layer_id}/features/{feature_id}", summary="Single feature (GeoJSON)")
def layer_feature(request: Request, layer_id: str, feature_id: int, storage: Storage = Depends(get_storage)):
    layer = require_layer(request, layer_id)
    _check_queryable(layer)
    feature = storage.get_feature(layer.id, feature_id)
    if feature is None:
        raise HTTPException(404, "feature not found")
    return feature_to_geojson(feature)


@router.get("/public-services", summary="Public-service points (hospitals, pharmacies, schools, …)")
def public_services(
    request: Request,
    bbox: str | None = Query(None, description="minLon,minLat,maxLon,maxLat"),
    kind: str | None = Query(None, description="hospital, pharmacy, school, fuel, bus_stop, parking, …"),
    near: str | None = Query(None, description="lat,lon"),
    radius_m: float | None = Query(None, gt=0, le=100_000),
    limit: int = Query(100, ge=1),
    offset: int = Query(0, ge=0, le=1_000_000),
    storage: Storage = Depends(get_storage),
    settings: Settings = Depends(get_settings),
):
    return layer_features(request, PUBLIC_SERVICES_LAYER, bbox, kind, None, near, radius_m, limit, offset,
                          storage, settings)
