package io.github.caesarde.syriamaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmEditTest {

    @Test
    fun `new place tags follow OSM tagging and the script of the name`() {
        assertEquals(
            mapOf("amenity" to "school", "name" to "مدرسة الأمل", "name:ar" to "مدرسة الأمل"),
            OsmXml.newPlaceTags("school", " مدرسة الأمل "),
        )
        assertEquals(mapOf("shop" to "bakery", "name:en" to "Al-Amal Bakery"), OsmXml.newPlaceTags("bakery", "Al-Amal Bakery"))
        assertEquals(mapOf("amenity" to "drinking_water"), OsmXml.newPlaceTags("water", ""))
        assertNull(OsmXml.newPlaceTags("other", "x"))
    }

    @Test
    fun `rename keeps other tags and only touches name keys`() {
        val old = mapOf("highway" to "residential", "name" to "شارع قديم", "name:ar" to "شارع قديم", "name:en" to "Old St")
        val new = OsmXml.renamedTags(old, "شارع جديد")
        assertEquals("residential", new["highway"])
        assertEquals("شارع جديد", new["name"])
        assertEquals("شارع جديد", new["name:ar"])
        assertEquals("Old St", new["name:en"])
        val edit = OsmEdit.Retag(OsmElement("way", 1, 3, old), new, "c")
        assertEquals(listOf("name", "name:ar"), edit.changes.map { it.first })
    }

    @Test
    fun `xml escapes values and keeps way node refs`() {
        val xml = OsmXml.nodeXml(42, 33.5, 36.3, mapOf("name" to "A & \"B\" <C>"))
        assertTrue(xml.contains("changeset=\"42\""))
        assertTrue(xml.contains("lat=\"33.5000000\""))
        assertTrue(xml.contains("v=\"A &amp; &quot;B&quot; &lt;C&gt;\""))
        val way = OsmElement("way", 7, 5, emptyMap(), nodeRefs = listOf(1, 2, 3))
        val wxml = OsmXml.wayXml(42, way, mapOf("highway" to "residential"))
        assertTrue(wxml.contains("id=\"7\" version=\"5\""))
        assertTrue(wxml.contains("<nd ref=\"1\"/><nd ref=\"2\"/><nd ref=\"3\"/>"))
        val cs = OsmXml.changesetXml("Add school", "0.1.0")
        assertTrue(cs.contains("k=\"hashtags\" v=\"#SyriaMaps\""))
        assertTrue(cs.contains("k=\"created_by\" v=\"Syria Maps 0.1.0\""))
    }

    @Test
    fun `nearby keeps named nodes and ways nearest first`() {
        val xml = """
            <osm version="0.6">
              <node id="1" version="1" lat="33.5000" lon="36.3000"/>
              <node id="2" version="1" lat="33.5010" lon="36.3000"/>
              <node id="3" version="2" lat="33.5002" lon="36.3001"><tag k="amenity" v="pharmacy"/><tag k="name" v="صيدلية"/></node>
              <node id="4" version="1" lat="33.5003" lon="36.3003"><tag k="amenity" v="bench"/></node>
              <way id="10" version="4"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="name" v="شارع"/></way>
              <way id="11" version="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="service"/></way>
            </osm>
        """.trimIndent()
        val list = OsmXml.parseNearby(xml, 33.5, 36.3)
        assertEquals(listOf("way/10", "node/3"), list.map { "${it.type}/${it.id}" })
        assertEquals(listOf(1L, 2L), list[0].nodeRefs)
        assertEquals("highway=residential", list[0].kindHint)
        assertTrue(list[1].distanceM in 20.0..30.0)
    }

    @Test
    fun `parse single element`() {
        val el = OsmXml.parseElement("""<osm><node id="5" version="9" lat="33.1" lon="36.2"><tag k="name" v="x"/></node></osm>""")
        assertEquals("node", el.type)
        assertEquals(9, el.version)
        assertEquals(33.1, el.lat!!, 1e-9)
        assertEquals(mapOf("name" to "x"), el.tags)
    }

    @Test
    fun `pkce challenge matches RFC 7636 example`() {
        // Appendix B of RFC 7636.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(Pkce.verifier().length >= 43)
    }
}
