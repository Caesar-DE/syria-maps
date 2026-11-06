# Android companion app

Source: [`android/`](../android). Kotlin + Jetpack Compose, package `io.github.caesarde.syriamaps`,
minSdk 26, targetSdk 36. Interface in Arabic (right-to-left, default), German and English.

## First start and settings

On first start a short setup wizard runs (5 steps, every step skippable):

1. **Language**: Arabic, German or English. The device language is preselected when it is one of the three.
   The switch is live (no restart); Arabic is laid out right-to-left, German and English left-to-right.
   Layer titles and category names come from the server in Arabic and English only, so the German
   interface shows them in English.
2. **Theme**: light (yellow), dark (black) or follow the phone. Colours morph live.
3. **Server**: address field with a "test connection" button that lists the server's layers without saving.
4. **OsmAnd**: shows whether OsmAnd is installed and connected, and how to enable the app in OsmAnd once.
5. **Done**.

The gear button in the header reopens the wizard later. Choices are stored in the app's
SharedPreferences (`language`, `theme`, `setup_done`).

The app replaces the manual steps (import `.osf`, enable plugins, pick overlays) for point layers:
the user turns a switch on, and the points appear in the official OsmAnd app.

## Reporting map problems (OSM notes)

Users report a wrong name, a missing place, a closed place or another problem. The app creates an anonymous,
public note with the documented OSM API 0.6 call `POST /api/0.6/notes` (`lat`, `lon`, `text`). The text is
bilingual (English labels + Arabic) and ends with `#SyriaMaps`. Debug builds send to the OSM development server
(`master.apis.dev.openstreetmap.org`, shown with a yellow "test build" tag); release builds to `api.openstreetmap.org`.

Where the location comes from:

| Source | How |
|---|---|
| OsmAnd "Share" | `ACTION_SEND text/plain`; OsmAnd's ShareMenu text contains `geo:lat,lon` and `https://osmand.net/map?pin=lat,lon`. Also `geo:` VIEW links, osm.org and Google Maps links |
| Our points in OsmAnd | Optional context-menu button (`addContextMenuButtons`, callback `onContextMenuButtonClicked`). OsmAnd shows it for points whose layer id matches (`AMapPointMenuController`). Android blocks starting an activity from the background (`BAL_BLOCK` on Android 17), so the callback posts a notification; tapping it opens the prepared report. Off by default; turning it on asks for `POST_NOTIFICATIONS` |
| Phone location | One fix via `LocationManager.getCurrentLocation`, only after the user taps the button; no tracking |

Privacy: the screen states that notes are public and requires confirming that before sending. Nothing about
the user is stored or sent besides the note itself.

## Sending to the Syria Maps team (moderated)

The report screen can send to the Syria Maps server instead (`POST /api/v1/submissions`, see
[deployment.md](deployment.md) section 5). The body carries only type, location, kind, names and details; the
sender must agree to CC0. Arabic input goes to `name_ar`, Latin to `name_en`. HTTP 429 (rate limit) is shown as
its own message. Accepted new places appear in the `community-places` layer, which the app lists like any other
point layer; "Sync now" reloads the layer list from the server.

## Direct OpenStreetMap editing (OAuth2)

Logged-in users can add a place (node with `amenity=*`/`shop=*`, `name`, `name:ar` or `name:en`) or rename a nearby
named node or way. Closed places and other problems stay notes, so someone checks on the ground before deleting.

- Login: OAuth 2.0 authorization code + PKCE (public client, no secret in the app). The password is entered only
  on openstreetmap.org in the browser. Token and display name are kept in the app's private storage.
- Rename: `GET /api/0.6/map` (about 80 m around the location) lists named objects nearest first; the user picks
  one. Before upload the element is re-read; if its version changed, the app stops with a conflict message.
- Upload: one changeset per edit (`created_by`, `comment`, `hashtags=#SyriaMaps`, `source=local knowledge`),
  then the node create / element update, then the changeset is closed. A preview shows the exact tags first.
- Debug builds talk to `master.apis.dev.openstreetmap.org`, release builds to `api.openstreetmap.org`.

### Registering the OAuth app (once per server)

1. Log in on https://www.openstreetmap.org (for testing: https://master.apis.dev.openstreetmap.org with a
   separate account) → My Settings → OAuth 2 applications → Register new application.
