"""Community submissions: places and corrections sent in from the app, published only after moderation.

Privacy: a submission stores only what the sender typed and the location they chose. No name, account,
device id or IP address is stored. The in-memory rate limiter keeps a salted hash of the client address
for at most one hour and never writes it anywhere.
"""

from __future__ import annotations

import hashlib
import secrets
import threading
import time
import unicodedata
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator

from app.geo import in_syria_envelope

COMMUNITY_LAYER = "community-places"
SUBMISSION_TYPES = ("new_place", "wrong_name", "closed", "other")
STATUSES = ("pending", "accepted", "rejected")
CONTRIBUTION_LICENSE = "CC0-1.0"

SubmissionType = Literal["new_place", "wrong_name", "closed", "other"]


def clean_text(value: str | None, max_len: int) -> str | None:
    """Trim, drop control characters, cap length; empty -> None."""
    if value is None:
        return None
    text = "".join(ch for ch in value if ch in "\n\t" or unicodedata.category(ch)[0] != "C").strip()
    return text[:max_len] or None


class SubmissionIn(BaseModel):
    type: SubmissionType
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    kind: str | None = Field(None, max_length=40)
    name_ar: str | None = Field(None, max_length=200)
    name_en: str | None = Field(None, max_length=200)
    current_name: str | None = Field(None, max_length=200)
    details: str | None = Field(None, max_length=1000)
    #: The sender must agree that the contribution may be published under CC0.
    license_accepted: bool
    #: Honeypot for simple bots; real clients leave it empty.
    website: str | None = None

    @field_validator("name_ar", "name_en", "current_name")
    @classmethod
    def _short(cls, v: str | None) -> str | None:
        return clean_text(v, 200)

    @field_validator("details")
    @classmethod
    def _long(cls, v: str | None) -> str | None:
        return clean_text(v, 1000)

    @model_validator(mode="after")
    def _rules(self) -> "SubmissionIn":
        if not self.license_accepted:
            raise ValueError(f"the contribution must be released under {CONTRIBUTION_LICENSE} (license_accepted)")
        if not in_syria_envelope(self.lon, self.lat):
            raise ValueError("location must be inside Syria")
        if self.type == "new_place" and not (self.name_ar or self.name_en or self.kind):
            raise ValueError("a new place needs a name or a kind")
        if self.type == "wrong_name" and not (self.name_ar or self.name_en):
            raise ValueError("a name correction needs the correct name")
        if self.type in ("closed", "other") and not (self.current_name or self.details):
            raise ValueError("describe the place or the problem")
        return self


@dataclass
class Submission:
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
    reviewed_at: datetime | None = None
    review_note: str | None = None
    #: external_id of the feature created in the community layer when accepted.
    feature_external_id: str | None = None

    def public_status(self) -> dict[str, Any]:
        return {"id": self.id, "status": self.status, "created_at": self.created_at,
                "reviewed_at": self.reviewed_at}


def new_submission_id() -> str:
    return secrets.token_urlsafe(9)


def feature_external_id(submission_id: str) -> str:
    return f"sub-{submission_id}"


def community_feature(sub: Submission, kind: str | None, name_ar: str | None, name_en: str | None) -> dict[str, Any]:
    """GeoJSON feature (import format) for an accepted new place."""
    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": [round(sub.lon, 7), round(sub.lat, 7)]},
        "properties": {
            "id": feature_external_id(sub.id),
            "kind": kind or "other",
            "name_ar": name_ar,
            "name_en": name_en,
            "submission_id": sub.id,
        },
    }


COMMUNITY_META = {
    "layer_id": COMMUNITY_LAYER,
    "source": "Syria Maps community submissions (moderated)",
    "license": CONTRIBUTION_LICENSE,
    "attribution": "Syria Maps contributors",
    "synthetic": False,
}


class RateLimiter:
    """At most ``limit`` submissions per client per ``window`` seconds. Memory only, salted hashes."""

    def __init__(self, limit: int, window: float = 3600.0) -> None:
        self.limit = limit
        self.window = window
        self._salt = secrets.token_bytes(16)
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, client: str | None) -> bool:
        if self.limit <= 0:
            return True
        key = hashlib.sha256(self._salt + (client or "?").encode()).hexdigest()
        now = time.monotonic()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] >= self.window:
                hits.popleft()
            if len(hits) >= self.limit:
                return False
            hits.append(now)
            # Forget idle clients so the table cannot grow without bound.
            if len(self._hits) > 10_000:
                for k in [k for k, v in self._hits.items() if not v or now - v[-1] > self.window]:
                    del self._hits[k]
            return True
