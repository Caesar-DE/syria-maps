package io.github.caesarde.syriamaps.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

@Composable
fun MainScreen(state: UiState, vm: MainViewModel) {
    val c = LocalInk.current
    Box(
        Modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        ScribbleBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Hero(state)
            Appear(1) { OsmAndCard(state, vm) }
            Appear(2) { PointLayersCard(state, vm) }
            Appear(3) { RasterCard(state, vm) }
            Appear(4) { OtherLayersCard(state) }
            Appear(5) { ServerCard(state, vm) }
            Spacer(Modifier.height(8.dp))
        }
    }
    state.notice?.let { problem ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            containerColor = c.paper,
            shape = RoundedCornerShape(24.dp),
            icon = { IconBadge(InkGlyph.Warning, fill = c.bad.copy(alpha = 0.85f)) },
            confirmButton = {
                InkButton(stringResource(R.string.ok), vm::dismissNotice, Modifier.width(140.dp))
            },
            text = { Text(problemText(problem), color = c.text) },
        )
    }
}

/* ---------- Header ---------- */

@Composable
private fun Hero(state: UiState) {
    val c = LocalInk.current
    val totalPoints = state.sync.values.sumOf { (it as? LayerSyncState.Synced)?.points ?: 0 }
    val activeLayers = state.enabled.size

    // Entrance: title rises in, pin drops with a bounce, then floats gently.
    val enter = remember { Animatable(0f) }
    val drop = remember { Animatable(-220f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        drop.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessLow))
    }
    val t = rememberInfiniteTransition(label = "float")
    val float by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float",
    )

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.BottomCenter) {
                // Ground shadow shrinks as the pin floats up.
                Canvas(Modifier.size(width = 46.dp, height = 10.dp)) {
                    val s = 1f - 0.25f * float
                    drawOval(
                        c.ink.copy(alpha = 0.25f),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * (1 - s) / 2, 0f),
                        size = androidx.compose.ui.geometry.Size(size.width * s, size.height),
                    )
                }
                InkIcon(
                    InkGlyph.Pin,
                    size = 72.dp,
                    ink = Palette.Ink,
                    fill = if (c.dark) Palette.Yellow else Palette.Cream,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .graphicsLayer {
                            translationY = drop.value.dp.toPx() * 0.4f - float * 7.dp.toPx()
                            rotationZ = (float - 0.5f) * 6f
                        },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 24.dp.toPx()
                },
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = c.text,
                )
                Text(
                    stringResource(R.string.hero_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(
            Modifier.graphicsLayer { alpha = enter.value },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (label, color, pulsing) = when {
                state.installed.isEmpty() -> Triple(stringResource(R.string.pill_missing), c.bad, false)
                state.connection is ConnectionState.Connected -> Triple(stringResource(R.string.pill_connected), c.ok, true)
                state.connection == ConnectionState.Connecting -> Triple(stringResource(R.string.pill_connecting), Palette.Brown, true)
                else -> Triple(stringResource(R.string.pill_offline), c.bad, false)
            }
            AnimatedContent(
                targetState = Triple(label, color, pulsing),
                transitionSpec = {
                    (fadeIn(tween(300)) + scaleIn(initialScale = 0.85f)) togetherWith fadeOut(tween(200))
                },
                label = "pill",
            ) { (l, col, p) -> StatusPill(l, col, p) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(totalPoints, stringResource(R.string.hero_points), InkGlyph.Spark, Modifier.weight(1f))
            StatTile(activeLayers, stringResource(R.string.hero_layers), InkGlyph.Layers, Modifier.weight(1f))
        }
    }
}

/** Number tile that counts up/down smoothly when the value changes. */
@Composable
private fun StatTile(value: Int, label: String, glyph: InkGlyph, modifier: Modifier = Modifier) {
    val c = LocalInk.current
    val shown by animateIntAsState(value, tween(900, easing = FastOutSlowInEasing), label = "count")
    Row(
        modifier
            .inkSurface(c.paper, c.ink, radius = 18.dp, offset = 4.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(glyph, size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("%,d".format(shown), style = MaterialTheme.typography.titleLarge, color = c.text)
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

/* ---------- Cards ---------- */

@Composable
private fun OsmAndCard(state: UiState, vm: MainViewModel) {
    val c = LocalInk.current
    InkCard(stringResource(R.string.section_osmand), InkGlyph.Navigate) {
        if (state.installed.isEmpty()) {
            Text(stringResource(R.string.osmand_not_installed), color = c.text)
            InkButton(stringResource(R.string.install_osmand), vm::installOsmAnd, Modifier.fillMaxWidth(), InkGlyph.Store)
            return@InkCard
        }
        if (state.installed.size > 1) {
            Text(stringResource(R.string.choose_osmand), style = MaterialTheme.typography.labelLarge, color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.installed.forEach { app ->
                    InkChip(app.label, app.packageName == state.selectedPackage) { vm.selectPackage(app.packageName) }
                }
            }
        }
        state.installed.firstOrNull { it.packageName == state.selectedPackage }?.let {
            Text(stringResource(R.string.osmand_installed, it.label, it.versionName), color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        val connected = state.connection is ConnectionState.Connected
        AnimatedContent(
            targetState = state.connection,
            transitionSpec = {
                (fadeIn(tween(280)) + slideInVertically { it / 2 }) togetherWith
                    (fadeOut(tween(180)) + slideOutVertically { -it / 2 })
            },
            label = "connection",
        ) { conn ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(if (conn is ConnectionState.Connected) c.ok else c.bad, conn is ConnectionState.Connected)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (conn) {
                        is ConnectionState.Connected -> stringResource(R.string.state_connected)
                        ConnectionState.Connecting -> stringResource(R.string.state_connecting)
                        ConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
                        is ConnectionState.Unsupported -> stringResource(R.string.state_unsupported)
                        ConnectionState.Idle -> stringResource(R.string.state_idle)
                    },
                    color = if (conn is ConnectionState.Connected) c.ok else c.bad,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        InkButton(
            stringResource(R.string.open_osmand), vm::openInOsmAnd, Modifier.fillMaxWidth(), InkGlyph.Navigate,
            style = if (connected) InkButtonStyle.Primary else InkButtonStyle.Secondary,
        )
        val denied = state.sync.values.any { it is LayerSyncState.Error && it.problem == Problem.PermissionDenied }
        AnimatedVisibility(denied, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            InkButton(
                stringResource(R.string.grant_permission), vm::requestPermission, Modifier.fillMaxWidth(), InkGlyph.Shield,
            )
        }
    }
}

@Composable
private fun InkChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalInk.current
    val bg by androidx.compose.animation.animateColorAsState(if (selected) Palette.Yellow else c.paper, tween(250), label = "chip")
    Box(
        Modifier
            .background(bg, CircleShape)
            .border(2.dp, if (selected) Palette.Ink else c.ink, CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (selected) Palette.Ink else c.text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PointLayersCard(state: UiState, vm: MainViewModel) {
    val c = LocalInk.current
    val layers = state.layers.filter { it.integration == Integration.POINTS_AIDL }
    InkCard(stringResource(R.string.section_points), InkGlyph.Layers) {
        Text(stringResource(R.string.points_explain), style = MaterialTheme.typography.bodySmall, color = c.muted)
        AnimatedContent(
            targetState = when {
                state.loadingLayers -> 0
                state.layersProblem != null -> 1
                layers.isEmpty() -> 2
                else -> 3
            },
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
            label = "layers",
        ) { mode ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        InkSpinner()
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.loading_layers), color = c.text)
                    }
                    1 -> {
                        state.layersProblem?.let {
                            ProblemLine(problemText(it))
                        }
                        if (state.serverUrl == BuildConfig.DEFAULT_SERVER_URL && "example.org" in state.serverUrl) {
                            Text(stringResource(R.string.placeholder_server), color = c.text, style = MaterialTheme.typography.bodySmall)
                        }
                        InkButton(stringResource(R.string.retry), vm::loadLayers, Modifier.fillMaxWidth(), InkGlyph.Sync, InkButtonStyle.Secondary)
                    }
                    2 -> Text(stringResource(R.string.no_point_layers), color = c.text)
                    else -> layers.forEachIndexed { i, layer ->
                        Appear(i) {
                            PointLayerRow(layer, layer.id in state.enabled, state.sync[layer.id] ?: LayerSyncState.Off) {
                                vm.setLayerEnabled(layer, it)
                            }
                        }
                    }
                }
            }
        }
        if (layers.isNotEmpty()) {
            val syncing = state.sync.values.any { it is LayerSyncState.Syncing }
            val spin = remember { Animatable(0f) }
            LaunchedEffect(syncing) {
                if (syncing) {
                    while (true) {
                        spin.animateTo(spin.value + 360f, tween(900, easing = LinearEasing))
                    }
                } else {
                    // Finish the turn smoothly instead of snapping back.
                    val target = (kotlin.math.ceil(spin.value / 360f) * 360f)
                    spin.animateTo(target, spring(dampingRatio = 0.5f))
                }
            }
            InkButton(
                stringResource(R.string.sync_now), vm::syncNow, Modifier.fillMaxWidth(), InkGlyph.Sync,
                iconRotation = spin.value,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointLayerRow(layer: LayerInfo, enabled: Boolean, sync: LayerSyncState, onToggle: (Boolean) -> Unit) {
    val c = LocalInk.current
    val haptics = LocalHapticFeedback.current
    val pinColor = layer.kinds.values.firstOrNull()?.let { Color(it.color) } ?: Palette.Yellow
    val pop = remember { Animatable(1f) }
    LaunchedEffect(enabled) {
        pop.snapTo(0.7f)
        pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
    }
    val lift by animateFloatAsState(if (enabled) 1f else 0f, spring(dampingRatio = 0.6f), label = "lift")
    val count by animateIntAsState(layer.featureCount, tween(1200, easing = FastOutSlowInEasing), label = "count")

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (c.dark) Palette.Graphite.copy(alpha = 0.5f + 0.5f * lift) else Palette.Sand.copy(alpha = 0.35f + 0.45f * lift),
                RoundedCornerShape(16.dp),
            )
            .border(1.5.dp, c.ink.copy(alpha = 0.25f + 0.75f * lift), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                        translationY = -lift * 3.dp.toPx()
                    },
                contentAlignment = Alignment.Center,
            ) {
                InkIcon(InkGlyph.Pin, size = 38.dp, ink = c.ink, fill = if (enabled) pinColor else pinColor.copy(alpha = 0.35f))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(layer.titleAr, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = c.text)
                Text(
                    buildString {
                        append(stringResource(R.string.layer_points_count, count))
                        if (layer.synthetic) append(" · ").append(stringResource(R.string.layer_synthetic))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(it)
                },
                colors = inkSwitchColors(),
            )
        }
        if (layer.kinds.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                layer.kinds.values.take(8).forEach { kind ->
                    Row(
                        Modifier
                            .background(c.paper, CircleShape)
                            .border(1.dp, c.ink.copy(alpha = 0.35f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(Color(kind.color), CircleShape).border(1.dp, Palette.Ink, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text(kind.labelAr, style = MaterialTheme.typography.labelSmall, color = c.text)
                    }
                }
            }
        }
        AnimatedContent(
            targetState = sync,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(160))
            },
            label = "sync",
        ) { s ->
            when (s) {
                LayerSyncState.Off -> if (!enabled) {
                    Text(stringResource(R.string.sync_off), style = MaterialTheme.typography.bodySmall, color = c.muted)
                } else Spacer(Modifier.height(0.dp))
                is LayerSyncState.Syncing -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.sync_running, s.sent, s.total), style = MaterialTheme.typography.bodySmall, color = c.text)
                    InkProgress(if (s.total > 0) s.sent.toFloat() / s.total else 0f)
                }
                is LayerSyncState.Synced -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val pop2 = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { pop2.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow)) }
                    InkIcon(
                        InkGlyph.Check, size = 20.dp, ink = Palette.Ink, fill = c.ok,
                        modifier = Modifier.graphicsLayer { scaleX = pop2.value; scaleY = pop2.value },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.sync_done, s.points),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.ok,
                        fontWeight = FontWeight.Bold,
                    )
                }
                is LayerSyncState.Error -> ProblemLine(problemText(s.problem))
            }
        }
    }
}