2. Name `Syria Maps`, redirect URI `io.github.caesarde.syriamaps://oauth2`, permissions **Read user
   preferences** and **Modify the map**, **Confidential application unticked**.
3. Build with the client id: `./gradlew assembleRelease -PosmClientId=<id>` (debug: `-PosmDevClientId=<id>`).
   Without a client id the "Edit OpenStreetMap directly" option is hidden.

## How it talks to OsmAnd

Everything below was read from the OsmAnd source at commit `32a03a67` (2026-11-05). No AIDL method was
invented; the interface is a verbatim copy in [`android/osmand-aidl/`](../android/osmand-aidl).

| Need | Mechanism | Source in OsmAnd |
|---|---|---|
| Detect OsmAnd | `PackageManager` for `net.osmand.plus`, `net.osmand`, `net.osmand.dev` (declared in `<queries>`) | package names |
| Connect | `bindService(Intent("net.osmand.aidl.OsmandAidlServiceV2").setPackage(pkg))` | `AndroidManifest.xml` service `OsmandAidlServiceV2` |
| Show point layer | `addMapLayer(AddMapLayerParams(AMapLayer(id, name, zOrder, points)))` | `IOsmAndAidlInterface.aidl` |
| Send points | `updateMapLayer(UpdateMapLayerParams(AMapLayer(...)))` in chunks of 250 | same |
| Hide layer / full refresh | `removeMapLayer(RemoveMapLayerParams(id))` | same |
| Centre map | `setMapLocation(SetMapLocationParams(lat, lon, zoom, rotation, animated))` | same |
| Open OsmAnd | launch intent of the package | — |
| Permission | `startActivityForResult(Intent("net.osmand.aidl.REQUEST_PERMISSIONS").setPackage(pkg).putExtra("groups", ["map"]))` | `AidlPermissionRequestActivity` |
| Import `.osf` | `ACTION_VIEW` with a `content://…/syria-maps.osf` FileProvider URI, `setPackage(pkg)`, read grant | `MapActivity` intent filters for `.osf` |

### Why `updateMapLayer` and not `addMapPoint`

`addMapPoint` sends one point per Binder call and OsmAnd reloads the map after each call. For 11,000
points that means 11,000 IPC round trips and map reloads. `updateMapLayer` takes many points per call.
In OsmAnd (`ConnectedApp.updateMapLayer`) it *merges* points into the existing layer and never deletes
any. A sync therefore runs `removeMapLayer` → `addMapLayer` (empty) → `updateMapLayer` × N, so places
deleted on the server disappear. Chunks of 250 points keep each transaction well below the ~1 MB Binder limit.

### Behaviour that comes from OsmAnd, not from us

- **Layers live in memory only.** OsmAnd stores AIDL layers in a `ConcurrentHashMap`, so they are gone after
  OsmAnd restarts. The app re-sends enabled layers whenever it (re)connects, which happens when you open it.
