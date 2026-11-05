# Architecture

```
 contributors ──► layers/*.yaml ─┐            ┌──► /api/v1/*        (JSON / GeoJSON, OpenAPI)
 OSM (Overpass) ─► GeoJSON ──────┤ app.cli    │
 authorized     ─► notices ──────┤ validate   ├──► /tiles/{layer}/{z}/{x}/{y}.png ──► OsmAnd overlay (online)
 sources            GeoJSON      ▼ import     │
                         PostgreSQL + PostGIS ├──► /api/v1/datasets/*/download  (GeoJSON, GPX)
                                              └──► /osmand/syria-maps.osf ──► OsmAnd import (plugin, tile source, GPX)
```

## Components

| Path | Responsibility |
|---|---|
| `layers/*.yaml` | Layer registry: titles (ar/en), category, status, license, attribution, kinds and colours, OsmAnd options |
| `backend/app/layers.py` | Loads and validates the registry (pydantic). Enforces e.g. "safety layers are verified-only" |
| `backend/app/datasets.py` | Import format, dataset validation, checksums |
| `backend/app/storage/` | `Storage` protocol with `PostgisStorage` (production) and `MemoryStorage` (tests, Docker-less dev) |
| `backend/app/routes/` | FastAPI routers |
| `backend/app/tiles.py` | Pillow renderer for transparent XYZ tiles |
| `backend/app/exports.py` | GeoJSON and GPX exports with attribution |
| `backend/app/osmand/` | `.osf` builder, format facts (`spec.py`) and structural validator |
| `backend/app/osm.py` | Overpass → import format (phase 2) |
| `database/migrations/` | Plain SQL migrations applied by `app.cli migrate` |

## Data model

See [data-model.md](data-model.md).

## Caching

- Tiles: in-process TTL/LRU cache keyed by `(layer, dataset checksum, z, x, y)`; `Cache-Control: max-age=3600`.
- Downloads: cached per dataset version; `ETag` + `If-None-Match` → `304`.
- Put a CDN or the reverse proxy cache in front of `/tiles` for production load.

## Update flow

1. Maintainer imports a dataset (`app.cli import`). If the checksum changed, a new row in
   `dataset_versions` is created.
2. Tiles change immediately (new cache key); OsmAnd refreshes them after the 60 min expiry.
3. Clients poll `/api/v1/datasets/{layer}/version` to detect new offline snapshots.
4. A release tag rebuilds the `.osf` (GitHub Actions) with the new GPX snapshot.
