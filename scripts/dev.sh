#!/usr/bin/env sh
# Docker-less development server (in-memory storage, synthetic fixture).
set -eu
cd "$(dirname "$0")/../backend"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q -r requirements-dev.txt
export SYRIA_MAPS_STORAGE=memory
export SYRIA_MAPS_PUBLIC_BASE_URL="${SYRIA_MAPS_PUBLIC_BASE_URL:-http://localhost:8000}"
exec .venv/bin/uvicorn app.main:create_app --factory --reload --port 8000
