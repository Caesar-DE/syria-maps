package io.github.caesarde.syriamaps.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caesarde.syriamaps.BuildConfig
import io.github.caesarde.syriamaps.MainViewModel
import io.github.caesarde.syriamaps.R
import io.github.caesarde.syriamaps.ServerProbe
import io.github.caesarde.syriamaps.UiState
import io.github.caesarde.syriamaps.data.SettingsStore
import io.github.caesarde.syriamaps.data.ThemeMode
import io.github.caesarde.syriamaps.osmand.ConnectionState
import kotlin.math.cos
import kotlin.math.sin

private const val STEPS = 5

/**
 * First-run setup: language, theme, server, OsmAnd check, done. Each choice applies immediately
 * (language and theme morph live), so the user sees the result while choosing.
 */
@Composable
fun SetupWizard(state: UiState, vm: MainViewModel) {
    val c = LocalInk.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var serverText by rememberSaveable { mutableStateOf(state.serverUrl) }
    val probe by vm.probe.collectAsStateWithLifecycle()

    fun next() {
        if (step == 2 && SettingsStore.normalizeServerUrl(serverText) != state.serverUrl && serverText.isNotBlank()) {
            vm.setServerUrl(serverText)
        }
        if (step < STEPS - 1) step++ else vm.finishSetup()
    }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        ScribbleBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            // Top: progress pills + skip.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                StepDots(step)
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(step < STEPS - 1, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        stringResource(R.string.wizard_skip),
                        color = c.muted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { vm.finishSetup() }
                            .padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val forward = targetState > initialState
                    // "Forward" moves toward the reading direction's end: left-to-right in Latin, right-to-left in Arabic.
                    val fromEnd = forward != rtl
                    (slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)) { w -> if (fromEnd) w else -w } +
                        fadeIn(tween(350))) togetherWith
                        (slideOutHorizontally(tween(320)) { w -> if (fromEnd) -w / 3 else w / 3 } + fadeOut(tween(220)))
                },
                label = "wizard",
            ) { s ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (s) {
                        0 -> LanguageStep(state.language, vm::setLanguage)
                        1 -> ThemeStep(state.themeMode, vm::setThemeMode)
                        2 -> ServerStep(serverText, { serverText = it; vm.resetProbe() }, probe) { vm.probeServer(serverText) }
                        3 -> OsmAndStep(state, vm)
                        else -> DoneStep()
                    }
                }
            }

            // Bottom navigation.
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(step > 0, modifier = Modifier.weight(1f)) {
                    InkButton(
                        stringResource(R.string.wizard_back), { step-- }, Modifier.fillMaxWidth(),
                        style = InkButtonStyle.Secondary,
                    )
                }
                InkButton(
                    stringResource(if (step == STEPS - 1) R.string.wizard_finish else R.string.wizard_next),
                    ::next,
                    Modifier.weight(if (step > 0) 1.4f else 1f),
                    glyph = if (step == STEPS - 1) InkGlyph.Navigate else InkGlyph.Arrow,
                    mirrorIcon = rtl && step != STEPS - 1,
                )
            }
        }
    }
}

/** Progress: past steps as small ink dots, the current one as a stretched yellow pill. */
@Composable
private fun StepDots(step: Int) {
    val c = LocalInk.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(STEPS) { i ->
            val width by animateDpAsState(if (i == step) 30.dp else 10.dp, spring(dampingRatio = 0.6f), label = "dotW")
            val fill by animateColorAsState(
                when {
                    i == step -> if (c.dark) Palette.Yellow else Palette.Ink
                    i < step -> c.ink.copy(alpha = 0.45f)
                    else -> c.paper
                },
                tween(300), label = "dotC",
            )
            Box(
                Modifier
                    .size(width = width, height = 10.dp)
                    .background(fill, CircleShape)
                    .border(1.5.dp, if (i == step) Palette.Ink else c.ink, CircleShape),
            )
        }
    }
}

/** Big bouncing badge, title and one-line explanation at the top of every step. */
@Composable
private fun StepHeader(glyph: InkGlyph, title: String, subtitle: String) {
    val c = LocalInk.current
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow)) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconBadge(glyph, size = 68.dp, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.muted)
    }
}

