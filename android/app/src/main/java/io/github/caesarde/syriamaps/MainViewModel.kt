package io.github.caesarde.syriamaps

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.caesarde.syriamaps.data.ApiClient
import io.github.caesarde.syriamaps.data.Integration
import io.github.caesarde.syriamaps.data.LayerInfo
import io.github.caesarde.syriamaps.data.NoteText
import io.github.caesarde.syriamaps.data.OsmNotesClient
import io.github.caesarde.syriamaps.data.PointFeature
import io.github.caesarde.syriamaps.data.ReportDraft
import io.github.caesarde.syriamaps.data.ReportLocation
import io.github.caesarde.syriamaps.data.SettingsStore
import io.github.caesarde.syriamaps.data.ThemeMode
import io.github.caesarde.syriamaps.osmand.AidlResult
import io.github.caesarde.syriamaps.osmand.ConnectionState
import io.github.caesarde.syriamaps.osmand.InstalledOsmAnd
import io.github.caesarde.syriamaps.osmand.LayerSync
import io.github.caesarde.syriamaps.osmand.OsfInstaller
import io.github.caesarde.syriamaps.osmand.OsmAndApps
import io.github.caesarde.syriamaps.osmand.OsmAndConnection
import io.github.caesarde.syriamaps.osmand.PointMapping
import io.github.caesarde.syriamaps.osmand.ReportButton
import io.github.caesarde.syriamaps.osmand.SyncOutcome
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LayerSyncState {
    data object Off : LayerSyncState
    data class Syncing(val sent: Int, val total: Int) : LayerSyncState
    data class Synced(val points: Int, val at: Long) : LayerSyncState
    data class Error(val problem: Problem) : LayerSyncState
}

/** User-facing problems; the UI maps them to localized strings. */
sealed interface Problem {
    data object ServerUnreachable : Problem
    data class ServerHttp(val code: Int) : Problem
    data object OsmAndNotInstalled : Problem
    data object OsmAndNotConnected : Problem
    data object OsmAndUnsupported : Problem

    /** OsmAnd refused the call: the app is not enabled under Connected apps / missing "map" group. */
    data object PermissionDenied : Problem
    data class AidlFailed(val method: String, val detail: String?) : Problem
    data object OsfDownloadFailed : Problem
    data object PermissionScreenMissing : Problem
}

data class UiState(
    val installed: List<InstalledOsmAnd> = emptyList(),
    val selectedPackage: String? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    val serverUrl: String = "",
    val layers: List<LayerInfo> = emptyList(),
    val loadingLayers: Boolean = false,
    val layersProblem: Problem? = null,
    val enabled: Set<String> = emptySet(),
    val sync: Map<String, LayerSyncState> = emptyMap(),
    val osfBusy: Boolean = false,
    val notice: Problem? = null,
    val language: String = "ar",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val setupDone: Boolean = true,
    /** Non-null while the report screen is open. */
    val report: ReportDraft? = null,
    val reportStatus: ReportStatus = ReportStatus.Editing,
    /** True when the report was opened from another app (share / OsmAnd button): close returns there. */
    val reportFromOutside: Boolean = false,
    val osmandReportButton: Boolean = false,
)

/** Sending state of the report screen. */
sealed interface ReportStatus {
    data object Editing : ReportStatus
    data object Sending : ReportStatus
    data class Sent(val noteId: Long, val url: String) : ReportStatus
    data object Failed : ReportStatus
    data object Locating : ReportStatus
    data object NoLocationFix : ReportStatus
}

/** Result of the "test server" button in the setup wizard. */
sealed interface ServerProbe {
    data object Idle : ServerProbe
    data object Running : ServerProbe
    data class Ok(val layers: Int) : ServerProbe
    data class Failed(val problem: Problem) : ServerProbe
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val connection = OsmAndConnection(app)
    private val layerSync = LayerSync(connection)
    private val syncJobs = mutableMapOf<String, Job>()

    /** Last synced points per OsmAnd layer id, to resolve "report" clicks in OsmAnd's context menu. */
    private val syncedPoints = ConcurrentHashMap<String, Map<String, PointFeature>>()
    private val reportButton = ReportButton(connection, app.packageName) { layerId, pointId ->
        viewModelScope.launch { onOsmAndPointReport(layerId, pointId) }
    }
    private val notes = OsmNotesClient(BuildConfig.OSM_API)

