package io.github.caesarde.syriamaps.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the user wants to tell OpenStreetMap mappers. */
enum class ReportType { WRONG_NAME, NEW_PLACE, CLOSED, OTHER }

/** A location, and where it came from (shown to the user so they can check it). */
data class ReportLocation(val latitude: Double, val longitude: Double, val source: Source) {
    enum class Source { SHARED, OUR_POINT, DEVICE_GPS }
}

/** Where a report goes: a public OSM note, or the moderated Syria Maps server (community layer). */
enum class ReportDestination { OSM_NOTE, COMMUNITY, OSM_EDIT }

data class ReportDraft(
    val type: ReportType = ReportType.WRONG_NAME,
    val location: ReportLocation? = null,
    /** Current / existing name (wrong name, closed place) — prefilled from our point when known. */
    val currentName: String = "",
    /** Correct name (wrong name) or name of the new place. */
    val newName: String = "",
    /** Kind of place for NEW_PLACE, e.g. "school". */
    val placeKind: String = "",
    val details: String = "",
    val destination: ReportDestination = ReportDestination.OSM_NOTE,
)

/**
 * Builds the public text of an OSM note. Labels are bilingual (English + Arabic) so both local and
 * foreign mappers can act on it; the user's own words are kept as written.
 */
object NoteText {
    const val HASHTAG = "#SyriaMaps"

    private val TITLES = mapOf(
        ReportType.WRONG_NAME to "Wrong name / اسم خاطئ",
        ReportType.NEW_PLACE to "Missing place / مكان غير موجود على الخريطة",
        ReportType.CLOSED to "Place closed or gone / مكان مغلق أو لم يعد موجوداً",
        ReportType.OTHER to "Map problem / مشكلة في الخريطة",
    )

    /** Kinds offered for NEW_PLACE: key -> (English, Arabic), matching the OSM tags the README lists. */
    val PLACE_KINDS: Map<String, Pair<String, String>> = linkedMapOf(
        "school" to ("School (amenity=school)" to "مدرسة"),
        "pharmacy" to ("Pharmacy (amenity=pharmacy)" to "صيدلية"),
        "hospital" to ("Hospital (amenity=hospital)" to "مستشفى"),
        "clinic" to ("Clinic (amenity=clinic)" to "عيادة"),
        "fuel" to ("Fuel station (amenity=fuel)" to "محطة وقود"),
        "bakery" to ("Bakery (shop=bakery)" to "فرن"),
        "water" to ("Water point (amenity=drinking_water)" to "نقطة مياه"),
        "other" to ("Other" to "أخرى"),
    )

    /** Null when the draft is not complete enough to be useful for a mapper. */
    fun build(d: ReportDraft): String? {
        if (d.location == null) return null
        val lines = mutableListOf("Syria Maps report: ${TITLES.getValue(d.type)}")
        when (d.type) {
            ReportType.WRONG_NAME -> {
                if (d.newName.isBlank()) return null
                if (d.currentName.isNotBlank()) lines += "Name on the map / الاسم على الخريطة: ${d.currentName.trim()}"
                lines += "Correct name / الاسم الصحيح: ${d.newName.trim()}"
            }
            ReportType.NEW_PLACE -> {
                if (d.newName.isBlank() && d.placeKind.isBlank()) return null
                PLACE_KINDS[d.placeKind]?.let { (en, ar) -> lines += "Type / النوع: $en / $ar" }
                if (d.newName.isNotBlank()) lines += "Name / الاسم: ${d.newName.trim()}"
            }
            ReportType.CLOSED -> {
                if (d.currentName.isBlank() && d.details.isBlank()) return null
                if (d.currentName.isNotBlank()) lines += "Place / المكان: ${d.currentName.trim()}"
            }
            ReportType.OTHER -> if (d.details.isBlank()) return null
        }
        if (d.details.isNotBlank()) lines += "Details / تفاصيل: ${d.details.trim()}"
        lines += "Reported with the Syria Maps app. Please verify on the ground before editing. $HASHTAG"
        return lines.joinToString("\n").take(MAX_LENGTH)
    }

