package io.github.caesarde.syriamaps.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApiException(message: String, val httpCode: Int? = null) : IOException(message)

/** Talks to the existing Syria Maps FastAPI backend. */
class ApiClient(private val baseUrl: String, private val cacheDir: File) {

    suspend fun layers(): List<LayerInfo> = withContext(Dispatchers.IO) {
        JsonParsers.parseLayers(get("$baseUrl/api/v1/layers").readText())
    }

    /**
     * Downloads a layer's GeoJSON dataset, reusing the cached copy when the server answers
     * `304 Not Modified` for the stored ETag.
     */
    suspend fun points(layer: LayerInfo): List<PointFeature> = withContext(Dispatchers.IO) {
        val url = layer.geojsonUrl ?: throw ApiException("layer ${layer.id} has no dataset")
        val dir = File(cacheDir, "datasets").apply { mkdirs() }
        val body = File(dir, "${layer.id}.geojson")
        val etagFile = File(dir, "${layer.id}.etag")
        val conn = open(url).apply {
            if (body.exists() && etagFile.exists()) setRequestProperty("If-None-Match", etagFile.readText())
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val tmp = File(dir, "${layer.id}.tmp")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(body.also { it.delete() })
                    conn.getHeaderField("ETag")?.let { etagFile.writeText(it) } ?: etagFile.delete()
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> Unit
                else -> throw ApiException("HTTP $code", code)
            }
        } finally {
            conn.disconnect()
        }
        JsonParsers.parsePoints(body.readText())
    }

    /** Downloads the server-built OsmAnd package into [target]. */
    suspend fun downloadOsf(target: File): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val conn = open("$baseUrl/osmand/syria-maps.osf")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw ApiException("HTTP ${conn.responseCode}", conn.responseCode)
            }
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        target
    }

    private fun get(url: String): Response {
        val conn = open(url)
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw ApiException("HTTP $code", code)
        }
        return Response(conn)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json, application/geo+json, */*")
            setRequestProperty("User-Agent", "SyriaMapsCompanion/0.1")
        }

    private class Response(private val conn: HttpURLConnection) {
        fun readText(): String = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
