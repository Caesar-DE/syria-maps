# Syria Maps — community map layers for OsmAnd

An open-source, community-driven extension for the official **OsmAnd** Android app that adds
Syrian map layers (public services, verified traffic and safety notices, community layers)
**without modifying or redistributing OsmAnd**.

> Arabic (primary): [README.md](README.md)

>  **All data currently in this repository is synthetic** development data. Every record carries
> `synthetic: true` and the word "Synthetic" in its name. It does not represent real facilities.

## Vision

A Syrian map built by its community: correct Arabic street and neighbourhood names, current
public-service points and sourced road notices — inside OsmAnd, which works offline.

## Status

| Feature | Status |
|---|---|
| FastAPI + PostgreSQL/PostGIS backend with Docker Compose |  Implemented (Compose verified in CI only; not run on the authoring machine) |
| Layer metadata API, spatial queries, pagination, input validation |  Implemented and tested |
| Public-services layer (synthetic data) |  Implemented |
| Transparent PNG overlay tiles usable as an OsmAnd online map source |  Implemented |
| `.osf` package (plugin + icon + tile source + offline GPX) |  Implemented, structurally validated —  **not yet imported on a real Android device** |
| Versioned dataset downloads (GeoJSON, GPX) |  Implemented |
| Traffic and safety notices (verified-only) |  Model and API ready — **no authorized data source connected** |
| OpenStreetMap import preferring Arabic names |  Tooling ready, nothing imported yet |
| Offline raster packs (`.sqlitedb`) |  Planned |
| 3D buildings |  Planned (via OSM data; `.osf` cannot inject 3D geometry) |
| Real-time traffic |  Planned — needs a real provider |
| Community submissions with moderation |  Planned |

## How it works

An OsmAnd `.osf` file is a **data-only ZIP**; it cannot run code. All logic lives on the server
and reaches OsmAnd through official mechanisms:

1. An **online map source** (`MAP_SOURCES`) pointing at `https://your-host/tiles/public-services/{0}/{1}/{2}.png`.
   Tiles are rendered from the database, so updates arrive without reinstalling (tile expiry: 60 minutes).
2. A **GPX file** inside the package with the layer's points for **offline** use (a snapshot of one dataset version).
3. The base Syria map stays with OsmAnd's own download manager.

Details: [docs/feasibility.md](docs/feasibility.md), [docs/architecture.md](docs/architecture.md).

## Install (end users)

1. Install OsmAnd from Google Play.
2. Download `syria-maps.osf` from the releases page or `https://your-host/osmand/syria-maps.osf`.
3. Open it with OsmAnd → Import.
4. Enable the **Online maps** plugin, then *Configure map → Overlay map* → choose
   `Syria Maps - Public services (synthetic)`.
5. The GPX waypoints appear under *My places → Tracks* and can be shown offline.

No-`.osf` alternative: the link returned by `GET /osmand/add-tile-source/public-services`.
Full steps and the on-device verification checklist: [docs/osmand-integration.md](docs/osmand-integration.md).

## Development

```bash
cp .env.example .env
docker compose up -d --build
curl http://localhost:8000/health        # OpenAPI UI: http://localhost:8000/docs
```

Without Docker (in-memory storage): `scripts/dev.sh` or `scripts\dev.ps1`.

```bash
cd backend
pip install -r requirements-dev.txt
pytest                                    # set TEST_DATABASE_URL to also run PostGIS tests
python -m app.cli build-osf --storage memory --base-url https://maps.example.org
python -m app.cli validate-osf ../dist/syria-maps.osf
```

## API overview

| Endpoint | Purpose |
|---|---|
| `GET /health` | Liveness + database check |
| `GET /api/v1/layers[?category=&status=]` | Layer list with license, attribution, availability |
| `GET /api/v1/layers/{id}` | Layer metadata |
| `GET /api/v1/layers/{id}/features?bbox=&kind=&q=&near=lat,lon&radius_m=&limit=&offset=` | GeoJSON features |
| `GET /api/v1/public-services` | Shortcut for the public-services layer |
| `GET /api/v1/notices`, `GET /api/v1/road-closures` | Verified, unexpired notices only |
| `GET /api/v1/datasets`, `/datasets/{id}/version`, `/datasets/{id}/download?format=geojson\|gpx` | Versioned downloads with ETag |
| `GET /tiles/{id}/{z}/{x}/{y}.png` | Overlay tiles |
| `GET /osmand/syria-maps.osf` | OsmAnd package for this server |

## Contributing · Safety · License

- [CONTRIBUTING.md](CONTRIBUTING.md), [docs/adding-layers.md](docs/adding-layers.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- [docs/safety-policy.md](docs/safety-policy.md) — the absence of a warning never means "safe".
- Code: [MIT](LICENSE). Data: per layer (OSM-derived data ODbL 1.0, synthetic fixtures CC0) — [docs/licensing.md](docs/licensing.md).
- Independent project, not affiliated with OsmAnd. "OsmAnd" is a trademark of its owners.
- Roadmap: [docs/roadmap.md](docs/roadmap.md)
