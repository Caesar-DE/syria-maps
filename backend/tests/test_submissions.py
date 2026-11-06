from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.submissions import RateLimiter, clean_text

TOKEN = "test-admin-token-0123456789"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
DAMASCUS = {"lat": 33.5138, "lon": 36.2765}


@pytest.fixture
def client(storage, registry):
    settings = Settings(storage="memory", public_base_url="https://maps.example.org", admin_token=TOKEN,
                        submissions_per_hour=100)
    with TestClient(create_app(settings=settings, storage=storage, registry=registry)) as c:
        yield c


def new_place(**kw):
    body = {"type": "new_place", **DAMASCUS, "kind": "school", "name_ar": "مدرسة الأمل", "license_accepted": True}
    body.update(kw)
    return body


def test_submit_and_status_hides_content(client):
    r = client.post("/api/v1/submissions", json=new_place())
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["status"] == "pending" and body["license"] == "CC0-1.0"
    status = client.get(f"/api/v1/submissions/{body['id']}").json()
    assert status["status"] == "pending"
    assert "name_ar" not in status  # content stays private until accepted
    assert client.get("/api/v1/submissions/nope").status_code == 404


@pytest.mark.parametrize("body, fragment", [
    (new_place(license_accepted=False), "CC0"),
    (new_place(lat=48.1, lon=11.5), "inside Syria"),
    (new_place(kind=None, name_ar=None), "name or a kind"),
    ({"type": "wrong_name", **DAMASCUS, "current_name": "x", "license_accepted": True}, "correct name"),
    ({"type": "other", **DAMASCUS, "license_accepted": True}, "describe"),
    (new_place(type="spam"), "type"),
    (new_place(name_ar="x" * 201), "200"),
])
def test_invalid_submissions(client, body, fragment):
    r = client.post("/api/v1/submissions", json=body)
    assert r.status_code == 422
    assert fragment in r.text


def test_unknown_kind_rejected(client):
    r = client.post("/api/v1/submissions", json=new_place(kind="casino"))
    assert r.status_code == 422 and "unknown kind" in r.text


def test_honeypot_stores_nothing(client):
    r = client.post("/api/v1/submissions", json=new_place(website="http://spam"))
    assert r.status_code == 201
    assert client.get("/api/v1/moderation/submissions", headers=AUTH).json()["total"] == 0


def test_moderation_requires_token(client, storage, registry):
    assert client.get("/api/v1/moderation/submissions").status_code == 401
    assert client.get("/api/v1/moderation/submissions", headers={"Authorization": "Bearer wrong"}).status_code == 401
    settings = Settings(storage="memory", admin_token="")
    with TestClient(create_app(settings=settings, storage=storage, registry=registry)) as c:
        r = c.get("/api/v1/moderation/submissions", headers=AUTH)
        assert r.status_code == 503 and "SYRIA_MAPS_ADMIN_TOKEN" in r.text


def test_accept_new_place_publishes_feature_and_version(client):
    sid = client.post("/api/v1/submissions", json=new_place()).json()["id"]
    pending = client.get("/api/v1/moderation/submissions", headers=AUTH).json()
    assert pending["total"] == 1 and pending["items"][0]["name_ar"] == "مدرسة الأمل"
    assert client.get("/api/v1/layers/community-places/features").json()["features"] == []

    r = client.post(f"/api/v1/moderation/submissions/{sid}/accept", headers=AUTH,
                    json={"name_en": "Al-Amal School", "note": "checked"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "accepted" and r.json()["feature_external_id"] == f"sub-{sid}"

    feats = client.get("/api/v1/layers/community-places/features").json()["features"]
    assert len(feats) == 1
    props = feats[0]["properties"]
    assert props["name_ar"] == "مدرسة الأمل" and props["name_en"] == "Al-Amal School" and props["kind"] == "school"
    layer = client.get("/api/v1/layers/community-places").json()
    assert layer["latest_version"] == 1 and layer["feature_count"] == 1
    assert client.get(f"/api/v1/submissions/{sid}").json()["status"] == "accepted"
    # Reviewed once only.
    assert client.post(f"/api/v1/moderation/submissions/{sid}/reject", headers=AUTH, json={}).status_code == 409


def test_accepting_a_correction_publishes_nothing(client):
    sid = client.post("/api/v1/submissions", json={
        "type": "wrong_name", **DAMASCUS, "current_name": "شارع بغداد", "name_ar": "شارع بغداد الجديد",
        "license_accepted": True}).json()["id"]
    r = client.post(f"/api/v1/moderation/submissions/{sid}/accept", headers=AUTH, json={})
    assert r.json()["status"] == "accepted" and r.json()["feature_external_id"] is None
    assert client.get("/api/v1/layers/community-places/features").json()["features"] == []


def test_reject_and_filters(client):
    a = client.post("/api/v1/submissions", json=new_place()).json()["id"]
    client.post("/api/v1/submissions", json=new_place(name_ar="مكان آخر"))
    r = client.post(f"/api/v1/moderation/submissions/{a}/reject", headers=AUTH, json={"note": "duplicate"})
    assert r.json()["status"] == "rejected" and r.json()["review_note"] == "duplicate"
    assert client.get("/api/v1/moderation/submissions?status=pending", headers=AUTH).json()["total"] == 1
    assert client.get("/api/v1/moderation/submissions?status=rejected", headers=AUTH).json()["total"] == 1


def test_rate_limit(storage, registry):
    settings = Settings(storage="memory", admin_token=TOKEN, submissions_per_hour=2)
    with TestClient(create_app(settings=settings, storage=storage, registry=registry)) as c:
        assert c.post("/api/v1/submissions", json=new_place()).status_code == 201
        assert c.post("/api/v1/submissions", json=new_place()).status_code == 201
        assert c.post("/api/v1/submissions", json=new_place()).status_code == 429


def test_rate_limiter_window():
    rl = RateLimiter(1, window=0.0)
    assert rl.allow("a") and rl.allow("a")  # window 0: every hit expires immediately
    rl = RateLimiter(1)
    assert rl.allow("a") and not rl.allow("a") and rl.allow("b")


def test_clean_text_strips_controls():
    assert clean_text("  مدرسة\u0000‮  ", 50) == "مدرسة"
    assert clean_text("   ", 10) is None
    assert clean_text("abcdef", 3) == "abc"


def test_moderation_page_served(client):
    r = client.get("/moderation")
    assert r.status_code == 200 and "text/html" in r.headers["content-type"]
    assert r.headers["x-frame-options"] == "DENY"
    assert "innerHTML" not in r.text  # user content is inserted as text nodes only
