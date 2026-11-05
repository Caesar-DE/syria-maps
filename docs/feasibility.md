# Technical Feasibility Report — OsmAnd integration

Date: 2026-11-05. Sources were read directly from the official OsmAnd repository
(`master` branch) and documentation:

- https://osmand.net/docs/user/plugins/custom/
- https://osmand.net/docs/user/map/raster-maps/
- `OsmAnd/src/net/osmand/plus/settings/backend/backup/` (`SettingsItemType.java`,
  `SettingsHelper.java`, `SettingsImporter.java`, `SettingsItemsFactory.java`,
  `items/*SettingsItem.java`)
- `OsmAnd/src/net/osmand/plus/plugins/custom/CustomOsmandPlugin.java`, `CustomRegion.java`

## 1. What an `.osf` file really is

An `.osf` file is a ZIP archive with an `items.json` entry at the root:

```json
{ "version": 1, "items": [ { "type": "...", ... } ] }
```

- `SettingsHelper.VERSION = 3`; a root `version` greater than 3 is rejected.
- Item types are a closed enum (`SettingsItemType`): `GLOBAL, PROFILE, PLUGIN, DATA, FILE,
  RESOURCES, GPX, GPX_DIR, QUICK_ACTIONS, POI_UI_FILTERS, MAP_SOURCES, AVOID_ROADS,
  SUGGESTED_DOWNLOADS, DOWNLOADS, OSM_NOTES, OSM_EDITS, FAVOURITES, ACTIVE_MARKERS,
  HISTORY_MARKERS, SEARCH_HISTORY, NAVIGATION_HISTORY, ONLINE_ROUTING_ENGINES, ITINERARY_GROUPS`.
- Unknown types are skipped silently, so a typo does not fail loudly — this is why the
  project ships its own structural validator (`python -m app.cli validate-osf`).
- Non-JSON payloads (icons, GPX, render styles) are separate ZIP entries referenced by the
  item's `"file"` key.

## 2. Executable code: **not supported**

A "custom plugin" in OsmAnd is a **data bundle**, not code. `CustomOsmandPlugin` only
registers: name/description/icon, resource folder, rendering styles, routing configs,
tile sources, POI filters, downloadable/suggested maps. There is no mechanism to run
our own logic inside OsmAnd. Anything dynamic (live notices, moderation, synchronization)
therefore happens **server-side**, and reaches OsmAnd through supported data channels.

## 3. Requested features vs. official mechanisms

| Requirement | Mechanism used | Status |
|---|---|---|
| Plugin entry (name/icon, Arabic + English) | `PLUGIN` + `RESOURCES` items | Implemented |
| Custom Syria map source | `MAP_SOURCES` (online raster template `{0}/{1}/{2}` = z/x/y) pointing to our tile server | Implemented |
| Raster overlays | Same tile source, chosen by the user as *overlay* in the "Online maps" plugin | Implemented (manual selection in OsmAnd) |
| Custom POI dataset offline | `FILE` item, `subtype: "gpx"` — waypoints of the public-services layer | Implemented (snapshot at build time) |
| Updates without reinstalling OsmAnd | Tiles are rendered from the live DB; a new `.osf` is published per dataset version; `/api/v1/datasets/*/version` for checks | Implemented (tiles live; offline GPX needs re-import) |
| Downloadable offline map data | `DOWNLOADS` item (`downloadurl` per file) | **Planned** — needs real OBF/sqlitedb files; `DownloadActivityType` filtering not yet verified |
| Official Syria OBF base map | OsmAnd's own download manager (no need to redistribute) | Use OsmAnd |
| Custom POI *filters* | `POI_UI_FILTERS` (`acceptedTypes` uses OsmAnd internal category keys) | **Planned** — key names not verified |
| 3D buildings | OsmAnd renders OSM `building`/`height`/`building:levels` from its own OBF maps. `.osf` cannot inject 3D geometry | Improve data **upstream in OSM**; no `.osf` feature |
| Traffic | No OsmAnd live-traffic import format. Server data model + API + raster overlay | Data model/API implemented, **no data provider** |
| Safety notices | Server-side, verified-only API + overlay; never shipped as "safe routes" | API implemented with verification rules, **no authorized source yet** |
| Executable companion logic | Separate Android app using the OsmAnd AIDL API (`OsmAnd-api`), see android-companion.md | Implemented (point layers); device test pending |

## 4. Important limitations

1. **Online tile overlays are not offline.** A `MAP_SOURCES` entry with a URL works only with
   connectivity (OsmAnd caches viewed tiles). Offline raster would need a pre-rendered
   `.sqlitedb` (`FILE` subtype `tiles_map`) — planned for Phase 2.
2. **The GPX snapshot** inside the `.osf` is static. A new dataset version requires downloading
   and importing a new `.osf` (or the GPX from `/api/v1/datasets/public-services/download?format=gpx`).
3. **Real-device import is unverified.** The package is validated structurally against the
   rules read from the OsmAnd source. Importing on a real Android device has **not** been
   tested by the authors of this commit. See `docs/osmand-integration.md` → "Verification checklist".
4. `PLUGIN` items in the official docs also use a `NAVIGATION_ICONS` type which does not exist
   in the current enum; we do not use it.

## 5. Decision

Phase 1 delivers: a FastAPI + PostGIS backend, a transparent PNG tile endpoint per layer,
a generated `.osf` (PLUGIN + RESOURCES + MAP_SOURCES + FILE/gpx), a validator, and the
alternative "magic link" `https://osmand.net/add-tile-source?...` documented by OsmAnd for
adding the same tile source without an `.osf`.