@Composable
private fun ProblemLine(text: String) {
    val c = LocalInk.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(text) {
        for (x in listOf(10f, -8f, 6f, -4f, 0f)) shake.animateTo(x, tween(60))
    }
    Row(
        Modifier
            .graphicsLayer { translationX = shake.value }
            .fillMaxWidth()
            .background(c.bad.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        InkIcon(InkGlyph.Warning, size = 20.dp, ink = Palette.Ink, fill = c.bad)
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.bad, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

/** Three ink dots bouncing in sequence. */
@Composable
private fun InkSpinner() {
    val c = LocalInk.current
    val t = rememberInfiniteTransition(label = "spinner")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val d = ((p - i + 3f) % 3f)
            val up = if (d < 1f) kotlin.math.sin(d * Math.PI).toFloat() else 0f
            Box(
                Modifier
                    .offset(y = (-6 * up).dp)
                    .size(9.dp)
                    .background(if (i == 1) c.ink else Palette.Yellow, CircleShape)
                    .border(1.5.dp, c.ink, CircleShape),
            )
        }
    }
}

@Composable
private fun RasterCard(state: UiState, vm: MainViewModel) {
    val c = LocalInk.current
    val raster = state.layers.filter { it.alsoRaster || it.integration == Integration.RASTER_OSF }
    if (raster.isEmpty()) return
    InkCard(stringResource(R.string.section_raster), InkGlyph.Raster) {
        Text(stringResource(R.string.raster_explain), style = MaterialTheme.typography.bodySmall, color = c.muted)
        raster.forEach { BulletLine(it.titleAr, InkGlyph.Raster) }
        InkButton(
            stringResource(if (state.osfBusy) R.string.osf_downloading else R.string.install_osf),
            vm::installOsf,
            Modifier.fillMaxWidth(),
            InkGlyph.Download,
            enabled = !state.osfBusy && state.installed.isNotEmpty(),
        )
        AnimatedVisibility(state.osfBusy) { InkProgressIndeterminate() }
        Text(stringResource(R.string.osf_consent), style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
private fun InkProgressIndeterminate() {
    val t = rememberInfiniteTransition(label = "indeterminate")
    val p by t.animateFloat(0.15f, 0.95f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    InkProgress(p)
}

@Composable
private fun BulletLine(text: String, glyph: InkGlyph, tag: String? = null) {
    val c = LocalInk.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        InkIcon(glyph, size = 20.dp, ink = c.ink, fill = Palette.Yellow.copy(alpha = if (c.dark) 0.25f else 0.9f))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.text, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        if (tag != null) {
            Text(
                tag,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Ink,
                modifier = Modifier
                    .background(Palette.Yellow, CircleShape)
                    .border(1.dp, Palette.Ink, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun OtherLayersCard(state: UiState) {
    val c = LocalInk.current
    val other = state.layers.filter { it.integration == Integration.PLANNED || it.integration == Integration.NOTICES }
    if (other.isEmpty()) return
    InkCard(stringResource(R.string.section_other), InkGlyph.Cube) {
        Text(stringResource(R.string.other_explain), style = MaterialTheme.typography.bodySmall, color = c.muted)
        other.forEach { layer ->
            val (tag, glyph) = when {
                layer.integration == Integration.NOTICES -> stringResource(R.string.integration_notices) to InkGlyph.Warning
                layer.category == "base" || layer.category == "buildings" -> stringResource(R.string.integration_vector) to InkGlyph.Cube
                else -> stringResource(R.string.integration_planned) to InkGlyph.Spark
            }
            BulletLine(layer.titleAr, glyph, tag)
        }
    }
}

@Composable
private fun ServerCard(state: UiState, vm: MainViewModel) {
    val c = LocalInk.current
    var text by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    InkCard(stringResource(R.string.section_server), InkGlyph.Server) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.server_url)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.ink,
                unfocusedBorderColor = c.ink.copy(alpha = 0.5f),
                focusedLabelColor = c.text,
                unfocusedLabelColor = c.muted,
                cursorColor = c.ink,
                focusedTextColor = c.text,
                unfocusedTextColor = c.text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        InkButton(
            stringResource(R.string.save), { vm.setServerUrl(text) }, Modifier.fillMaxWidth(), InkGlyph.Check,
            style = InkButtonStyle.Secondary,
            enabled = text.isNotBlank() && text != state.serverUrl,
        )
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
