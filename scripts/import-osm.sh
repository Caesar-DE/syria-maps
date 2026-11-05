#!/usr/bin/env sh
# Phase 2 (prepared): extract OSM public services for Syria and import them into the
# osm-public-services layer. Review the GeoJSON before importing. Data: © OpenStreetMap contributors, ODbL.
set -eu
cd "$(dirname "$0")/../backend"
OUT="${1:-../dist/osm-public-services.geojson}"
mkdir -p "$(dirname "$OUT")"
python -m app.cli osm-extract --output "$OUT"
python -m app.cli validate-dataset "$OUT"
echo "Review $OUT, then run: docker compose run --rm migrate python -m app.cli import /path/in/container.geojson"
