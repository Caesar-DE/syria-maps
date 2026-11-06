package io.github.caesarde.syriamaps.data

import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource

class OsmApiException(message: String, val httpCode: Int? = null) : IOException(message)

/** A node or way from the OSM API (relations are not edited by the app). */
data class OsmElement(
    val type: String, // "node" | "way"
    val id: Long,
    val version: Int,
    val tags: Map<String, String>,
    val lat: Double? = null,
    val lon: Double? = null,
    val nodeRefs: List<Long> = emptyList(),
    /** Distance to the report location in metres (nearest node for ways). */
    val distanceM: Double = 0.0,
) {
    val name: String? get() = tags["name"] ?: tags["name:ar"] ?: tags["name:en"]

    /** Short human hint like "highway=residential". */
    val kindHint: String?
        get() = listOf("highway", "amenity", "shop", "place", "building", "leisure", "tourism", "healthcare")
            .firstNotNullOfOrNull { k -> tags[k]?.let { "$k=$it" } }
}

/** The change the user is about to upload, shown as a preview first. */
sealed interface OsmEdit {
    val comment: String

    data class AddNode(val lat: Double, val lon: Double, val tags: Map<String, String>, override val comment: String) : OsmEdit

    data class Retag(
        val element: OsmElement,
        val newTags: Map<String, String>,
        override val comment: String,
    ) : OsmEdit {
        /** Only the keys that actually change (for the preview). */
        val changes: List<Triple<String, String?, String?>>
            get() = (element.tags.keys + newTags.keys).distinct().sorted()
                .filter { element.tags[it] != newTags[it] }
                .map { Triple(it, element.tags[it], newTags[it]) }
    }
}

/** Pure helpers: tag mapping, XML building and parsing. Unit tested. */
object OsmXml {
    /** Place kinds the app offers → OSM tags (the same tags the Syria Maps OSM import reads). */
    val KIND_TAGS: Map<String, Map<String, String>> = mapOf(
        "school" to mapOf("amenity" to "school"),
        "pharmacy" to mapOf("amenity" to "pharmacy", "healthcare" to "pharmacy"),
        "hospital" to mapOf("amenity" to "hospital", "healthcare" to "hospital"),
        "clinic" to mapOf("amenity" to "clinic", "healthcare" to "clinic"),
        "fuel" to mapOf("amenity" to "fuel"),
        "bakery" to mapOf("shop" to "bakery"),
        "water" to mapOf("amenity" to "drinking_water"),
    )

    private val ARABIC = Regex("[\\u0600-\\u06FF]")

    fun isArabic(text: String) = ARABIC.containsMatchIn(text)

    /** Name tags: in Syria `name` is the local (Arabic) name; Latin input goes to `name:en` only. */
    fun nameTags(name: String): Map<String, String> {
        val n = name.trim()
        if (n.isEmpty()) return emptyMap()
        return if (isArabic(n)) mapOf("name" to n, "name:ar" to n) else mapOf("name:en" to n)
    }

    fun newPlaceTags(kind: String, name: String): Map<String, String>? {
        val base = KIND_TAGS[kind] ?: return null
        return base + nameTags(name)
    }

    /** New tag set for a rename: keep everything, replace the name tags that the new name covers. */
    fun renamedTags(old: Map<String, String>, newName: String): Map<String, String> = old + nameTags(newName)

    fun changesetXml(comment: String, appVersion: String): String = buildString {
        append("<osm><changeset>")
        tag("created_by", "Syria Maps $appVersion")
        tag("comment", comment)
        tag("hashtags", "#SyriaMaps")
        // Edits come from the user's own knowledge of the place; there is no imagery or import source.
        tag("source", "local knowledge")
        append("</changeset></osm>")
    }

    fun nodeXml(changeset: Long, lat: Double, lon: Double, tags: Map<String, String>, id: Long? = null, version: Int? = null): String =
        buildString {
            append("<osm><node changeset=\"").append(changeset).append('"')
            if (id != null) append(" id=\"").append(id).append('"')
            if (version != null) append(" version=\"").append(version).append('"')
            append(" lat=\"").append(fmt(lat)).append("\" lon=\"").append(fmt(lon)).append("\">")
            tags.toSortedMap().forEach { (k, v) -> tag(k, v) }
            append("</node></osm>")
        }

    fun wayXml(changeset: Long, way: OsmElement, tags: Map<String, String>): String = buildString {
        append("<osm><way changeset=\"").append(changeset).append("\" id=\"").append(way.id)
            .append("\" version=\"").append(way.version).append("\">")
        way.nodeRefs.forEach { append("<nd ref=\"").append(it).append("\"/>") }
        tags.toSortedMap().forEach { (k, v) -> tag(k, v) }
        append("</way></osm>")
    }

    private fun StringBuilder.tag(k: String, v: String) {
        append("<tag k=\"").append(esc(k)).append("\" v=\"").append(esc(v.take(255))).append("\"/>")
    }

    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun fmt(d: Double) = String.format(Locale.ROOT, "%.7f", d)

