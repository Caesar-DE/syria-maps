"""Community submissions (public) and their moderation (admin token)."""

from __future__ import annotations

import hmac
from datetime import datetime
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from app.config import Settings
from app.datasets import utcnow
from app.layers import LayerRegistry
from app.routes.deps import get_registry, get_settings, get_storage
from app.storage.base import Storage
from app.submissions import (
    COMMUNITY_LAYER,
    COMMUNITY_META,
    CONTRIBUTION_LICENSE,
    RateLimiter,
    Submission,
    SubmissionIn,
    clean_text,
    community_feature,
    new_submission_id,
)

router = APIRouter(tags=["submissions"])
STATIC = Path(__file__).resolve().parents[1] / "static"


class SubmissionStatus(BaseModel):
    id: str
    status: str
    created_at: datetime
    reviewed_at: datetime | None = None


class SubmissionCreated(SubmissionStatus):
    license: str = CONTRIBUTION_LICENSE
    message: str = "Thank you. A moderator will review it before it is published."


class SubmissionOut(BaseModel):
    id: str
    type: str
    lat: float
    lon: float
    kind: str | None
    name_ar: str | None
    name_en: str | None
    current_name: str | None
    details: str | None
    status: str
    created_at: datetime
    reviewed_at: datetime | None
    review_note: str | None
    feature_external_id: str | None


class SubmissionPage(BaseModel):
    total: int
    items: list[SubmissionOut]


class AcceptIn(BaseModel):
    """Moderator may correct the values before publishing (only used for new places)."""

    kind: str | None = Field(None, max_length=40)
    name_ar: str | None = Field(None, max_length=200)
    name_en: str | None = Field(None, max_length=200)
    note: str | None = Field(None, max_length=500)


class RejectIn(BaseModel):
    note: str | None = Field(None, max_length=500)


def _limiter(request: Request) -> RateLimiter:
    app = request.app
    if not hasattr(app.state, "submission_limiter"):
        app.state.submission_limiter = RateLimiter(app.state.settings.submissions_per_hour)
    return app.state.submission_limiter


def require_admin(
    settings: Settings = Depends(get_settings),
    authorization: str | None = Header(None),
) -> None:
    if not settings.admin_token:
        raise HTTPException(503, "moderation is disabled: set SYRIA_MAPS_ADMIN_TOKEN on the server")
    token = (authorization or "").removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token.encode(), settings.admin_token.encode()):
        raise HTTPException(401, "invalid or missing admin token", headers={"WWW-Authenticate": "Bearer"})


def _out(s: Submission) -> SubmissionOut:
    return SubmissionOut(**s.__dict__)


@router.post("/api/v1/submissions", status_code=201, response_model=SubmissionCreated,
             summary="Send a new place or a correction (published only after moderation)")
def create_submission(
    body: SubmissionIn,
    request: Request,
    storage: Storage = Depends(get_storage),
    registry: LayerRegistry = Depends(get_registry),
):
    if body.website:  # honeypot: pretend success, store nothing
        return SubmissionCreated(id=new_submission_id(), status="pending", created_at=utcnow())
    layer = registry.get(COMMUNITY_LAYER)
    if layer is None:
        raise HTTPException(503, "community layer is not configured on this server")
    if body.kind and body.kind not in layer.kinds:
        raise HTTPException(422, f"unknown kind {body.kind!r}; allowed: {', '.join(layer.kinds)}")
    if not _limiter(request).allow(request.client.host if request.client else None):
        raise HTTPException(429, "too many submissions, please try again later")
    sub = Submission(
        id=new_submission_id(), type=body.type, lat=body.lat, lon=body.lon, kind=body.kind,
        name_ar=body.name_ar, name_en=body.name_en, current_name=body.current_name, details=body.details,
        status="pending", created_at=utcnow(),
    )
    storage.add_submission(sub)
    return SubmissionCreated(**sub.public_status())


@router.get("/api/v1/submissions/{submission_id}", response_model=SubmissionStatus,
            summary="Review status of one submission (content stays private until accepted)")
def submission_status(submission_id: str, storage: Storage = Depends(get_storage)):
    sub = storage.get_submission(submission_id)
    if sub is None:
        raise HTTPException(404, "unknown submission")
    return SubmissionStatus(**sub.public_status())


# -- moderation -----------------------------------------------------------------

@router.get("/api/v1/moderation/submissions", response_model=SubmissionPage, dependencies=[Depends(require_admin)],
            summary="List submissions (admin)")
def list_submissions(
    status: Literal["pending", "accepted", "rejected"] | None = Query("pending"),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    storage: Storage = Depends(get_storage),
):
    rows, total = storage.list_submissions(status, limit, offset)
    return SubmissionPage(total=total, items=[_out(s) for s in rows])


def _pending(storage: Storage, submission_id: str) -> Submission:
    sub = storage.get_submission(submission_id)
    if sub is None:
        raise HTTPException(404, "unknown submission")
    if sub.status != "pending":
        raise HTTPException(409, f"submission is already {sub.status}")
    return sub


@router.post("/api/v1/moderation/submissions/{submission_id}/accept", response_model=SubmissionOut,
             dependencies=[Depends(require_admin)], summary="Accept (new places are published in community-places)")
def accept_submission(
    submission_id: str,
    body: AcceptIn,
    storage: Storage = Depends(get_storage),
    registry: LayerRegistry = Depends(get_registry),
):
    sub = _pending(storage, submission_id)
    feature = None
    if sub.type == "new_place":
        layer = registry.get(COMMUNITY_LAYER)
        kind = body.kind or sub.kind or "other"
        if layer is None or kind not in layer.kinds:
            raise HTTPException(422, f"unknown kind {kind!r}")
        name_ar = clean_text(body.name_ar, 200) or sub.name_ar
        name_en = clean_text(body.name_en, 200) or sub.name_en
        feature = community_feature(sub, kind, name_ar, name_en)
    # Corrections of OSM data (wrong name, closed, other) are fixed by the moderator in OpenStreetMap;
    # accepting them only records that they were handled.
    done = storage.review_submission(sub.id, "accepted", clean_text(body.note, 500), feature, COMMUNITY_META)
    return _out(done)


@router.post("/api/v1/moderation/submissions/{submission_id}/reject", response_model=SubmissionOut,
             dependencies=[Depends(require_admin)], summary="Reject a submission")
def reject_submission(submission_id: str, body: RejectIn, storage: Storage = Depends(get_storage)):
    sub = _pending(storage, submission_id)
    done = storage.review_submission(sub.id, "rejected", clean_text(body.note, 500), None, COMMUNITY_META)
    return _out(done)


@router.get("/moderation", include_in_schema=False)
def moderation_page():
    """Single-page moderation tool; the admin token is entered in the page and kept in the browser only."""
    return FileResponse(STATIC / "moderation.html", media_type="text/html; charset=utf-8",
                        headers={"Cache-Control": "no-store", "X-Frame-Options": "DENY",
                                 "Referrer-Policy": "no-referrer"})
