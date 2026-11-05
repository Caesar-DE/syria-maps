from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app import __version__
from app.config import Settings
from app.routes.deps import get_settings, get_storage
from app.schemas import Health
from app.storage.base import Storage

router = APIRouter(tags=["meta"])


@router.get("/health", response_model=Health, summary="Liveness and database check")
def health(storage: Storage = Depends(get_storage), settings: Settings = Depends(get_settings)):
    up = storage.ping()
    body = Health(status="ok" if up else "degraded", storage=settings.storage,
                  database="up" if up else "down", version=__version__)
    return JSONResponse(body.model_dump(), status_code=200 if up else 503)


@router.get("/", include_in_schema=False)
def root():
    return {"name": "Syria Maps API", "docs": "/docs", "openapi": "/openapi.json", "health": "/health"}
