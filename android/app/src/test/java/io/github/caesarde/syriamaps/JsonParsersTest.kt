package io.github.caesarde.syriamaps

import io.github.caesarde.syriamaps.data.Integration
import io.github.caesarde.syriamaps.data.JsonParsers
import io.github.caesarde.syriamaps.data.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonParsersTest {

    // Shape of GET /api/v1/layers from the Syria Maps backend (abridged).
    private val layersJson = """
        {"layers": [
          {"id": "public-services", "title": {"ar": "الخدمات العامة", "en": "Public services"},
           "description": {"ar": "وصف", "en": "d"}, "category": "services", "status": "implemented",
           "geometry_types": ["Point"], "license": "CC0-1.0", "attribution": "synthetic",
           "synthetic": true, "verified_only": false, "tiles": true,
           "osmand": {"map_source": true, "gpx_snapshot": true},
           "kinds": {"hospital": {"color": "#d32f2f", "label": {"ar": "مستشفى", "en": "Hospital"}}},
           "feature_count": 48, "latest_version": 1,
           "links": {"downloads": {"geojson": "https://h/api/v1/datasets/public-services/download?format=geojson"}}},
          {"id": "safety-notices", "title": {"ar": "س", "en": "s"}, "category": "safety", "status": "experimental",
           "geometry_types": ["Point", "Polygon"], "verified_only": true, "tiles": false,
           "osmand": {"map_source": false}, "kinds": {}, "feature_count": 0, "latest_version": null,
           "links": {"downloads": {}}},
          {"id": "buildings-3d", "title": {"ar": "مباني", "en": "b"}, "category": "buildings", "status": "planned",
           "geometry_types": ["Polygon"], "tiles": false, "kinds": {}, "feature_count": 0, "latest_version": null,
           "links": {"downloads": {}}}
        ]}
    """.trimIndent()

    @Test
    fun parsesLayersAndClassifiesIntegration() {
        val layers = JsonParsers.parseLayers(layersJson)
        assertEquals(listOf("public-services", "safety-notices", "buildings-3d"), layers.map { it.id })
        val ps = layers[0]
        assertEquals(Integration.POINTS_AIDL, ps.integration)
        assertTrue(ps.alsoRaster)
        assertTrue(ps.synthetic)
        assertEquals(48, ps.featureCount)
        assertEquals(1, ps.latestVersion)
        assertEquals(0xFFD32F2F.toInt(), ps.kinds.getValue("hospital").color)
        assertEquals(Integration.NOTICES, layers[1].integration)
        assertNull(layers[1].latestVersion)
        assertNull(layers[1].geojsonUrl)
        assertEquals(Integration.PLANNED, layers[2].integration)
        assertFalse(layers[2].alsoRaster)
    }

    @Test
    fun parsesOnlyPointsAndPrefersExternalId() {
        val json = """
            {"type": "FeatureCollection", "metadata": {"attribution": "© OpenStreetMap contributors", "synthetic": false},
             "features": [
              {"type": "Feature", "id": 7, "geometry": {"type": "Point", "coordinates": [36.3, 33.5]},
               "properties": {"id": 7, "external_id": "osm:node/1", "kind": "pharmacy", "name_ar": "صيدلية", "name_en": null}},
              {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[36, 33], [36.1, 33.1]]},
               "properties": {"id": 8}},
              {"type": "Feature", "geometry": {"type": "Point", "coordinates": [36.4, 33.6]},
               "properties": {"id": 9, "kind": "school", "attribution": "x", "synthetic": true}}
             ]}
        """.trimIndent()
        val points = JsonParsers.parsePoints(json)
        assertEquals(2, points.size)
        val p = points[0]
        assertEquals("osm:node/1", p.id)
        assertEquals(33.5, p.latitude, 0.0)
        assertEquals(36.3, p.longitude, 0.0)
        assertEquals("صيدلية", p.nameAr)
        assertNull(p.nameEn)
        assertEquals("© OpenStreetMap contributors", p.attribution)
        assertFalse(p.synthetic)
        assertEquals("9", points[1].id)
        assertTrue(points[1].synthetic)
    }

    @Test
    fun hexColors() {
        assertEquals(0xFF007A3D.toInt(), parseHexColor("#007a3d"))
        assertEquals(123, parseHexColor("red", fallback = 123))
    }
}
