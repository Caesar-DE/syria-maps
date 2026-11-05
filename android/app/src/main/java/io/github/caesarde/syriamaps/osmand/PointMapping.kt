package io.github.caesarde.syriamaps.osmand

import io.github.caesarde.syriamaps.data.LayerInfo
import io.github.caesarde.syriamaps.data.PointFeature

/** Plain description of an OsmAnd map point (converted to AMapPoint in [LayerSync]). */
data class PointSpec(
    val id: String,
    val shortName: String,
    val fullName: String,
    val typeName: String,
    val color: Int,
    val latitude: Double,
    val longitude: Double,
    val details: List<String>,
)

object PointMapping {
    /** Points per Binder transaction; keeps each call far below the ~1 MB Binder limit. */
    const val CHUNK_SIZE = 250
    private const val DEFAULT_COLOR = 0xFF455A64.toInt()

    /** Layer id inside OsmAnd. Prefixed so it never collides with other connected apps' layers. */
    fun osmandLayerId(layer: LayerInfo) = "syria-maps/${layer.id}"

    fun toSpec(layer: LayerInfo, f: PointFeature): PointSpec {
        val style = f.kind?.let { layer.kinds[it] }
        val typeLabel = style?.labelAr?.takeIf { it.isNotBlank() } ?: f.kind ?: layer.titleAr
        val name = f.nameAr ?: f.nameEn ?: typeLabel
        val details = buildList {
            if (f.nameEn != null && f.nameEn != name) add(f.nameEn)
            add("${layer.titleAr} — ${f.attribution.ifBlank { layer.attribution }}")
            if (f.synthetic) add("بيانات تجريبية اصطناعية — ليست مكاناً حقيقياً")
        }
        return PointSpec(
            id = f.id,
            // OsmAnd draws a single character inside the circle.
            shortName = typeLabel.trim().take(1).ifEmpty { "•" },
            fullName = name,
            typeName = typeLabel,
            color = style?.color ?: DEFAULT_COLOR,
            latitude = f.latitude,
            longitude = f.longitude,
            details = details,
        )
    }

    fun chunks(specs: List<PointSpec>): List<List<PointSpec>> = specs.chunked(CHUNK_SIZE)
}
