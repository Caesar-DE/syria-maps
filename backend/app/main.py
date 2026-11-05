from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware

from app import __version__
from app.cache import TTLCache
from app.config import Settings, get_settings
from app.layers import LayerRegistry, load_registry
from app.routes import datasets, features, layers, meta, notices, osmand, tiles
from app.storage import create_storage
from app.storage.base import Storage

DESCRIPTION = """
Open-source data service for the **Syria Maps** OsmAnd extension.

* Layer metadata, GeoJSON features (bbox / nearest / text search, paginated)
* Transparent PNG overlay tiles usable as an OsmAnd online map source
* Versioned dataset downloads (GeoJSON, GPX) and the generated `.osf` package
* Verified-only traffic and safety notices

Development fixtures are **synthetic** and flagged with `synthetic: true`.
"""


def create_app(
    settings: Settings | None = None,
    storage: Storage | None = None,
    registry: LayerRegistry | None = None,
) -> FastAPI:
    settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.storage = storage or create_storage(settings)
        try:
            yield
        finally:
            app.state.storage.close()

    app = FastAPI(
        title="Syria Maps API",
        version=__version__,
        description=DESCRIPTION,
        license_info={"name": "MIT (code); data licenses per layer", "url": "https://opensource.org/license/mit"},
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.registry = registry or load_registry(str(settings.layers_dir))
    app.state.tile_cache = TTLCache(settings.tile_cache_entries, settings.cache_ttl_seconds)
    app.state.export_cache = TTLCache(64, settings.cache_ttl_seconds)

    app.add_middleware(GZipMiddleware, minimum_size=1024)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[o.strip() for o in settings.cors_origins.split(",")],
        allow_methods=["GET", "HEAD"],
        allow_headers=["*"],
    )
    for module in (meta, layers, features, notices, datasets, tiles, osmand):
        app.include_router(module.router)
    logging.getLogger("syria_maps").info("storage=%s layers=%d", settings.storage, len(app.state.registry.all()))
    return app
