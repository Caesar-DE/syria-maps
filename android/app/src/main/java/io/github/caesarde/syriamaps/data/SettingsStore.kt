package io.github.caesarde.syriamaps.data

import android.content.Context
import io.github.caesarde.syriamaps.BuildConfig

/** Small persisted settings: server URL, enabled point layers, chosen OsmAnd package. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("syria_maps", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null) ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER, normalizeServerUrl(value)).apply()

    var enabledLayers: Set<String>
        get() = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_ENABLED, value).apply()

    var osmandPackage: String?
        get() = prefs.getString(KEY_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PACKAGE, value).apply()

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_ENABLED = "enabled_layers"
        private const val KEY_PACKAGE = "osmand_package"

        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        }
    }
}
