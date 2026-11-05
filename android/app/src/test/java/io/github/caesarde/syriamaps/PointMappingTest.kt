package io.github.caesarde.syriamaps

import io.github.caesarde.syriamaps.data.KindStyle
import io.github.caesarde.syriamaps.data.LayerInfo
import io.github.caesarde.syriamaps.data.PointFeature
import io.github.caesarde.syriamaps.data.SettingsStore
import io.github.caesarde.syriamaps.osmand.PointMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointMappingTest {
    private val layer = LayerInfo(
        id = "public-services", titleAr = "الخدمات العامة", titleEn = "Public services", descriptionAr = "",
        category = "services", status = "implemented", geometryTypes = listOf("Point"), synthetic = true,
        verifiedOnly = false, tiles = true, osfMapSource = true, featureCount = 2, latestVersion = 1,
        license = "CC0-1.0", attribution = "Syria Maps contributors",
        kinds = mapOf("pharmacy" to KindStyle(0xFF2E7D32.toInt(), "صيدلية", "Pharmacy")),
        geojsonUrl = "https://h/x",
    )

    private fun feature(id: String, kind: String?, ar: String?, en: String?, synthetic: Boolean = false) =
        PointFeature(id, 33.5, 36.3, kind, ar, en, "© OpenStreetMap contributors", synthetic)

    @Test
    fun mapsFeatureToOsmAndPoint() {
        val spec = PointMapping.toSpec(layer, feature("a", "pharmacy", "صيدلية النور", "Al-Nour Pharmacy"))
        assertEquals("a", spec.id)
        assertEquals("ص", spec.shortName)
        assertEquals("صيدلية النور", spec.fullName)
        assertEquals("صيدلية", spec.typeName)
        assertEquals(0xFF2E7D32.toInt(), spec.color)
        assertEquals(listOf("Al-Nour Pharmacy", "الخدمات العامة — © OpenStreetMap contributors"), spec.details)
    }

    @Test
    fun unnamedAndUnknownKindFallBack() {
        val spec = PointMapping.toSpec(layer, feature("b", "unknown", null, null, synthetic = true))
        assertEquals("unknown", spec.fullName)
        assertEquals("u", spec.shortName)
        assertEquals(0xFF455A64.toInt(), spec.color)
        assertTrue(spec.details.last().contains("تجريبية"))
    }

    @Test
    fun chunksStayBelowBinderBudget() {
        val specs = (1..11_421).map { PointMapping.toSpec(layer, feature("p$it", "pharmacy", "اسم $it", null)) }
        val chunks = PointMapping.chunks(specs)
        assertEquals(46, chunks.size)
        assertTrue(chunks.all { it.size <= PointMapping.CHUNK_SIZE })
        assertEquals(11_421, chunks.sumOf { it.size })
    }

    @Test
    fun layerIdIsNamespaced() {
        assertEquals("syria-maps/public-services", PointMapping.osmandLayerId(layer))
    }

    @Test
    fun serverUrlNormalisation() {
        assertEquals("https://maps.example.org", SettingsStore.normalizeServerUrl(" maps.example.org/ "))
        assertEquals("http://10.0.2.2:8000", SettingsStore.normalizeServerUrl("http://10.0.2.2:8000/"))
    }
}
