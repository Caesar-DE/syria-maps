# osmand-aidl (vendored, unmodified)

The `src/` folder is an **unmodified copy** (line endings normalised by Git) of the official OsmAnd AIDL API module
(`OsmAnd-api/src`) from https://github.com/osmandapp/OsmAnd at commit
`32a03a67032ee4b12a81ac8153980ff015b3a8a2` (2026-11-05).

- License: **GPL-3.0** (OsmAnd source code license). Because the companion app links these
  classes, the companion app is distributed under GPL-3.0 as well — see `../LICENSE`.
- No OsmAnd artwork, icons or UI resources are included.
- Only `build.gradle.kts`, `AndroidManifest.xml`, `consumer-rules.pro` and this README are ours.
  The original module's manifest and `res/` (an `app_name` string) were left out. The manifest set
  the package through the `package` attribute, which AGP 8 replaces with `namespace`.

To update: copy `OsmAnd-api/src` from a newer OsmAnd commit over `src/`, update the commit hash
above, rebuild, and re-check the method signatures used in `app/src/main/java/.../osmand/`.
