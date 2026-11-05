# Roadmap

## Phase 1 — MVP (this release)
- [x] Docker Compose: PostGIS + API + migrations + fixture import + health checks + volumes
- [x] Layer registry and metadata API
- [x] Synthetic public-services dataset
- [x] Overlay tile endpoint + OsmAnd `MAP_SOURCES`
- [x] `.osf` package builder and structural validator
- [x] GeoJSON/GPX downloads with versions and ETags
- [x] Verified-only notices API (no data source)
- [x] Tests, CI, Arabic/English documentation
- [ ] **Real-device import test** (see docs/osmand-integration.md)

## Phase 2 — Community data
- [x] Overpass extraction tool preferring `name:ar` (`app.cli osm-extract`)
- [ ] First reviewed OSM import into `osm-public-services`; replace synthetic layer in the package
- [ ] Arabic name quality reports (missing `name:ar`, transliteration mismatches) to guide OSM edits
- [ ] Offline raster packs (`.sqlitedb`, `FILE` subtype `tiles_map`) — verify OsmAnd's sqlitedb schema first
- [ ] `DOWNLOADS` item for updatable offline files — verify `DownloadActivityType` values first
- [ ] Contributor review workflow (issue templates, data-license checklist bot)

## Phase 3 — Advanced
- [ ] Traffic provider integration (needs a real, licensed provider)
- [ ] Verified safety notices with an authorized partner organisation
- [ ] Moderated community submissions (accounts, audit log, two-person verification)
- [ ] Enhanced offline sync (delta GPX / companion app via OsmAnd AIDL API)
- [ ] 3D: campaigns to add `height`/`building:levels` in OSM for Syrian cities
