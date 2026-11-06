"""PostGIS integration tests — run with TEST_DATABASE_URL pointing at an EMPTY database.

    TEST_DATABASE_URL=postgresql://syria_maps:syria_maps@localhost:5432/syria_maps_test pytest -m postgis
"""

from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient

from app.config import REPO_ROOT, Settings
from app.main import create_app
from tests.conftest import notice, notices_collection

DSN = os.environ.get("TEST_DATABASE_URL")
pytestmark = [pytest.mark.postgis, pytest.mark.skipif(not DSN, reason="TEST_DATABASE_URL not set")]


@pytest.fixture(scope="module")
def pg(registry, fixture_data):
    from app.storage.postgis import PostgisStorage

    db = PostgisStorage(DSN, max_size=4)
    with db.pool.connection() as conn:
        conn.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
    assert db.migrate(REPO_ROOT / "database" / "migrations") == ["001_initial.sql", "002_submissions.sql"]
    assert db.migrate(REPO_ROOT / "database" / "migrations") == []
    db.sync_layers(registry.all())
    assert db.import_collection(fixture_data).version == 1
    assert db.import_collection(fixture_data) is None  # unchanged data -> no new version
    db.import_collection(notices_collection("traffic", [
        notice("published", "verified"),
        notice("unverified", "unverified", verified=False),
        notice("expired", "verified", starts=-48, expires=-1),
    ]))
    yield db
    db.close()


@pytest.fixture(scope="module")
def pg_client(pg, registry):
    settings = Settings(storage="postgis", database_url=DSN, public_base_url="https://maps.example.org")
    with TestClient(create_app(settings=settings, storage=pg, registry=registry)) as c:
        yield c


def test_health(pg_client):
    assert pg_client.get("/health").json()["database"] == "up"


def test_spatial_queries_match_memory(pg_client, client):
    for params in ({"bbox": "36.1,33.4,36.3,33.5", "limit": 1000},
                   {"kind": "hospital,pharmacy", "limit": 1000},
                   {"q": "صيدلية", "limit": 1000},
                   {"near": "33.45,36.2", "radius_m": 3000, "limit": 1000}):
        a = pg_client.get("/api/v1/layers/public-services/features", params=params).json()
        b = client.get("/api/v1/layers/public-services/features", params=params).json()
        assert a["numberMatched"] == b["numberMatched"], params
        ext = lambda body: [f["properties"]["external_id"] for f in body["features"]]
        assert sorted(ext(a)) == sorted(ext(b)), params
        if "near" in params:
            assert ext(a) == ext(b)


def test_pagination_total_beyond_last_page(pg_client):
    body = pg_client.get("/api/v1/layers/public-services/features", params={"offset": 100}).json()
    assert body["numberReturned"] == 0 and body["numberMatched"] == 48


def test_verified_only_notices(pg_client):
    titles = [f["properties"]["title_en"] for f in pg_client.get("/api/v1/road-closures").json()["features"]]
    assert titles == ["Synthetic test notice published"]


def test_database_rejects_unsourced_verified_notice(pg):
    import psycopg

    with pytest.raises(psycopg.errors.CheckViolation), pg.pool.connection() as conn:
        conn.execute(
            """INSERT INTO notices (layer_id, external_id, notice_type, geom, title_ar, source_name,
                   verification_status, reported_at, valid_from)
               VALUES ('traffic', 'x', 'road_closure', ST_SetSRID(ST_MakePoint(36, 33), 4326), 't', ' ',
                   'verified', now(), now())"""
        )


def test_tile_and_versions(pg_client):
    assert pg_client.get("/api/v1/datasets/public-services/version").json()["version"] == 1
    r = pg_client.get("/tiles/public-services/12/2459/1643.png")
    assert r.status_code == 200 and r.headers["content-type"] == "image/png"


def test_submission_moderation_roundtrip(pg):
    from app.datasets import utcnow
    from app.submissions import COMMUNITY_META, Submission, community_feature

    sub = Submission(id="pgtest1", type="new_place", lat=33.51, lon=36.28, kind="school", name_ar="مدرسة",
                     name_en=None, current_name=None, details="d", status="pending", created_at=utcnow())
    pg.add_submission(sub)
    got = pg.get_submission("pgtest1")
    assert got.lat == pytest.approx(33.51) and got.lon == pytest.approx(36.28) and got.status == "pending"
    rows, total = pg.list_submissions("pending", 10, 0)
    assert total == 1 and rows[0].id == "pgtest1"

    done = pg.review_submission("pgtest1", "accepted", "ok", community_feature(sub, "school", "مدرسة", None), COMMUNITY_META)
    assert done.status == "accepted" and done.feature_external_id == "sub-pgtest1" and done.reviewed_at is not None
    feats = pg.iter_layer_features("community-places")
    assert [f.external_id for f in feats] == ["sub-pgtest1"] and feats[0].name_ar == "مدرسة"
    assert pg.latest_version("community-places").version == 1
    assert pg.review_submission("missing", "rejected", None, None, COMMUNITY_META) is None
