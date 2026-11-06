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
| OpenStreetMap import preferring Arabic names | Implemented and tried (about 11,400 points); server import is manual |
| Offline raster packs (`.sqlitedb`) | Planned |
| 3D buildings | Planned (via OSM data; `.osf` cannot inject 3D geometry) |
| Real-time traffic | Planned — needs a real provider |
| Community submissions with moderation | Planned |
| Android companion app (Kotlin) adding point layers to OsmAnd with one switch | Implemented; tested on a real phone with OsmAnd 5.4.11 (points appear on the map). `.osf` hand-over not yet tested |

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
- Large, pixelated colour blobs when zooming in: OsmAnd cannot load tiles for the current zoom and stretches
  older tiles from a lower zoom. Usually `SYRIA_MAPS_PUBLIC_BASE_URL` is wrong, e.g. `http://0.0.0.0:8000`
  (a listen address no phone can reach; the server now refuses to build a package with it). Fix it, download
  and import the `.osf` again, then close and reopen OsmAnd.

The package uses 512 px tiles (`.../{z}/{x}/{y}@2x.png`, `tileSize: 512`) so markers stay sharp on dense
phone screens; the 256 px tiles (`.../{z}/{x}/{y}.png`) remain available.

### Without `.osf`

The link returned by `GET /osmand/add-tile-source/public-services` adds the tile source directly when opened on the phone.

On-device checklist and verification status: [docs/osmand-integration.md](docs/osmand-integration.md).

## Data sources: where do the places come from?

The main source of real data is **OpenStreetMap (OSM)**, an open map database edited by volunteers, licensed
under ODbL (reuse allowed with the attribution "© OpenStreetMap contributors"). The project includes a tool
that extracts public-service points inside Syria's national boundary only, preferring Arabic names (`name:ar`).

In a test run (November 2026) it extracted about **11,400 points**: schools, pharmacies, hospitals, clinics,
fuel stations, parking, police and fire stations, post offices and universities. About half have an Arabic name.

### Step 1: extract (on your computer)

Run it on your computer rather than inside Docker on the server; some servers block container internet access.

```powershell
cd "C:\OsmAnd Plugin\syria-maps\backend"
.\.venv\Scripts\python.exe -m app.cli osm-extract --output ..\dist\osm-public-services.geojson
.\.venv\Scripts\python.exe -m app.cli validate-dataset ..\dist\osm-public-services.geojson
```

It takes about a minute. The check must report `0 errors`; `feature has no name` warnings are normal
(places in OSM without a name).

### Step 2: copy the file to the server

```powershell
scp "C:\OsmAnd Plugin\syria-maps\dist\osm-public-services.geojson" root@your-host:/root/
```

### Step 3: import into the database (on the server)

```bash
cd /srv/syria-maps
docker compose run --rm -v /root/osm-public-services.geojson:/data.geojson:ro migrate python -m app.cli import /data.geojson
```

Expected output: `imported osm-public-services.geojson: new version 1`.

### Step 4: in OsmAnd

Download and import the `.osf` from your server again, then choose the overlay
`Syria Maps - Public services from OpenStreetMap`.

### Updating

Repeat steps 1–3, e.g. monthly. The import updates changed places, deletes places removed from OSM, and only
creates a new version when the data actually changed. Tiles in OsmAnd refresh by themselves; no new `.osf` is needed.

### Other sources

Any other dataset must have a license that allows redistribution. **Never copy data from Google Maps or
commercial sources.** How to add a layer: [docs/adding-layers.md](docs/adding-layers.md).

## How can people add places?

### Option 1 (recommended): add them in OpenStreetMap

Every place added in OSM reaches our layer with the next import, and reaches OsmAnd's official maps for all
users with the next map update.

1. Create a free account at https://www.openstreetmap.org
2. Add the place:
   - **In OsmAnd:** enable the **OpenStreetMap Editing** plugin, long-press the map at the location →
     *Create POI* → choose the type (e.g. pharmacy) and enter the name → upload with your OSM account.
   - **In the browser:** open openstreetmap.org and press *Edit* (iD editor).
   - **Phone apps:** StreetComplete, Every Door or Vespucci.
3. Put the Arabic name in `name:ar` where possible and the English name in `name:en`.

Types our layer reads:

| Type | OSM tag |
|---|---|
| Hospital | `amenity=hospital` |
| Clinic | `amenity=clinic` |
| Pharmacy | `amenity=pharmacy` |
| School | `amenity=school` |
| University | `amenity=university` |
| Fuel station | `amenity=fuel` |
| Bus stop | `highway=bus_stop` |
| Bus station | `amenity=bus_station` |
| Parking | `amenity=parking` |
| Police | `amenity=police` |
| Fire station | `amenity=fire_station` |
| Post office | `amenity=post_office` |

**OSM rules:** only add real places you know on the ground; never copy from Google Maps or other protected maps.

### Option 2: a new community layer via GitHub

For data that does not fit OSM, or a separate layer (e.g. drinking-water points): a YAML file and a GeoJSON
file submitted as a pull request. Full guide: [docs/adding-layers.md](docs/adding-layers.md).

### Not available yet

Adding places directly to our server from the app or website, with moderator review, is **not implemented yet**
(planned for phase 3, see [docs/roadmap.md](docs/roadmap.md)).

## Android companion app (the easy way)

Instead of importing the package and picking layers by hand, there is a simple Android app in `android/`
(Arabic, German and English):

1. Install OsmAnd, then the "Syria Maps" app (`app-release.apk`).
2. On first start a short setup asks for language, theme (light or dark) and your server URL, and checks
   OsmAnd. The gear button opens it again later.
3. Turn on the switch next to a layer; the points are sent straight to the OsmAnd map.
4. Tap "Open in OsmAnd".

Notes:
- If OsmAnd refuses, an "allow" button appears. OsmAnd requires allowing connected apps once. In OsmAnd 5.4
  there is no permission screen yet: open OsmAnd, *Menu → Plugins*, and switch on "Syria Maps" in the list.
- OsmAnd keeps these points in memory only; they disappear when OsmAnd is fully closed. Opening
  "Syria Maps" again re-sends them automatically.
- Raster overlays cannot be switched on from the app. "Install map package" opens OsmAnd's import screen for you to confirm.

Build, signing, limitations and verification status: [docs/android-companion.md](docs/android-companion.md).
The app is GPL-3.0 because it uses OsmAnd's official GPL-3.0 AIDL interface.

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
- Code: [MIT](LICENSE); the Android app in `android/` is GPL-3.0 (links OsmAnd's AIDL API). Data: per layer (OSM-derived data ODbL 1.0, synthetic fixtures CC0) — [docs/licensing.md](docs/licensing.md).
- Independent project, not affiliated with OsmAnd. "OsmAnd" is a trademark of its owners.
- Roadmap: [docs/roadmap.md](docs/roadmap.md)
