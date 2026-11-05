package io.github.caesarde.syriamaps

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.caesarde.syriamaps.data.ApiClient
import io.github.caesarde.syriamaps.data.Integration
import io.github.caesarde.syriamaps.data.LayerInfo
import io.github.caesarde.syriamaps.data.SettingsStore
import io.github.caesarde.syriamaps.osmand.AidlResult
import io.github.caesarde.syriamaps.osmand.ConnectionState
import io.github.caesarde.syriamaps.osmand.InstalledOsmAnd
import io.github.caesarde.syriamaps.osmand.LayerSync
import io.github.caesarde.syriamaps.osmand.OsfInstaller
import io.github.caesarde.syriamaps.osmand.OsmAndApps
import io.github.caesarde.syriamaps.osmand.OsmAndConnection
import io.github.caesarde.syriamaps.osmand.SyncOutcome
import java.io.IOException
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

/** User-facing problems; the UI maps them to Arabic strings. */
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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val connection = OsmAndConnection(app)
    private val layerSync = LayerSync(connection)
    private val syncJobs = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow(UiState(serverUrl = settings.serverUrl, enabled = settings.enabledLayers))
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
            if (c !is ConnectionState.Connected || c.packageName != chosen) connection.connect(chosen)
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
