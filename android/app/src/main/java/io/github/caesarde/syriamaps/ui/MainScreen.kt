package io.github.caesarde.syriamaps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.caesarde.syriamaps.BuildConfig
import io.github.caesarde.syriamaps.LayerSyncState
import io.github.caesarde.syriamaps.MainViewModel
import io.github.caesarde.syriamaps.Problem
import io.github.caesarde.syriamaps.R
import io.github.caesarde.syriamaps.UiState
import io.github.caesarde.syriamaps.data.Integration
import io.github.caesarde.syriamaps.data.LayerInfo
import io.github.caesarde.syriamaps.osmand.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: UiState, vm: MainViewModel) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OsmAndCard(state, vm)
            PointLayersCard(state, vm)
            RasterCard(state, vm)
            OtherLayersCard(state)
            ServerCard(state, vm)
        }
    }
    state.notice?.let { problem ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.ok)) } },
            text = { Text(problemText(problem)) },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun OsmAndCard(state: UiState, vm: MainViewModel) {
    Section(stringResource(R.string.section_osmand)) {
        if (state.installed.isEmpty()) {
            Text(stringResource(R.string.osmand_not_installed))
            Button(onClick = vm::installOsmAnd, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.install_osmand))
            }
            return@Section
        }
        if (state.installed.size > 1) {
            Text(stringResource(R.string.choose_osmand), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.installed.forEach { app ->
                    FilterChip(
                        selected = app.packageName == state.selectedPackage,
                        onClick = { vm.selectPackage(app.packageName) },
                        label = { Text(app.label) },
                    )
                }
            }
        }
        state.installed.firstOrNull { it.packageName == state.selectedPackage }?.let {
            Text(stringResource(R.string.osmand_installed, it.label, it.versionName))
        }
        Text(
            when (state.connection) {
                is ConnectionState.Connected -> stringResource(R.string.state_connected)
                ConnectionState.Connecting -> stringResource(R.string.state_connecting)
                ConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
                is ConnectionState.Unsupported -> stringResource(R.string.state_unsupported)
                ConnectionState.Idle -> stringResource(R.string.state_idle)
            },
            color = if (state.connection is ConnectionState.Connected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Button(onClick = vm::openInOsmAnd, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_osmand))
        }
        val denied = state.sync.values.any { it is LayerSyncState.Error && it.problem == Problem.PermissionDenied }
        if (denied) {
            OutlinedButton(onClick = vm::requestPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.grant_permission))
            }
        }
    }
}

@Composable
private fun PointLayersCard(state: UiState, vm: MainViewModel) {
    val layers = state.layers.filter { it.integration == Integration.POINTS_AIDL }
    Section(stringResource(R.string.section_points)) {
        Text(stringResource(R.string.points_explain), style = MaterialTheme.typography.bodySmall)
        when {
            state.loadingLayers -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.loading_layers))
            }
            state.layersProblem != null -> {
                Text(problemText(state.layersProblem), color = MaterialTheme.colorScheme.error)
                if (state.serverUrl == BuildConfig.DEFAULT_SERVER_URL && "example.org" in state.serverUrl) {
                    Text(stringResource(R.string.placeholder_server))
                }
                OutlinedButton(onClick = vm::loadLayers) { Text(stringResource(R.string.retry)) }
            }
            layers.isEmpty() -> Text(stringResource(R.string.no_point_layers))
            else -> layers.forEach { layer ->
                PointLayerRow(layer, layer.id in state.enabled, state.sync[layer.id] ?: LayerSyncState.Off) {
                    vm.setLayerEnabled(layer, it)
                }
            }
        }
        if (layers.isNotEmpty()) {
            OutlinedButton(onClick = vm::syncNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sync_now))
            }
        }
    }
}

@Composable
private fun PointLayerRow(layer: LayerInfo, enabled: Boolean, sync: LayerSyncState, onToggle: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(layer.titleAr, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(stringResource(R.string.layer_points_count, layer.featureCount))
                        if (layer.synthetic) append(" · ").append(stringResource(R.string.layer_synthetic))
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        when (sync) {
            LayerSyncState.Off -> if (!enabled) Text(stringResource(R.string.sync_off), style = MaterialTheme.typography.bodySmall)
            is LayerSyncState.Syncing -> {
                Text(stringResource(R.string.sync_running, sync.sent, sync.total), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { if (sync.total > 0) sync.sent.toFloat() / sync.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is LayerSyncState.Synced -> Text(
                stringResource(R.string.sync_done, sync.points),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is LayerSyncState.Error -> Text(
                problemText(sync.problem),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RasterCard(state: UiState, vm: MainViewModel) {
    val raster = state.layers.filter { it.alsoRaster || it.integration == Integration.RASTER_OSF }
    if (raster.isEmpty()) return
    Section(stringResource(R.string.section_raster)) {
        Text(stringResource(R.string.raster_explain), style = MaterialTheme.typography.bodySmall)
        raster.forEach { Text("• ${it.titleAr}") }
        Button(
            onClick = vm::installOsf,
            enabled = !state.osfBusy && state.installed.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (state.osfBusy) R.string.osf_downloading else R.string.install_osf))
        }
        Text(stringResource(R.string.osf_consent), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OtherLayersCard(state: UiState) {
    val other = state.layers.filter { it.integration == Integration.PLANNED || it.integration == Integration.NOTICES }
    if (other.isEmpty()) return
    Section(stringResource(R.string.section_other)) {
        Text(stringResource(R.string.other_explain), style = MaterialTheme.typography.bodySmall)
        other.forEach { layer ->
            val tag = when {
                layer.integration == Integration.NOTICES -> stringResource(R.string.integration_notices)
                layer.category == "base" || layer.category == "buildings" -> stringResource(R.string.integration_vector)
                else -> stringResource(R.string.integration_planned)
            }
            Text("• ${layer.titleAr} — $tag")
        }
    }
}

@Composable
private fun ServerCard(state: UiState, vm: MainViewModel) {
    var text by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    Section(stringResource(R.string.section_server)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.server_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { vm.setServerUrl(text) }, enabled = text.isNotBlank()) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun problemText(p: Problem): String = when (p) {
    Problem.ServerUnreachable -> stringResource(R.string.problem_server_unreachable)
    is Problem.ServerHttp -> stringResource(R.string.problem_server_http, p.code)
    Problem.OsmAndNotInstalled -> stringResource(R.string.problem_osmand_not_installed)
    Problem.OsmAndNotConnected -> stringResource(R.string.problem_osmand_not_connected)
    Problem.OsmAndUnsupported -> stringResource(R.string.problem_osmand_unsupported)
    Problem.PermissionDenied -> stringResource(R.string.problem_permission)
    is Problem.AidlFailed -> stringResource(R.string.problem_aidl, p.method)
    Problem.OsfDownloadFailed -> stringResource(R.string.problem_osf_download)
    Problem.PermissionScreenMissing -> stringResource(R.string.problem_permission_screen_missing)
}
