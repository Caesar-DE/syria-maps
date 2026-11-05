package io.github.caesarde.syriamaps.data

import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON → model functions (unit-tested on the JVM). */
object JsonParsers {

    fun parseLayers(json: String): List<LayerInfo> {
        val array = JSONObject(json).getJSONArray("layers")
        return (0 until array.length()).map { parseLayer(array.getJSONObject(it)) }
    }

    fun parseLayer(o: JSONObject): LayerInfo {
        val kinds = o.optJSONObject("kinds")?.let { k ->
            k.keys().asSequence().associateWith { key ->
                val style = k.getJSONObject(key)
                val label = style.getJSONObject("label")
                KindStyle(parseHexColor(style.optString("color")), label.optString("ar"), label.optString("en"))
            }
        } ?: emptyMap()
        val downloads = o.optJSONObject("links")?.optJSONObject("downloads")
        return LayerInfo(
            id = o.getString("id"),
            titleAr = o.getJSONObject("title").optString("ar"),
            titleEn = o.getJSONObject("title").optString("en"),
            descriptionAr = o.optJSONObject("description")?.optString("ar").orEmpty(),
            category = o.optString("category"),
            status = o.optString("status"),
            geometryTypes = o.optJSONArray("geometry_types").strings(),
            synthetic = o.optBoolean("synthetic"),
            verifiedOnly = o.optBoolean("verified_only"),
            tiles = o.optBoolean("tiles"),
            osfMapSource = o.optJSONObject("osmand")?.optBoolean("map_source") ?: false,
            featureCount = o.optInt("feature_count"),
            latestVersion = if (o.isNull("latest_version")) null else o.optInt("latest_version"),
            license = o.optString("license"),
            attribution = o.optString("attribution"),
            kinds = kinds,
            geojsonUrl = downloads?.optString("geojson")?.takeIf { it.isNotBlank() },
        )
    }

    /** Point features of a dataset download; other geometry types are skipped. */
    fun parsePoints(json: String): List<PointFeature> {
        val root = JSONObject(json)
        val meta = root.optJSONObject("metadata")
        val defaultAttribution = meta?.optString("attribution").orEmpty()
        val features = root.getJSONArray("features")
        val out = ArrayList<PointFeature>(features.length())
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val geom = f.optJSONObject("geometry") ?: continue
            if (geom.optString("type") != "Point") continue
            val coords = geom.getJSONArray("coordinates")
            val p = f.optJSONObject("properties") ?: JSONObject()
            out += PointFeature(
                // external_id is stable across server re-imports; the numeric id is not.
                id = p.optStringOrNull("external_id") ?: p.opt("id")?.toString() ?: f.opt("id")?.toString() ?: "f$i",
                longitude = coords.getDouble(0),
                latitude = coords.getDouble(1),
                kind = p.optStringOrNull("kind"),
                nameAr = p.optStringOrNull("name_ar"),
                nameEn = p.optStringOrNull("name_en"),
                attribution = p.optStringOrNull("attribution") ?: defaultAttribution,
                synthetic = p.optBoolean("synthetic", meta?.optBoolean("synthetic") ?: false),
            )
        }
        return out
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