/** A large selectable card: lifts and turns yellow when chosen, with a check that pops in. */
@Composable
private fun ChoiceCard(
    selected: Boolean,
    onClick: () -> Unit,
    index: Int,
    content: @Composable () -> Unit,
) {
    val c = LocalInk.current
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (selected) Palette.Yellow else c.paper, tween(300), label = "bg")
    val lift by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.55f), label = "lift")
    Appear(index) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = -lift * 3.dp.toPx()
                    val s = 1f + 0.015f * lift
                    scaleX = s
                    scaleY = s
                }
                .inkSurface(bg, if (selected) Palette.Ink else c.ink, shadow = if (selected) Palette.Ink else c.shadow, radius = 20.dp, offset = (2 + 4 * lift).dp)
                .clickable(remember { MutableInteractionSource() }, null, role = Role.RadioButton) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { content() }
                AnimatedVisibility(selected, enter = scaleIn(spring(dampingRatio = 0.4f)) + fadeIn(), exit = fadeOut()) {
                    InkIcon(InkGlyph.Check, size = 28.dp, ink = Palette.Ink, fill = Palette.Cream)
                }
            }
        }
    }
}

/* ---------- Step 1: language ---------- */

@Composable
private fun LanguageStep(current: String, onPick: (String) -> Unit) {
    StepHeader(InkGlyph.Globe, stringResource(R.string.wizard_lang_title), stringResource(R.string.wizard_lang_sub))
    // Language names are always shown in their own language, so everyone finds theirs.
    listOf(
        Triple("ar", "العربية", "Arabic · Arabisch"),
        Triple("de", "Deutsch", "German · ألمانية"),
        Triple("en", "English", "Englisch · إنكليزية"),
    ).forEachIndexed { i, (code, name, hint) ->
        val selected = code == current
        ChoiceCard(selected, { onPick(code) }, i) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(if (selected) Palette.Ink else Palette.Yellow, CircleShape)
                        .border(2.dp, Palette.Ink, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        code.uppercase(),
                        color = if (selected) Palette.Yellow else Palette.Ink,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (selected) Palette.Ink else LocalInk.current.text,
                    )
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = if (selected) Palette.Brown else LocalInk.current.muted)
                }
            }
        }
    }
}

/* ---------- Step 2: theme ---------- */

@Composable
private fun ThemeStep(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    StepHeader(InkGlyph.Sun, stringResource(R.string.wizard_theme_title), stringResource(R.string.wizard_theme_sub))
    listOf(
        Triple(ThemeMode.LIGHT, InkGlyph.Sun, R.string.theme_light),
        Triple(ThemeMode.DARK, InkGlyph.Moon, R.string.theme_dark),
        Triple(ThemeMode.SYSTEM, InkGlyph.Phone, R.string.theme_system),
    ).forEachIndexed { i, (mode, glyph, label) ->
        val selected = mode == current
        ChoiceCard(selected, { onPick(mode) }, i) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemeSwatch(mode)
                Spacer(Modifier.width(14.dp))
                InkIcon(glyph, size = 26.dp, ink = if (selected) Palette.Ink else LocalInk.current.ink, fill = Palette.Cream)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) Palette.Ink else LocalInk.current.text,
                )
            }
        }
    }
}

