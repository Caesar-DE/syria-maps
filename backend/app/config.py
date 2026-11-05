from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

REPO_ROOT = Path(__file__).resolve().parents[2]


def _default_dir(name: str) -> Path:
    """Repo layout locally, /srv/<name> inside the Docker image."""
    local = REPO_ROOT / name
    return local if local.exists() else Path("/srv") / name


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SYRIA_MAPS_", env_file=".env", extra="ignore")

    # "postgis" for real deployments, "memory" for tests and Docker-less development.
    storage: Literal["postgis", "memory"] = "postgis"
    database_url: str = "postgresql://syria_maps:syria_maps@localhost:5432/syria_maps"
    db_pool_max: int = 10

    # Absolute public URL used in tile templates, download links and the .osf package.
    public_base_url: str = "http://localhost:8000"

    layers_dir: Path = _default_dir("layers")
    database_dir: Path = _default_dir("database")
    # GeoJSON files loaded by the in-memory storage (comma separated).
    memory_fixtures: str = ""

    cors_origins: str = "*"
    cache_ttl_seconds: int = 300
    tile_cache_entries: int = 2048
    max_page_size: int = 1000

    @property
    def base_url(self) -> str:
        return self.public_base_url.rstrip("/")


@lru_cache
def get_settings() -> Settings:
    return Settings()
