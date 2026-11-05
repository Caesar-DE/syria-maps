from __future__ import annotations


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
    assert r.json()["database"] == "up"


def test_health_reports_database_down(settings, registry):
    from fastapi.testclient import TestClient

    from app.main import create_app
    from app.storage.memory import MemoryStorage

    class Down(MemoryStorage):
        def ping(self) -> bool:
            return False

    with TestClient(create_app(settings=settings, storage=Down(), registry=registry)) as c:
        r = c.get("/health")
    assert r.status_code == 503
    assert r.json()["database"] == "down"


def test_openapi_documents_endpoints(client):
    paths = client.get("/openapi.json").json()["paths"]
    for p in ("/api/v1/layers", "/api/v1/public-services", "/api/v1/road-closures",
              "/api/v1/datasets/{layer_id}/version", "/tiles/{layer_id}/{z}/{x}/{y}.png", "/osmand/syria-maps.osf"):
        assert p in paths


def test_cors_and_gzip(client):
    r = client.get("/api/v1/layers", headers={"Origin": "https://x.example", "Accept-Encoding": "gzip"})
    assert r.headers["access-control-allow-origin"] == "*"
    assert r.headers.get("content-encoding") == "gzip"