/** Tiny phone-screen preview of a theme (system = split diagonally). */
@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    Canvas(
        Modifier
            .size(width = 38.dp, height = 52.dp)
            .border(2.dp, Palette.Ink, RoundedCornerShape(8.dp))
            .padding(2.dp),
    ) {
        val r = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
        fun screen(bg: Color, card: Color) {
            drawRoundRect(bg, cornerRadius = r)
            drawRoundRect(card, topLeft = Offset(size.width * 0.18f, size.height * 0.3f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.64f, size.height * 0.22f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            drawRoundRect(card, topLeft = Offset(size.width * 0.18f, size.height * 0.6f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.64f, size.height * 0.22f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
        when (mode) {
            ThemeMode.LIGHT -> screen(Palette.Yellow, Palette.Cream)
            ThemeMode.DARK -> screen(Palette.Ink, Palette.Graphite)
            ThemeMode.SYSTEM -> {
                screen(Palette.Yellow, Palette.Cream)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawContext.canvas.save()
                drawContext.canvas.clipPath(path)
                screen(Palette.Ink, Palette.Graphite)
                drawContext.canvas.restore()
            }
        }
    }
}

/* ---------- Step 3: server ---------- */

@Composable
private fun ServerStep(text: String, onText: (String) -> Unit, probe: ServerProbe, onTest: () -> Unit) {
    val c = LocalInk.current
    StepHeader(InkGlyph.Server, stringResource(R.string.wizard_server_title), stringResource(R.string.wizard_server_sub))
    Appear(0) {
        Column(
            Modifier.fillMaxWidth().inkSurface(c.paper, c.ink, shadow = c.shadow).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // URLs are always left-to-right, also inside the Arabic interface.
            androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    label = { Text(stringResource(R.string.server_url)) },
                    placeholder = { Text("https://maps.example.org") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.ink, unfocusedBorderColor = c.ink.copy(alpha = 0.5f),
                        focusedLabelColor = c.text, unfocusedLabelColor = c.muted, cursorColor = c.ink,
                        focusedTextColor = c.text, unfocusedTextColor = c.text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val spin = remember { Animatable(0f) }
            LaunchedEffect(probe is ServerProbe.Running) {
                while (probe is ServerProbe.Running) spin.animateTo(spin.value + 360f, tween(800))
            }
            InkButton(
                stringResource(if (probe is ServerProbe.Running) R.string.server_testing else R.string.server_test),
                onTest, Modifier.fillMaxWidth(), InkGlyph.Sync,
                style = InkButtonStyle.Secondary,
                enabled = text.isNotBlank() && probe !is ServerProbe.Running,
                iconRotation = spin.value,
            )
            AnimatedContent(
                targetState = probe,
                transitionSpec = { (fadeIn(tween(250)) + expandVertically()) togetherWith (fadeOut(tween(150)) + shrinkVertically()) },
                label = "probe",
            ) { p ->
                when (p) {
                    is ServerProbe.Ok -> ResultLine(InkGlyph.Check, c.ok, stringResource(R.string.server_ok, p.layers))
                    is ServerProbe.Failed -> ResultLine(InkGlyph.Warning, c.bad, stringResource(R.string.server_failed))
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
            if ("example.org" in text || text == BuildConfig.DEFAULT_SERVER_URL && "example.org" in BuildConfig.DEFAULT_SERVER_URL) {
                Text(stringResource(R.string.wizard_server_example), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
    }
}

@Composable
private fun ResultLine(glyph: InkGlyph, color: Color, text: String) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.4f)) }
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkIcon(glyph, size = 22.dp, ink = Palette.Ink, fill = color, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

/* ---------- Step 4: OsmAnd ---------- */

@Composable
private fun OsmAndStep(state: UiState, vm: MainViewModel) {
    val c = LocalInk.current
    StepHeader(InkGlyph.Navigate, stringResource(R.string.wizard_osmand_title), stringResource(R.string.wizard_osmand_sub))
    val app = state.installed.firstOrNull { it.packageName == state.selectedPackage }
    Appear(0) {
        Column(
            Modifier.fillMaxWidth().inkSurface(c.paper, c.ink, shadow = c.shadow).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (app == null) {
                ResultLine(InkGlyph.Warning, c.bad, stringResource(R.string.wizard_osmand_missing))
                InkButton(stringResource(R.string.install_osmand), vm::installOsmAnd, Modifier.fillMaxWidth(), InkGlyph.Store)
            } else {
                ResultLine(InkGlyph.Check, c.ok, stringResource(R.string.osmand_installed, app.label, app.versionName))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(if (state.connection is ConnectionState.Connected) c.ok else Palette.Brown, true)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (state.connection is ConnectionState.Connected) R.string.pill_connected else R.string.pill_connecting,
                        ),
                        color = c.text, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
    Appear(1) {
        Row(
            Modifier.fillMaxWidth().inkSurface(c.paper, c.ink, shadow = c.shadow).padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            InkIcon(InkGlyph.Shield, size = 30.dp, ink = c.ink, fill = Palette.Yellow)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.wizard_osmand_enable), color = c.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/* ---------- Step 5: done ---------- */

@Composable
private fun DoneStep() {
    val c = LocalInk.current
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow)) }
    val t = rememberInfiniteTransition(label = "rays")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
    val pulse by t.animateFloat(0.92f, 1.05f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            // Hand-drawn sun rays turning slowly behind a big bouncing pin.
            Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn; scaleX = pop.value; scaleY = pop.value }) {
                val center = Offset(size.width / 2, size.height / 2)
                repeat(14) { i ->
                    val a = Math.toRadians(i * 360.0 / 14).toFloat()
                    val r1 = size.minDimension * 0.30f
                    val r2 = size.minDimension * (if (i % 2 == 0) 0.48f else 0.42f)
                    drawLine(
                        c.ink.copy(alpha = 0.55f),
                        Offset(center.x + cos(a) * r1, center.y + sin(a) * r1),
                        Offset(center.x + cos(a) * r2, center.y + sin(a) * r2),
                        strokeWidth = 3.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
            Box(
                Modifier
                    .size(120.dp)
                    .graphicsLayer { scaleX = pop.value * pulse; scaleY = pop.value * pulse }
                    .inkSurface(Palette.Yellow, Palette.Ink, radius = 60.dp, offset = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                InkIcon(InkGlyph.Pin, size = 74.dp, ink = Palette.Ink, fill = Palette.Cream)
            }
        }
        Text(
            stringResource(R.string.wizard_done_title),
            style = MaterialTheme.typography.headlineSmall,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer { alpha = pop.value.coerceIn(0f, 1f) },
        )
        Text(
            stringResource(R.string.wizard_done_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
    }
}
