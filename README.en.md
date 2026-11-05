# Syria Maps — community map layers for OsmAnd

An open-source, community-driven extension for the official **OsmAnd** Android app that adds
Syrian map layers (public services, verified traffic and safety notices, community layers)
**without modifying or redistributing OsmAnd**.

> Arabic (primary): [README.md](README.md)

> **All data currently in this repository is synthetic** development data. Every record carries
> `synthetic: true` and the word "Synthetic" in its name. It does not represent real facilities.

## Vision

A Syrian map built by its community: correct Arabic street and neighbourhood names, current
public-service points and sourced road notices — inside OsmAnd, which works offline.

## Status

| Feature | Status |
|---|---|
| FastAPI + PostgreSQL/PostGIS backend with Docker Compose | Implemented (Compose verified in CI only; not run on the authoring machine) |
| Layer metadata API, spatial queries, pagination, input validation | Implemented and tested |
| Public-services layer (synthetic data) | Implemented |
| Transparent PNG overlay tiles usable as an OsmAnd online map source | Implemented |
| `.osf` package (plugin + icon + tile source + offline GPX) | Implemented, structurally validated — **not yet imported on a real Android device** |
| Versioned dataset downloads (GeoJSON, GPX) | Implemented |
| Traffic and safety notices (verified-only) | Model and API ready — **no authorized data source connected** |
| OpenStreetMap import preferring Arabic names | Tooling ready, nothing imported yet |
| Offline raster packs (`.sqlitedb`) | Planned |
| 3D buildings | Planned (via OSM data; `.osf` cannot inject 3D geometry) |
| Real-time traffic | Planned — needs a real provider |
| Community submissions with moderation | Planned |

## How it works

An OsmAnd `.osf` file is a **data-only ZIP**; it cannot run code. All logic lives on the server
and reaches OsmAnd through official mechanisms:

1. An **online map source** (`MAP_SOURCES`) pointing at `https://your-host/tiles/public-services/{0}/{1}/{2}.png`.
   Tiles are rendered from the database, so updates arrive without reinstalling (tile expiry: 60 minutes).
2. A **GPX file** inside the package with the layer's points for **offline** use (a snapshot of one dataset version).
3. The base Syria map stays with OsmAnd's own download manager.

Details: [docs/feasibility.md](docs/feasibility.md), [docs/architecture.md](docs/architecture.md).

## Install and use in OsmAnd

### 1. Install

1. Install OsmAnd from Google Play.
2. Download `syria-maps.osf` from your server: `https://your-host/osmand/syria-maps.osf`
   (or from the GitHub releases page).
3. Open the file with OsmAnd → Import, and confirm.

> **Important:** the tile URL is written into the `.osf` file. A package built locally in `dist/`
> points at the placeholder `maps.example.org` and will not work. Always use the package downloaded
> from your server, or build it with your URL (`build-osf --base-url https://your-host`).

### 2. Check the import

- *Menu → Plugins*: **Syria Maps / خرائط سوريا** should be listed with a green icon.
- In the same list, enable the **Online maps** plugin — without it the layer cannot be shown.

### 3. Show the layer

1. *Configure map → Overlay map*.
2. Choose `Syria Maps - Public services (synthetic)`.
3. Adjust transparency with the slider at the bottom of the screen.
4. Go to Damascus (about 33.45 N, 36.20 E) and zoom to level 12 or more. Coloured dots appear:
   red hospital, green pharmacy, yellow school, purple fuel station, blue bus stop, grey parking.
   More clusters are near Aleppo, Homs and Latakia. **These points are synthetic, not real places.**

Tiles are rendered from the server database and OsmAnd re-fetches them every 60 minutes, so updates
arrive without re-importing.

### 4. Offline points

- *My places → Tracks* → `syria-maps-public-services` → *Show on map*. Works in airplane mode.
- This is a snapshot of the dataset at download time. To update, download a new `.osf` or the GPX from
  `https://your-host/api/v1/datasets/public-services/download?format=gpx`.

### 5. If no points appear

Open this URL in the phone's browser; it should show an image with coloured dots:

```
https://your-host/tiles/public-services/12/2459/1643.png
```

- No image: check that the server runs (`/health`) and HTTPS is set up (Caddy or nginx, see [docs/deployment.md](docs/deployment.md)).
- Image works but not in OsmAnd: check that Online maps is enabled and the overlay selected, and that
  `SYRIA_MAPS_PUBLIC_BASE_URL` in the server's `.env` is the correct HTTPS URL; then download the `.osf` again.

### Without `.osf`

The link returned by `GET /osmand/add-tile-source/public-services` adds the tile source directly when opened on the phone.

On-device checklist and verification status: [docs/osmand-integration.md](docs/osmand-integration.md).

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
