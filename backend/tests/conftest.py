from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import REPO_ROOT, Settings
from app.datasets import load_json, utcnow
from app.layers import LayerRegistry
from app.main import create_app
from app.storage.memory import MemoryStorage

FIXTURE = REPO_ROOT / "database" / "fixtures" / "public-services.synthetic.geojson"
BASE_URL = "https://maps.example.org"


def notice(nid: str, status: str, *, notice_type="road_closure", layer="traffic", source="Test Authority",
           verified=True, starts=-1, expires: int | None = 24, coords=(36.21, 33.46)) -> dict:
    now = utcnow()
    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": list(coords)},
        "properties": {
            "id": nid,
            "notice_type": notice_type,
            "title_ar": f"إشعار اختبار {nid}",
            "title_en": f"Synthetic test notice {nid}",
            "source_name": source,
            "verification_status": status,
            "verified_at": (now - timedelta(hours=1)).isoformat() if verified else None,
            "reported_at": (now - timedelta(hours=2)).isoformat(),
            "valid_from": (now + timedelta(hours=starts)).isoformat(),
            "expires_at": (now + timedelta(hours=expires)).isoformat() if expires is not None else None,
        },
    }


def notices_collection(layer: str, features: list[dict]) -> dict:
    return {
        "type": "FeatureCollection",
        "metadata": {"layer_id": layer, "kind": "notices", "source": "synthetic tests", "license": "CC0-1.0",
                     "attribution": "tests", "synthetic": False},
        "features": features,
    }


@pytest.fixture(scope="session")
def registry() -> LayerRegistry:
    return LayerRegistry.from_dir(REPO_ROOT / "layers")


@pytest.fixture
def storage() -> MemoryStorage:
    s = MemoryStorage.from_files([FIXTURE])
    s.load_collection(notices_collection("traffic", [
        notice("published", "verified"),
        notice("unverified", "unverified", verified=False),
        notice("rejected", "rejected"),
        notice("expired", "verified", starts=-48, expires=-1),
        notice("future", "verified", starts=5, expires=48),
        notice("construction", "verified", notice_type="construction"),
        notice("far-away", "verified", coords=(41.0, 36.9)),
    ]))
    s.load_collection(notices_collection("safety-notices", [
        notice("safety-ok", "verified", notice_type="safety_notice", layer="safety-notices"),
        notice("safety-unverified", "unverified", notice_type="road_hazard", verified=False),
    ]))
    return s


@pytest.fixture
def settings() -> Settings:
    return Settings(storage="memory", public_base_url=BASE_URL)


@pytest.fixture
def client(settings, storage, registry):
    with TestClient(create_app(settings=settings, storage=storage, registry=registry)) as c:
        yield c


@pytest.fixture(scope="session")
def fixture_data() -> dict:
    return load_json(FIXTURE)


def fixture_path() -> Path:
    return FIXTURE
