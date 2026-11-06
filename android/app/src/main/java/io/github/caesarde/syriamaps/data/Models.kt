package io.github.caesarde.syriamaps.data

/** Layer metadata as returned by `GET /api/v1/layers` of the Syria Maps backend. */
data class LayerInfo(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String,
    val category: String,
    val status: String,
    val geometryTypes: List<String>,
    val synthetic: Boolean,
    val verifiedOnly: Boolean,
    val tiles: Boolean,
    val osfMapSource: Boolean,
    val featureCount: Int,
    val latestVersion: Int?,
    val license: String,
    val attribution: String,
    val kinds: Map<String, KindStyle>,
    val geojsonUrl: String?,
) {
    /** How this layer can reach OsmAnd. Different layer types need different mechanisms. */
    val integration: Integration
        get() = when {
            status == "planned" -> Integration.PLANNED
            verifiedOnly -> Integration.NOTICES
            geometryTypes == listOf("Point") && geojsonUrl != null -> Integration.POINTS_AIDL
            tiles -> Integration.RASTER_OSF
            else -> Integration.PLANNED
        }

    /** Also published as an online raster overlay inside the .osf package. */
    val alsoRaster: Boolean get() = tiles && osfMapSource && status != "planned"
}

data class KindStyle(val color: Int, val labelAr: String, val labelEn: String) {
    /** The server only provides Arabic and English labels; German falls back to English. */
    fun label(language: String): String = if (language == "ar") labelAr else labelEn.ifBlank { labelAr }
}

/** Layer title in the UI language (server provides ar/en; German uses English). */
fun LayerInfo.title(language: String): String = if (language == "ar") titleAr else titleEn.ifBlank { titleAr }

enum class Integration {
    /** Points pushed into OsmAnd at runtime via the AIDL map-layer API. */
    POINTS_AIDL,

    /** Raster tile overlay — only through the .osf map source (no AIDL method selects overlays). */
    RASTER_OSF,

    /** Verified traffic/safety notices — served by the API, not yet shown in the app. */
    NOTICES,

    /** Vector/3D/base layers that come from OsmAnd's own maps, or layers without data yet. */
    PLANNED,
}

/** One point of a layer dataset (GeoJSON Point feature). */
data class PointFeature(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val kind: String?,
    val nameAr: String?,
    val nameEn: String?,
    val attribution: String,
    val synthetic: Boolean,
)

/** Parses `#rrggbb` into an opaque ARGB int. */
fun parseHexColor(hex: String, fallback: Int = 0xFF455A64.toInt()): Int {
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return fallback
    return clean.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: fallback
}
