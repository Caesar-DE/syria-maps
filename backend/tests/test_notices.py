from __future__ import annotations


def _ids(body) -> set[str]:
    return {f["properties"]["title_en"].rsplit(" ", 1)[-1] for f in body["features"]}


def test_road_closures_only_verified_current(client):
    body = client.get("/api/v1/road-closures").json()
    assert _ids(body) == {"published", "far-away"}
    for f in body["features"]:
        p = f["properties"]
        assert p["verification_status"] == "verified"
        assert p["source_name"] and p["verified_at"] and p["expires_at"] and p["reported_at"]
    assert "does NOT mean" in body["disclaimer"]["en"]
    assert body["disclaimer"]["ar"]


def test_notices_never_return_unverified(client):
    body = client.get("/api/v1/notices", params={"limit": 1000}).json()
    assert {f["properties"]["verification_status"] for f in body["features"]} == {"verified"}
    assert not _ids(body) & {"unverified", "rejected", "expired", "future", "safety-unverified"}
    assert _ids(body) == {"published", "far-away", "construction", "safety-ok"}


def test_notice_filters(client):
    body = client.get("/api/v1/notices", params={"layer": "safety-notices"}).json()
    assert _ids(body) == {"safety-ok"}
    body = client.get("/api/v1/notices", params={"type": "construction"}).json()
    assert _ids(body) == {"construction"}
    body = client.get("/api/v1/road-closures", params={"bbox": "36.1,33.4,36.3,33.5"}).json()
    assert _ids(body) == {"published"}


def test_notice_input_validation(client):
    assert client.get("/api/v1/notices", params={"type": "landmine_prediction"}).status_code == 422
    assert client.get("/api/v1/notices", params={"layer": "public-services"}).status_code == 422
    assert client.get("/api/v1/road-closures", params={"bbox": "x"}).status_code == 422
