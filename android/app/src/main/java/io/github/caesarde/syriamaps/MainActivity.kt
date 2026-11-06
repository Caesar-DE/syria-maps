package io.github.caesarde.syriamaps

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caesarde.syriamaps.data.LocationParser
import io.github.caesarde.syriamaps.data.ReportLocation
import io.github.caesarde.syriamaps.data.SettingsStore
import io.github.caesarde.syriamaps.osmand.OsmAndApps
import io.github.caesarde.syriamaps.ui.MainScreen
import io.github.caesarde.syriamaps.ui.ReportScreen
import io.github.caesarde.syriamaps.ui.SetupWizard
import io.github.caesarde.syriamaps.ui.SyriaMapsTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // OsmAnd's permission screen identifies the caller only through startActivityForResult.
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onPermissionResult()
    }

    // Location for a report: asked only when the user taps "use my current location".
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.useDeviceLocation()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setOsmandReportButton(granted)
    }

    private fun setReportButton(enabled: Boolean) {
        when {
            !enabled -> vm.setOsmandReportButton(false)
            ReportNotification.canPost(this) -> vm.setOsmandReportButton(true)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestLocation() {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** A point shared from OsmAnd (or any geo: link) opens the report screen at that place. */
    private fun handleShare(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> return
        }
        val latLon = LocationParser.parse(text)
        vm.openReport(latLon?.let { ReportLocation(it.first, it.second, ReportLocation.Source.SHARED) }, fromOutside = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun attachBaseContext(newBase: Context) {
        // Non-Compose parts (task label, system dialogs) follow the chosen language too.
        super.attachBaseContext(localized(newBase, SettingsStore(newBase).language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            // Switch the language live inside Compose: no activity restart, so the wizard keeps animating.
            val activity = this
            val localizedConfig = remember(state.language) {
                Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(state.language)) }
            }
            val localizedContext = remember(state.language) {
                val res = createConfigurationContext(localizedConfig).resources
                object : ContextWrapper(activity) {
                    override fun getResources(): Resources = res
                }
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig,
            ) {
                SyriaMapsTheme(themeMode = state.themeMode, language = state.language) {
                    val pending by vm.intents.collectAsStateWithLifecycle()
                    LaunchedEffect(pending) {
                        val intent = pending ?: return@LaunchedEffect
                        vm.intentHandled()
                        try {
                            if (intent.action == OsmAndApps.ACTION_REQUEST_PERMISSIONS) permissionLauncher.launch(intent)
                            else startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            vm.intentFailed(intent)
                        }
                    }
                    BackHandler(enabled = state.report != null) {
                        val outside = state.reportFromOutside
                        vm.closeReport()
                        if (outside) moveTaskToBack(true)
                    }
                    AnimatedContent(
                        targetState = when {
                            state.report != null -> Screen.REPORT
                            state.setupDone -> Screen.MAIN
                            else -> Screen.SETUP
                        },
                        transitionSpec = {
                            (fadeIn(tween(500)) + scaleIn(tween(500), initialScale = 0.92f)) togetherWith
                                (fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 1.06f))
                        },
                        label = "setup",
                    ) { screen ->
                        when (screen) {
                            Screen.REPORT -> ReportScreen(state, vm, ::requestLocation)
                            Screen.MAIN -> MainScreen(state, vm, ::setReportButton)
                            Screen.SETUP -> SetupWizard(state, vm)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReportNotification.cancel(this)
        // OsmAnd may have been installed, updated or stopped while we were in the background.
        vm.refreshOsmAnd()
    }

    private enum class Screen { SETUP, MAIN, REPORT }

    private companion object {
        fun localized(base: Context, language: String): Context {
            val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            return base.createConfigurationContext(config)
        }
    }
}
