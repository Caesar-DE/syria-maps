from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from app.datasets import utcnow
from app.exports import notice_to_geojson
from app.layers import LayerRegistry
from app.routes.deps import get_registry, get_storage, parse_bbox, split_csv
from app.storage.base import NOTICE_TYPES, NoticeQuery, Storage

router = APIRouter(prefix="/api/v1", tags=["notices"])

DISCLAIMER = {
    "ar": "تُعرض هنا فقط الإشعارات الموثقة من مصادر معتمدة وغير منتهية الصلاحية. "
    "عدم وجود إشعار لا يعني أن المنطقة أو الطريق آمن.",
    "en": "Only verified, sourced, unexpired notices are shown. "
    "The absence of a notice does NOT mean an area or route is safe.",
}


def _notices(storage: Storage, registry: LayerRegistry, layers: list[str], types: list[str],
             bbox: str | None, limit: int, offset: int) -> dict:
    for t in types:
        if t not in NOTICE_TYPES:
            raise HTTPException(422, f"unknown notice type {t!r}; allowed: {list(NOTICE_TYPES)}")
    for layer_id in layers:
        layer = registry.get(layer_id)
        if layer is None or layer.category not in ("traffic", "safety"):
            raise HTTPException(422, f"{layer_id!r} is not a traffic/safety layer")
    rows, total = storage.public_notices(
        NoticeQuery(now=utcnow(), layer_ids=layers, notice_types=types, bbox=parse_bbox(bbox),
                    limit=limit, offset=offset)
    )
    return {
        "type": "FeatureCollection",
        "disclaimer": DISCLAIMER,
        "numberMatched": total,
        "numberReturned": len(rows),
        "features": [notice_to_geojson(n) for n in rows],
    }


@router.get("/notices", summary="Verified traffic and safety notices")
def notices(
    layer: str | None = Query(None, description="traffic, safety-notices (comma separated)"),
    type: str | None = Query(None, description=f"Comma separated: {', '.join(NOTICE_TYPES)}"),
    bbox: str | None = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    storage: Storage = Depends(get_storage),
    registry: LayerRegistry = Depends(get_registry),
):
    return _notices(storage, registry, split_csv(layer), split_csv(type), bbox, limit, offset)


@router.get("/road-closures", summary="Verified road-closure notices")
def road_closures(
    bbox: str | None = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    storage: Storage = Depends(get_storage),
    registry: LayerRegistry = Depends(get_registry),
):
    return _notices(storage, registry, [], ["road_closure"], bbox, limit, offset)