- **Permissions.** Current OsmAnd builds create new connected apps as *disabled*. The `map` group
  (layers, points, setMapLocation) is granted by default once the app is enabled. When OsmAnd answers
  `false`, the app shows "allow" (the permission screen) or, on versions without that screen,
  instructions to enable the app in OsmAnd's *Menu → Plugins* list.
  Tested on a Galaxy Z Fold7 (Android 17) with OsmAnd 5.4.11 (`net.osmand`): the first `addMapLayer`
  is refused (`enabled: false` in OsmAnd's log), and 5.4.11 has no `REQUEST_PERMISSIONS` activity yet
  (it exists on OsmAnd `master`). In the 5.4 branch, connected apps are listed in *Plugins*
  (`PluginsListAdapter`) and switched on there.
- **No overlay selection via AIDL.** Raster overlays (our tile layers) still come from the `.osf`
  `MAP_SOURCES` item, and the user selects them under *Configure map → Overlay map*.

## .osf import and user consent

The app downloads `/osmand/syria-maps.osf` from the server and opens it in OsmAnd with `ACTION_VIEW`.
OsmAnd then shows its normal import screen and the user confirms. The AIDL method `importProfile` was
**deliberately not used**: its `ProfileSettingsParams` has a `silent` flag that skips the confirmation,
and it needs the `settings` permission group.

## Layer types

| Type | Example | How it reaches OsmAnd | In the app |
|---|---|---|---|
| Point layers | public services | AIDL map layer (live, in memory) | Switch per layer |
| Raster overlays | tile endpoint `/tiles/...` | `.osf` → `MAP_SOURCES` → Overlay map | "Install map package" button + instructions |
| Vector maps (streets, names) | streets-places | OsmAnd's own OBF maps (download inside OsmAnd) | Info only |
| 3D buildings | buildings-3d | OsmAnd's 3D rendering of OSM `height`/`building:levels` | Info only (planned) |
| Verified notices | traffic, safety-notices | API only for now | Listed as "later" |

## Error handling

| Situation | What the user sees |
|---|---|
| OsmAnd not installed | Install button (Play Store, web fallback) |
| `bindService` fails / null binding | "This OsmAnd version does not support AIDL, update it" |
| OsmAnd killed while connected | "Connection lost", reconnect on resume / "Sync now" |
| OsmAnd returns `false` | Permission explanation + "Allow" button |
| `RemoteException` (missing method, transaction failure) | "Request X failed in OsmAnd" on the layer |
| Server unreachable / HTTP error | Message with HTTP code + retry |
| `.osf` download fails | Dialog |

Datasets are cached with the server's `ETag` (`If-None-Match` → `304`), so a re-sync after an OsmAnd
restart does not download the data again.

## Build

Requirements: JDK 17+ (Android Studio's `jbr` works), Android SDK 36.

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
.\gradlew.bat assembleRelease bundleRelease -PsyriaMapsServer=https://your-host
```

Outputs:
- `app/build/outputs/apk/release/app-release.apk`: signed APK for direct installation
- `app/build/outputs/bundle/release/app-release.aab`: for Google Play

### Signing

Release signing reads `android/keystore.properties` (git-ignored):

```
storeFile=keystore/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Create a key with:

```bash
keytool -genkeypair -keystore keystore/upload.jks -storetype PKCS12 -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Without that file, the release build is signed with the debug key: installable, but not accepted by Google Play.
**Back up the keystore and its passwords.** With Play App Signing a lost upload key can be reset, but only
through Google support.

### Google Play checklist

- [x] targetSdk 36, AAB, no dangerous permissions (only `INTERNET`), package visibility limited to OsmAnd packages
- [x] HTTPS-only network config for release (cleartext only in debug)
- [ ] Privacy policy URL (the app sends no personal data. It only downloads layers from the configured server)
- [ ] Store listing, screenshots, Data safety form
- [ ] Set the production server URL at build time (`-PsyriaMapsServer`)

## License

The app links the OsmAnd AIDL classes, which are GPL-3.0, so **the companion app is distributed under
GPL-3.0** ([`android/LICENSE`](../android/LICENSE)). The rest of the repository stays MIT. MIT code may be
combined into a GPL-3.0 work. Publishing on Google Play is allowed, but the full source (this repository)
must stay available. No OsmAnd artwork (CC-BY-NC-ND) is used.

## Verification status

| Check | Status |
|---|---|
| Compiles against the vendored OsmAnd AIDL interface | Done |
| Unit tests (JSON parsing, point mapping, chunking, location parsing, note text, community body, OSM XML/tags, PKCE) | Done (23 tests) |
| Team submission: phone → server → moderation page → community layer → OsmAnd | Verified 2026-11-06 (in-memory storage) |
| Direct OSM edit: login, nearby search, upload | **Not yet verified on a device** (needs a registered OAuth app) |
| Signed release APK + AAB, lint without errors | Done |
| Binding, layer display and permission flow on a real device with OsmAnd | Verified 2026-11-06: Galaxy Z Fold7, Android 17, OsmAnd 5.4.11 (`net.osmand`). Bind OK; first call refused until the app was enabled in *Plugins*; then remove/add/updateMapLayer accepted and 48 synthetic points shown around Damascus |
| `.osf` hand-over via ACTION_VIEW on a real device | Verified 2026-11-06 (Fold7, OsmAnd 5.4.11): OsmAnd shows its own import dialog; after import OsmAnd must be restarted to load the new tile source |
| Report: shared OsmAnd point → note | Verified 2026-11-06 on the OSM dev server (note 401778, correct position and text) |
| Report: button on our points in OsmAnd | Verified 2026-11-06: button shown, callback received, direct start blocked by Android (`BAL_BLOCK`), notification opens the prefilled report |
