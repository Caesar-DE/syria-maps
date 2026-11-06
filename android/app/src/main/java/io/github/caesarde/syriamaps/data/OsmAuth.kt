package io.github.caesarde.syriamaps.data

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * OpenStreetMap OAuth 2.0 (authorization code + PKCE, public client: no secret in the app).
 * The password is only ever entered on openstreetmap.org in the browser.
 *
 * Needs an OAuth2 application registered on the OSM server (Settings → OAuth 2 applications) with
 * redirect URI [REDIRECT_URI], scopes `read_prefs write_api`, "confidential" unticked. Its client id is
 * passed at build time (`-PosmClientId=…`, debug builds: `-PosmDevClientId=…` for the dev server).
 */
class OsmAuth(private val webBase: String, private val clientId: String) {

    val configured: Boolean get() = clientId.isNotBlank()

    /** URL to open in the browser; [challenge] is from [Pkce.challenge]. */
    fun authorizeUrl(challenge: String, state: String): String =
        Uri.parse("$webBase/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build().toString()

    /** Exchanges the authorization code for an access token. */
    suspend fun exchange(code: String, verifier: String): String = withContext(Dispatchers.IO) {
        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId,
            "code_verifier" to verifier,
        ).entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val conn = (URL("$webBase/oauth2/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(form.toByteArray()) }
            if (conn.responseCode != 200) throw OsmApiException("token HTTP ${conn.responseCode}", conn.responseCode)
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("access_token")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val REDIRECT_URI = "io.github.caesarde.syriamaps://oauth2"
        const val SCOPES = "read_prefs write_api"
    }
}

/** PKCE helpers (RFC 7636). */
object Pkce {
    private val random = SecureRandom()

    fun verifier(): String = randomToken(48)

    fun state(): String = randomToken(16)

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomToken(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
}
