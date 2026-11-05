#!/usr/bin/env sh
# Build and validate dist/syria-maps.osf for a public API URL.
#   scripts/build-osf.sh https://maps.example.org
set -eu
BASE_URL="${1:?usage: build-osf.sh https://your-api-host}"
cd "$(dirname "$0")/../backend"
python -m app.cli build-osf --storage memory --base-url "$BASE_URL" --output ../dist/syria-maps.osf
