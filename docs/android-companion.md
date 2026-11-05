# Android companion app

Source: [`android/`](../android). Kotlin + Jetpack Compose, Arabic RTL interface, package
`io.github.caesarde.syriamaps`, minSdk 26, targetSdk 36.

The app replaces the manual steps (import `.osf`, enable plugins, pick overlays) for point layers:
the user turns a switch on, and the points appear in the official OsmAnd app.

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
  instructions for *Settings → Connected apps*.
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
| Unit tests (JSON parsing, point mapping, chunking) | Done (8 tests) |
| Signed release APK + AAB, lint without errors | Done |
| Binding, layer display and permission flow on a real device with OsmAnd | **Not yet verified** |
| `.osf` hand-over via ACTION_VIEW on a real device | **Not yet verified** |
