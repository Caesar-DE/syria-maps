from __future__ import annotations

from pathlib import Path

from app.config import Settings
from app.storage.base import Storage


def create_storage(settings: Settings) -> Storage:
    if settings.storage == "memory":
        from app.storage.memory import MemoryStorage

        paths = [Path(p.strip()) for p in settings.memory_fixtures.split(",") if p.strip()]
        if not paths:
            paths = sorted((settings.database_dir / "fixtures").glob("*.geojson"))
        return MemoryStorage.from_files(paths)

    from app.storage.postgis import PostgisStorage

    return PostgisStorage(settings.database_url, max_size=settings.db_pool_max)