    private val _state = MutableStateFlow(
        UiState(
            serverUrl = settings.serverUrl,
            enabled = settings.enabledLayers,
            language = settings.language,
            themeMode = settings.themeMode,
            setupDone = settings.setupDone,
            osmandReportButton = settings.osmandReportButton,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Activities to start (store, OsmAnd, permission screen, .osf import), consumed by MainActivity. */
    private val _intents = MutableStateFlow<Intent?>(null)
    val intents: StateFlow<Intent?> = _intents.asStateFlow()

    private var lastConnected = false

    init {
        viewModelScope.launch {
            connection.state.collect { c ->
                _state.update { it.copy(connection = c) }
                val connected = c is ConnectionState.Connected
                // OsmAnd forgets AIDL layers when it restarts: re-push enabled layers on every (re)connect.
                if (connected && !lastConnected) resyncEnabled()
                lastConnected = connected
            }
        }
        refreshOsmAnd()
        loadLayers()
    }

    private fun api() = ApiClient(settings.serverUrl, getApplication<Application>().filesDir)

    /** Called on start and on every resume: OsmAnd may have been installed or removed meanwhile. */
    fun refreshOsmAnd() {
        val installed = OsmAndApps.installed(getApplication())
        val chosen = settings.osmandPackage?.takeIf { p -> installed.any { it.packageName == p } }
            ?: installed.firstOrNull()?.packageName
        _state.update { it.copy(installed = installed, selectedPackage = chosen) }
        if (chosen == null) {
            connection.disconnect()
        } else {
            val c = connection.state.value
            if (c !is ConnectionState.Connected || c.packageName != chosen) {
                connection.connect(chosen)
            } else if (_state.value.sync.values.any { it is LayerSyncState.Error && it.problem == Problem.PermissionDenied }) {
                // Back from OsmAnd after the user may have enabled us there (Plugins list): try again.
                resyncEnabled()
            }
        }
    }

    fun selectPackage(pkg: String) {
        settings.osmandPackage = pkg
        refreshOsmAnd()
    }

    fun setServerUrl(url: String) {
        settings.serverUrl = url
        _state.update { it.copy(serverUrl = settings.serverUrl) }
        loadLayers()
    }

    fun setLanguage(language: String) {
        if (language !in SettingsStore.LANGUAGES) return
        settings.language = language
        _state.update { it.copy(language = language) }
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun finishSetup() {
        settings.setupDone = true
        _state.update { it.copy(setupDone = true) }
    }

    fun reopenSetup() {
        _state.update { it.copy(setupDone = false) }
    }

    private val _probe = MutableStateFlow<ServerProbe>(ServerProbe.Idle)
    val probe: StateFlow<ServerProbe> = _probe.asStateFlow()

    /** Checks a server URL without saving it: lists its layers. */
    fun probeServer(url: String) {
        viewModelScope.launch {
            _probe.value = ServerProbe.Running
            _probe.value = try {
                val base = SettingsStore.normalizeServerUrl(url)
                ServerProbe.Ok(ApiClient(base, getApplication<Application>().filesDir).layers().size)
            } catch (e: IOException) {
                ServerProbe.Failed(e.toProblem())
            } catch (e: RuntimeException) {
                // Malformed URL or a reply that is not our API (e.g. an HTML page).
                ServerProbe.Failed(Problem.ServerUnreachable)
            }
        }
    }

    fun resetProbe() {
        _probe.value = ServerProbe.Idle
    }

    fun loadLayers() {
        viewModelScope.launch {
            _state.update { it.copy(loadingLayers = true, layersProblem = null) }
            try {
                val layers = api().layers()
                _state.update { it.copy(layers = layers, loadingLayers = false) }
                resyncEnabled()
            } catch (e: IOException) {
                _state.update { it.copy(loadingLayers = false, layersProblem = e.toProblem()) }
            }
        }
    }

    fun setLayerEnabled(layer: LayerInfo, enabled: Boolean) {
        val set = settings.enabledLayers.toMutableSet().apply { if (enabled) add(layer.id) else remove(layer.id) }
        settings.enabledLayers = set
        _state.update { it.copy(enabled = set) }
        if (enabled) {
            startSync(layer)
        } else {
            syncJobs.remove(layer.id)?.cancel()
            setSync(layer.id, LayerSyncState.Off)
            viewModelScope.launch { layerSync.remove(layer) }
        }
    }

    fun syncNow() {
        refreshOsmAnd()
        if (_state.value.layers.isEmpty()) loadLayers() else resyncEnabled()
    }

    private fun resyncEnabled() {
        val s = _state.value
        if (s.connection !is ConnectionState.Connected) return
        s.layers.filter { it.id in s.enabled && it.integration == Integration.POINTS_AIDL }.forEach(::startSync)
    }

    private fun startSync(layer: LayerInfo) {
        syncJobs[layer.id]?.cancel()
        syncJobs[layer.id] = viewModelScope.launch {
            if (_state.value.connection !is ConnectionState.Connected) {
                setSync(layer.id, LayerSyncState.Error(connectionProblem()))
                return@launch
            }
            setSync(layer.id, LayerSyncState.Syncing(0, layer.featureCount))
            val features = try {
                api().points(layer)
            } catch (e: IOException) {
                setSync(layer.id, LayerSyncState.Error(e.toProblem()))
                return@launch
            }
            val outcome = layerSync.sync(layer, features) { sent, total ->
                setSync(layer.id, LayerSyncState.Syncing(sent, total))
            }
            if (outcome is SyncOutcome.Done) {
                val osmandId = PointMapping.osmandLayerId(layer)
                syncedPoints[osmandId] = features.associateBy { it.id }
                // Optional extra; a failure here must not mark the layer as failed.
                if (settings.osmandReportButton) reportButton.add(osmandId, localizedString(R.string.report_button_osmand))
            }
            setSync(layer.id, when (outcome) {
                is SyncOutcome.Done -> LayerSyncState.Synced(outcome.points, System.currentTimeMillis())
                is SyncOutcome.Rejected -> LayerSyncState.Error(Problem.PermissionDenied)
                is SyncOutcome.Failed -> LayerSyncState.Error(outcome.result.toProblem())
            })
        }
    }

    fun openInOsmAnd() {
        val pkg = _state.value.selectedPackage ?: return showNotice(Problem.OsmAndNotInstalled)
        viewModelScope.launch {
            // Centre on Syria if allowed; opening OsmAnd works even without the permission.
            if (_state.value.connection is ConnectionState.Connected) layerSync.moveMap(SYRIA_LAT, SYRIA_LON, SYRIA_ZOOM)
            OsmAndApps.launchIntent(getApplication(), pkg)?.let { _intents.value = it }
                ?: showNotice(Problem.OsmAndNotInstalled)
        }
    }

    fun installOsmAnd() {
        _intents.value = OsmAndApps.storeIntent()
    }

    fun requestPermission() {
        val pkg = _state.value.selectedPackage ?: return showNotice(Problem.OsmAndNotInstalled)
        if (OsmAndApps.supportsPermissionRequest(getApplication(), pkg)) {
            _intents.value = OsmAndApps.permissionRequestIntent(pkg)
        } else {
            showNotice(Problem.PermissionScreenMissing)
        }
    }

    /** Result of OsmAnd's permission screen: retry every enabled layer. */
    fun onPermissionResult() = resyncEnabled()

    fun installOsf() {
        val pkg = _state.value.selectedPackage ?: return showNotice(Problem.OsmAndNotInstalled)
        viewModelScope.launch {
            _state.update { it.copy(osfBusy = true) }
            try {
                val file = api().downloadOsf(OsfInstaller.packageFile(getApplication()))
                _intents.value = OsfInstaller.importIntent(getApplication(), file, pkg)
            } catch (_: IOException) {
                showNotice(Problem.OsfDownloadFailed)
            } finally {
                _state.update { it.copy(osfBusy = false) }
            }
        }
    }

    fun intentHandled() {
        _intents.value = null
    }

    /** Fallback when an intent cannot be resolved (e.g. no Play Store app). */
    fun intentFailed(intent: Intent) {
        _intents.value = if (intent.data?.scheme == "market") OsmAndApps.storeWebIntent() else null
        if (intent.data?.scheme != "market") showNotice(Problem.OsmAndNotInstalled)
    }

    /* ---------- Reports (OSM notes) ---------- */

    fun openReport(location: ReportLocation? = null, currentName: String = "", fromOutside: Boolean = false) {
        _state.update {
            it.copy(
                report = ReportDraft(location = location, currentName = currentName),
                reportStatus = ReportStatus.Editing,
                reportFromOutside = fromOutside,
            )
        }
    }

    fun updateReport(draft: ReportDraft) = _state.update {
        val keep = it.reportStatus is ReportStatus.Sending || it.reportStatus is ReportStatus.Locating
        it.copy(report = draft, reportStatus = if (keep) it.reportStatus else ReportStatus.Editing)
    }

    fun closeReport() = _state.update { it.copy(report = null, reportStatus = ReportStatus.Editing, reportFromOutside = false) }

    fun submitReport() {
        val draft = _state.value.report ?: return
        val loc = draft.location ?: return
        val text = NoteText.build(draft) ?: return
        if (_state.value.reportStatus is ReportStatus.Sending) return
        viewModelScope.launch {
            _state.update { it.copy(reportStatus = ReportStatus.Sending) }
            val status = try {
                val id = notes.create(loc.latitude, loc.longitude, text)
                ReportStatus.Sent(id, notes.noteUrl(id))
            } catch (_: IOException) {
                ReportStatus.Failed
            } catch (_: org.json.JSONException) {
                ReportStatus.Failed
            }
            _state.update { it.copy(reportStatus = status) }
        }
    }

    /** Called after the location permission was granted. Uses one fresh fix, never tracks. */
    @SuppressLint("MissingPermission")
    fun useDeviceLocation() {
        val ctx = getApplication<Application>()
        val granted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        _state.update { it.copy(reportStatus = ReportStatus.Locating) }
        val deliver: (Location?) -> Unit = { loc ->
            _state.update { s ->
                val draft = s.report
                when {
                    draft == null -> s
                    loc == null -> s.copy(reportStatus = ReportStatus.NoLocationFix)
                    else -> s.copy(
                        report = draft.copy(location = ReportLocation(loc.latitude, loc.longitude, ReportLocation.Source.DEVICE_GPS)),
                        reportStatus = ReportStatus.Editing,
                    )
                }
            }
        }
        if (provider == null) return deliver(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { deliver(it) }
        } else {
            deliver(lm.getLastKnownLocation(provider))
        }
    }

    /** OsmAnd's context-menu button on one of our points. */
    private fun onOsmAndPointReport(osmandLayerId: String, pointId: String) {
        val f = syncedPoints[osmandLayerId]?.get(pointId) ?: return
        val lang = _state.value.language
        val name = (if (lang == "ar") f.nameAr ?: f.nameEn else f.nameEn ?: f.nameAr).orEmpty()
        openReport(ReportLocation(f.latitude, f.longitude, ReportLocation.Source.OUR_POINT), name, fromOutside = true)
        // We are in the background while the user looks at OsmAnd. Android 10+ blocks background activity
        // starts (seen on Android 17: BAL_BLOCK), so a notification opens the prepared report on tap.
        ReportNotification.show(getApplication(), name, localizedString(R.string.report_notification_title),
            localizedString(R.string.report_notification_text))
    }

    /** Opt-in for the OsmAnd button. The activity asks for the notification permission first. */
    fun setOsmandReportButton(enabled: Boolean) {
        settings.osmandReportButton = enabled
        _state.update { it.copy(osmandReportButton = enabled) }
        viewModelScope.launch {
            if (enabled) resyncEnabled() else reportButton.removeAll()
        }
    }

    private fun localizedString(id: Int): String {
        val app = getApplication<Application>()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(_state.value.language)) }
        return app.createConfigurationContext(config).getString(id)
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun showNotice(p: Problem) = _state.update { it.copy(notice = p) }

    private fun setSync(id: String, s: LayerSyncState) = _state.update { it.copy(sync = it.sync + (id to s)) }

    private fun connectionProblem(): Problem = when {
        _state.value.installed.isEmpty() -> Problem.OsmAndNotInstalled
        _state.value.connection is ConnectionState.Unsupported -> Problem.OsmAndUnsupported
        else -> Problem.OsmAndNotConnected
    }

    private fun AidlResult<*>.toProblem(): Problem = when (this) {
        is AidlResult.Ok -> Problem.AidlFailed("?", null)
        AidlResult.NotConnected -> connectionProblem()
        is AidlResult.Security -> Problem.PermissionDenied
        is AidlResult.Remote -> Problem.AidlFailed(method, message)
    }

    private fun IOException.toProblem(): Problem =
        if (this is io.github.caesarde.syriamaps.data.ApiException && httpCode != null) Problem.ServerHttp(httpCode)
        else Problem.ServerUnreachable

    override fun onCleared() {
        connection.disconnect()
    }

    companion object {
        const val SYRIA_LAT = 34.8
        const val SYRIA_LON = 38.5
        const val SYRIA_ZOOM = 7
    }
}