    /**
     * Named nodes and ways from a /map response, nearest first. Ways get the distance of their nearest node.
     * Unnamed objects are left out: renaming needs something that has (or should have) a name.
     */
    fun parseNearby(xml: String, lat: Double, lon: Double, limit: Int = 10): List<OsmElement> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val coords = HashMap<Long, Pair<Double, Double>>()
        val result = mutableListOf<OsmElement>()
        val nodes = doc.getElementsByTagName("node")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            val id = e.getAttribute("id").toLong()
            val nLat = e.getAttribute("lat").toDouble()
            val nLon = e.getAttribute("lon").toDouble()
            coords[id] = nLat to nLon
            val tags = tagsOf(e)
            if (tags.keys.any { it == "name" || it.startsWith("name:") }) {
                result += OsmElement("node", id, e.getAttribute("version").toInt(), tags, nLat, nLon,
                    distanceM = metres(lat, lon, nLat, nLon))
            }
        }
        val ways = doc.getElementsByTagName("way")
        for (i in 0 until ways.length) {
            val e = ways.item(i) as Element
            val tags = tagsOf(e)
            if (tags.keys.none { it == "name" || it.startsWith("name:") }) continue
            val refs = mutableListOf<Long>()
            val nds = e.getElementsByTagName("nd")
            for (j in 0 until nds.length) refs += (nds.item(j) as Element).getAttribute("ref").toLong()
            val d = refs.mapNotNull { coords[it] }.minOfOrNull { (a, b) -> metres(lat, lon, a, b) } ?: continue
            result += OsmElement("way", e.getAttribute("id").toLong(), e.getAttribute("version").toInt(), tags,
                nodeRefs = refs, distanceM = d)
        }
        return result.sortedBy { it.distanceM }.take(limit)
    }

    fun parseElement(xml: String): OsmElement {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val e = (doc.getElementsByTagName("node").item(0) ?: doc.getElementsByTagName("way").item(0)) as Element
        val refs = mutableListOf<Long>()
        val nds = e.getElementsByTagName("nd")
        for (j in 0 until nds.length) refs += (nds.item(j) as Element).getAttribute("ref").toLong()
        return OsmElement(
            type = e.tagName, id = e.getAttribute("id").toLong(), version = e.getAttribute("version").toInt(),
            tags = tagsOf(e), lat = e.getAttribute("lat").toDoubleOrNull(), lon = e.getAttribute("lon").toDoubleOrNull(),
            nodeRefs = refs,
        )
    }

    private fun tagsOf(e: Element): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val tags = e.getElementsByTagName("tag")
        for (i in 0 until tags.length) {
            val t = tags.item(i) as Element
            out[t.getAttribute("k")] = t.getAttribute("v")
        }
        return out
    }

    /** Equirectangular distance; accurate enough within a few hundred metres. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * 6_371_000.0
    }
}

/** OSM API 0.6 calls for one edit, authenticated with the user's OAuth2 token. */
class OsmEditor(private val apiBase: String, private val token: String, private val appVersion: String) {

    /** Named objects within roughly [radiusDeg] degrees (about 80 m by default). */
    suspend fun nearby(lat: Double, lon: Double, radiusDeg: Double = 0.0008): List<OsmElement> = withContext(Dispatchers.IO) {
        val bbox = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", lon - radiusDeg, lat - radiusDeg, lon + radiusDeg, lat + radiusDeg)
        OsmXml.parseNearby(request("GET", "/api/0.6/map?bbox=$bbox"), lat, lon)
    }

    suspend fun displayName(): String = withContext(Dispatchers.IO) {
        org.json.JSONObject(request("GET", "/api/0.6/user/details.json", accept = "application/json"))
            .getJSONObject("user").getString("display_name")
    }

    /** Uploads [edit] in its own changeset; returns the changeset id. The changeset is always closed. */
    suspend fun apply(edit: OsmEdit): Long = withContext(Dispatchers.IO) {
        val changeset = request("PUT", "/api/0.6/changeset/create", OsmXml.changesetXml(edit.comment, appVersion)).trim().toLong()
        try {
            when (edit) {
                is OsmEdit.AddNode -> request("PUT", "/api/0.6/node/create", OsmXml.nodeXml(changeset, edit.lat, edit.lon, edit.tags))
                is OsmEdit.Retag -> {
                    // Re-read the element so a change made by someone else meanwhile is not overwritten blindly.
                    val path = "/api/0.6/${edit.element.type}/${edit.element.id}"
                    val current = OsmXml.parseElement(request("GET", path))
                    if (current.version != edit.element.version) throw OsmApiException("conflict: element changed", 409)
                    val body = if (current.type == "node") {
                        OsmXml.nodeXml(changeset, current.lat!!, current.lon!!, edit.newTags, current.id, current.version)
                    } else {
                        OsmXml.wayXml(changeset, current, edit.newTags)
                    }
                    request("PUT", path, body)
                }
            }
        } finally {
            runCatching { request("PUT", "/api/0.6/changeset/$changeset/close") }
        }
        changeset
    }

    private fun request(method: String, path: String, body: String? = null, accept: String = "text/xml"): String {
        val conn = (URL(apiBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SyriaMapsCompanion/$appVersion (+https://github.com/Caesar-DE/syria-maps)")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            }
        }
        try {
            body?.let { b -> conn.outputStream.use { it.write(b.toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                throw OsmApiException("OSM $method $path: HTTP $code ${msg.trim()}", code)
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