    /** OSM rejects very long notes; keep well below. */
    const val MAX_LENGTH = 2000
}

/**
 * Body for POST /api/v1/submissions on the Syria Maps server (moderated community layer).
 * Same completeness rules as the server (app.submissions.SubmissionIn); null when incomplete.
 */
object CommunitySubmission {
    private val ARABIC = Regex("[\\u0600-\\u06FF]")

    fun build(d: ReportDraft, licenseAccepted: Boolean): JSONObject? {
        val loc = d.location ?: return null
        val name = d.newName.trim()
        val current = d.currentName.trim()
        val details = d.details.trim()
        val type = when (d.type) {
            ReportType.NEW_PLACE -> if (name.isEmpty() && d.placeKind.isEmpty()) return null else "new_place"
            ReportType.WRONG_NAME -> if (name.isEmpty()) return null else "wrong_name"
            ReportType.CLOSED -> if (current.isEmpty() && details.isEmpty()) return null else "closed"
            ReportType.OTHER -> if (details.isEmpty()) return null else "other"
        }
        return JSONObject().apply {
            put("type", type)
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            if (d.type == ReportType.NEW_PLACE && d.placeKind.isNotEmpty()) put("kind", d.placeKind)
            // The server keeps Arabic and Latin names apart; decide by the script the user typed.
            if (name.isNotEmpty()) put(if (ARABIC.containsMatchIn(name)) "name_ar" else "name_en", name.take(200))
            if (current.isNotEmpty()) put("current_name", current.take(200))
            if (details.isNotEmpty()) put("details", details.take(1000))
            put("license_accepted", licenseAccepted)
        }
    }
}

/** Finds coordinates in shared text or links (OsmAnd share text, geo: URIs, osm.org and osmand.net links). */
object LocationParser {
    private const val NUM = """(-?\d{1,3}(?:\.\d+)?)"""
    private val patterns = listOf(
        Regex("""geo:$NUM\s*,\s*$NUM"""),
        Regex("""[?&#]pin=$NUM\s*,\s*$NUM"""),
        Regex("""mlat=$NUM&mlon=$NUM"""),
        Regex("""[?&]q=$NUM\s*,\s*$NUM"""),
        Regex("""#map=\d+/$NUM/$NUM"""),
        // Plain "33.51320, 36.27650" as copied from OsmAnd's coordinates format; require decimals.
        Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})(?![\d.])"""),
    )

    fun parse(text: String?): Pair<Double, Double>? {
        if (text.isNullOrBlank()) return null
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            // geo:0,0 is "no location" in the geo URI scheme (used with ?q=address).
            if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) return lat to lon
        }
        return null
    }
}

class NoteException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * Creates OSM notes anonymously (POST /api/0.6/notes, documented OSM API 0.6). Notes are public.
 * Debug builds use the OSM development server so testing never reaches the real map.
 */
class OsmNotesClient(private val apiBase: String) {

    /** Returns the new note's id. */
    suspend fun create(latitude: Double, longitude: Double, text: String): Long = withContext(Dispatchers.IO) {
        val body = listOf(
            "lat" to String.format(Locale.ROOT, "%.6f", latitude),
            "lon" to String.format(Locale.ROOT, "%.6f", longitude),
            "text" to text,
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val conn = (URL("$apiBase/api/0.6/notes.json").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SyriaMapsCompanion/0.1 (+https://github.com/Caesar-DE/syria-maps)")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                throw NoteException("OSM API HTTP $code ${msg.trim()}", code)
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.getJSONObject("properties").getLong("id")
        } finally {
            conn.disconnect()
        }
    }

    fun noteUrl(id: Long): String = "${webBase()}/note/$id"

    private fun webBase(): String =
        if ("dev.openstreetmap.org" in apiBase) apiBase else "https://www.openstreetmap.org"

    companion object {
        const val PRODUCTION = "https://api.openstreetmap.org"
        const val DEVELOPMENT = "https://master.apis.dev.openstreetmap.org"
    }
}
