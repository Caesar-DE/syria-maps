package io.github.caesarde.syriamaps.data

import android.content.Context
import io.github.caesarde.syriamaps.BuildConfig

/** Small persisted settings: server URL, enabled point layers, chosen OsmAnd package, language, theme, setup state. */
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

    /** "ar", "de" or "en". First run: the device language if supported, else Arabic. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, null) ?: defaultLanguage()
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** Opt-in: "Report" button in OsmAnd's menu of our points (opens the report via a notification). */
    var osmandReportButton: Boolean
        get() = prefs.getBoolean(KEY_REPORT_BUTTON, false)
        set(value) = prefs.edit().putBoolean(KEY_REPORT_BUTTON, value).apply()

    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    companion object {
        val LANGUAGES = listOf("ar", "de", "en")
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_REPORT_BUTTON = "osmand_report_button"
        private const val KEY_SERVER = "server_url"
        private const val KEY_ENABLED = "enabled_layers"
        private const val KEY_PACKAGE = "osmand_package"

        fun defaultLanguage(): String =
            java.util.Locale.getDefault().language.takeIf { it in LANGUAGES } ?: "ar"

        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        }
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
