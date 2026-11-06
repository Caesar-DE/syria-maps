package io.github.caesarde.syriamaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmNotesTest {

    private fun assertLatLon(expected: Pair<Double, Double>, actual: Pair<Double, Double>?) {
        assertNotNull(actual)
        assertEquals(expected.first, actual!!.first, 1e-9)
        assertEquals(expected.second, actual.second, 1e-9)
    }

    @Test
    fun `parses OsmAnd share text`() {
        // Format built by OsmAnd's ShareMenu: title, "Location:", geo URI and osmand.net link.
        val text = "مدرسة\nLocation: \ngeo:33.51320,36.27650?z=17\nhttps://osmand.net/map?pin=33.51320,36.27650#17/33.51320/36.27650"
        assertLatLon(33.5132 to 36.2765, LocationParser.parse(text))
    }

    @Test
    fun `parses other link formats`() {
        assertLatLon(33.5 to 36.3, LocationParser.parse("https://osmand.net/map?pin=33.5,36.3#15/33.5/36.3"))
        assertLatLon(36.2 to 37.15, LocationParser.parse("https://www.openstreetmap.org/?mlat=36.2&mlon=37.15#map=16/36.2/37.15"))
        assertLatLon(34.73 to 36.71, LocationParser.parse("https://maps.google.com/?q=34.73,36.71"))
        assertLatLon(35.52 to 35.79, LocationParser.parse("https://www.openstreetmap.org/#map=17/35.52/35.79"))
        assertLatLon(33.51320 to 36.27650, LocationParser.parse("33.51320, 36.27650"))
    }

    @Test
    fun `rejects text without usable coordinates`() {
        assertNull(LocationParser.parse(null))
        assertNull(LocationParser.parse("Damascus, Syria"))
        assertNull(LocationParser.parse("geo:0,0?q=Damascus"))
        assertNull(LocationParser.parse("geo:95.0,36.0"))
        assertNull(LocationParser.parse("call 0933, 123456"))
    }

    private val here = ReportLocation(33.5, 36.3, ReportLocation.Source.SHARED)

    @Test
    fun `wrong name note needs the correct name and keeps user text`() {
        assertNull(NoteText.build(ReportDraft(ReportType.WRONG_NAME, here, currentName = "شارع بغداد")))
        val text = NoteText.build(ReportDraft(ReportType.WRONG_NAME, here, currentName = "شارع بغداد", newName = "شارع بغداد الجديد"))!!
        assertTrue(text.startsWith("Syria Maps report: Wrong name / اسم خاطئ"))
        assertTrue("Name on the map / الاسم على الخريطة: شارع بغداد" in text)
        assertTrue("Correct name / الاسم الصحيح: شارع بغداد الجديد" in text)
        assertTrue(text.trimEnd().endsWith(NoteText.HASHTAG))
    }

    @Test
    fun `new place note includes the OSM tag hint`() {
        val text = NoteText.build(ReportDraft(ReportType.NEW_PLACE, here, newName = "مدرسة الأمل", placeKind = "school"))!!
        assertTrue("School (amenity=school) / مدرسة" in text)
        assertTrue("Name / الاسم: مدرسة الأمل" in text)
    }

    @Test
    fun `incomplete drafts give no text`() {
        assertNull(NoteText.build(ReportDraft(ReportType.NEW_PLACE, null, newName = "x")))
        assertNull(NoteText.build(ReportDraft(ReportType.NEW_PLACE, here)))
        assertNull(NoteText.build(ReportDraft(ReportType.CLOSED, here)))
        assertNull(NoteText.build(ReportDraft(ReportType.OTHER, here)))
        assertNotNull(NoteText.build(ReportDraft(ReportType.OTHER, here, details = "الجسر مهدوم")))
    }

    @Test
    fun `long text is capped`() {
        val text = NoteText.build(ReportDraft(ReportType.OTHER, here, details = "x".repeat(5000)))!!
        assertTrue(text.length <= NoteText.MAX_LENGTH)
    }
}

class CommunitySubmissionTest {
    private val here = ReportLocation(33.5, 36.3, ReportLocation.Source.SHARED)

    @Test
    fun `new place maps to server fields and splits names by script`() {
        val ar = CommunitySubmission.build(ReportDraft(type = ReportType.NEW_PLACE, location = here, newName = "مدرسة الأمل", placeKind = "school"), true)!!
        assertEquals("new_place", ar.getString("type"))
        assertEquals("school", ar.getString("kind"))
        assertEquals("مدرسة الأمل", ar.getString("name_ar"))
        assertTrue(!ar.has("name_en"))
        assertTrue(ar.getBoolean("license_accepted"))
        val en = CommunitySubmission.build(ReportDraft(type = ReportType.NEW_PLACE, location = here, newName = "Al-Amal School"), true)!!
        assertEquals("Al-Amal School", en.getString("name_en"))
        assertTrue(!en.has("kind"))
    }

    @Test
    fun `incomplete drafts are not sent`() {
        assertNull(CommunitySubmission.build(ReportDraft(type = ReportType.NEW_PLACE, location = null, newName = "x"), true))
        assertNull(CommunitySubmission.build(ReportDraft(type = ReportType.WRONG_NAME, location = here, currentName = "x"), true))
        assertNull(CommunitySubmission.build(ReportDraft(type = ReportType.OTHER, location = here), true))
        assertNotNull(CommunitySubmission.build(ReportDraft(type = ReportType.CLOSED, location = here, currentName = "فرن"), true))
    }
}
