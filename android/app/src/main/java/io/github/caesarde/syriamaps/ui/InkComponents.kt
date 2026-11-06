package io.github.caesarde.syriamaps.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Hard "printed" shadow offset used by cards and buttons. In RTL it falls to the bottom-left. */
private val ShadowOffset = 5.dp

/** Paper surface with an ink outline and an offset ink shadow. */
fun Modifier.inkSurface(
    paper: Color,
    ink: Color,
    shadow: Color = ink,
    radius: Dp = 20.dp,
    offset: Dp = ShadowOffset,
    stroke: Dp = 2.dp,
): Modifier = this
    .drawBehind {
        val o = offset.toPx()
        val dx = if (layoutDirection == LayoutDirection.Rtl) -o else o
        drawRoundRect(shadow, topLeft = Offset(dx, o), size = size, cornerRadius = CornerRadius(radius.toPx()))
    }
    .background(paper, RoundedCornerShape(radius))
    .border(stroke, ink, RoundedCornerShape(radius))

/** Slides and fades content in, one after another by [index], with a soft spring. */
@Composable
fun Appear(index: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(90L * index)
        p.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = p.value.coerceIn(0f, 1f)
            translationY = (1f - p.value) * 60.dp.toPx()
            val s = 0.94f + 0.06f * p.value
            scaleX = s
            scaleY = s
            rotationZ = (1f - p.value) * -1.5f
        },
    ) { content() }
}

/** A section card: yellow icon badge, bold title, then content. Size changes animate. */
@Composable
fun InkCard(
    title: String,
    glyph: InkGlyph,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalInk.current
    Column(
        modifier
            .fillMaxWidth()
            .inkSurface(c.paper, c.ink, shadow = c.shadow)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(glyph)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
        }
        content()
    }
}

/** Yellow rounded badge holding an [InkIcon]; wobbles once when it first appears. */
@Composable
fun IconBadge(glyph: InkGlyph, modifier: Modifier = Modifier, size: Dp = 42.dp, fill: Color = Palette.Cream) {
    val wobble = remember { Animatable(-14f) }
    LaunchedEffect(Unit) { wobble.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessLow)) }
    Box(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = wobble.value }
            .inkSurface(Palette.Yellow, Palette.Ink, radius = size * 0.32f, offset = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        InkIcon(glyph, size = size * 0.62f, fill = fill)
    }
}

enum class InkButtonStyle { Primary, Secondary }

/**
 * Tactile button: the face sinks into its shadow while pressed (spring), with an optional
 * leading icon that can spin (e.g. while syncing).
 */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: InkGlyph? = null,
    style: InkButtonStyle = InkButtonStyle.Primary,
    enabled: Boolean = true,
    iconRotation: Float = 0f,
) {
    val c = LocalInk.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val sink by animateFloatAsState(
        if (pressed && enabled) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "sink",
    )
    val face = if (style == InkButtonStyle.Primary) Palette.Yellow else c.paper
    val label = if (style == InkButtonStyle.Primary) Palette.Ink else c.text
    val border = if (style == InkButtonStyle.Primary) Palette.Ink else c.ink
    val shadow = c.shadow
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val alphaTarget by animateFloatAsState(if (enabled) 1f else 0.45f, tween(250), label = "enabled")
    Box(
        modifier
            .graphicsLayer { alpha = alphaTarget }
            .drawBehind {
                val o = 4.dp.toPx()
                val dx = if (layoutDirection == LayoutDirection.Rtl) -o else o
                drawRoundRect(shadow, Offset(dx, o), size, CornerRadius(14.dp.toPx()))
            }
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier
                .graphicsLayer {
                    val o = 4.dp.toPx() * sink
                    translationX = if (rtl) -o else o
                    translationY = o
                }
                .background(face, RoundedCornerShape(14.dp))
                .border(2.dp, border, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 13.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                InkIcon(
                    glyph,
                    size = 22.dp,
                    ink = label,
                    fill = if (style == InkButtonStyle.Primary) Palette.Cream else Palette.Yellow,
                    modifier = Modifier.graphicsLayer { rotationZ = iconRotation },
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/** Solid dot with an expanding ring while [pulsing]. Colour changes cross-fade. */
@Composable
fun PulseDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val animated by animateColorAsState(color, tween(450), label = "dot")
    val t = rememberInfiniteTransition(label = "pulse")
    val wave by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "wave",
    )
    Canvas(modifier.size(14.dp)) {
        val r = size.minDimension / 4f
        if (pulsing) drawCircle(animated.copy(alpha = 0.5f * (1f - wave)), radius = r * (1f + 1.1f * wave))
        drawCircle(animated, radius = r)
    }
}

/** Pill with a status dot, e.g. in the header. */
@Composable
fun StatusPill(text: String, color: Color, pulsing: Boolean) {
    val c = LocalInk.current
    Row(
        Modifier
            .inkSurface(c.paper, c.ink, shadow = c.shadow, radius = 50.dp, offset = 3.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color, pulsing)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.text)
    }
}

/** Progress bar with animated fill and moving diagonal stripes. */
@Composable
fun InkProgress(progress: Float, modifier: Modifier = Modifier) {
    val c = LocalInk.current
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "progress")
    val t = rememberInfiniteTransition(label = "stripes")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "shift")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(CircleShape)
            .background(if (c.dark) Palette.Graphite else Palette.Sand)
            .border(2.dp, c.ink, CircleShape),
    ) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val w = size.width * p
        val left = if (rtl) size.width - w else 0f
        clipRect(left, 0f, left + w, size.height) {
            drawRect(Palette.Yellow)
            val gap = 12.dp.toPx()
            var x = -size.height + shift * gap * (if (rtl) -1 else 1)
            while (x < size.width + size.height) {
                drawLine(
                    Palette.Ink.copy(alpha = 0.22f),
                    Offset(x, size.height), Offset(x + size.height, 0f),
                    strokeWidth = 4.dp.toPx(),
                )
                x += gap
            }
        }
    }
}

@Composable
fun inkSwitchColors(): SwitchColors {
    val c = LocalInk.current
    return if (c.dark) {
        SwitchDefaults.colors(
            checkedThumbColor = Palette.Ink, checkedTrackColor = Palette.Yellow, checkedBorderColor = Palette.Yellow,
            uncheckedThumbColor = Palette.Sand, uncheckedTrackColor = Palette.Graphite, uncheckedBorderColor = Palette.Sand,
        )
    } else {
        SwitchDefaults.colors(
            checkedThumbColor = Palette.Yellow, checkedTrackColor = Palette.Ink, checkedBorderColor = Palette.Ink,
            uncheckedThumbColor = Palette.Ink, uncheckedTrackColor = Palette.Sand, uncheckedBorderColor = Palette.Ink,
        )
    }
}

/**
 * Pen-scribble doodles across the background (a nod to the hand-drawn poster style). The strokes
 * draw themselves in once, then drift slowly.
 */
@Composable
fun ScribbleBackground(modifier: Modifier = Modifier) {
    val c = LocalInk.current
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(2600, easing = FastOutSlowInEasing)) }
    val t = rememberInfiniteTransition(label = "drift")
    val drift by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "drift",
    )
    Box(
        modifier.drawWithCache {
            val loops = buildScribbles(size)
            val lengths = loops.map { PathMeasure().apply { setPath(it, false) }.length }
            val width = 1.6.dp.toPx()
            onDrawBehind {
                loops.forEachIndexed { i, path ->
                    val len = lengths[i]
                    val shown = len * reveal.value
                    val phase = i * 1.3f
                    translate(sin(drift + phase) * 10.dp.toPx(), cos(drift * 0.8f + phase) * 8.dp.toPx()) {
                        drawPath(
                            path,
                            c.scribble,
                            style = Stroke(
                                width = width,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(shown, len + 1f), 0f),
                            ),
                        )
                    }
                }
            }
        },
    )
}

/** Deterministic tangled loops (fixed seed), like quick pen circles around the edges of the page. */
private fun buildScribbles(size: Size): List<Path> {
    val rnd = Random(7)
    val anchors = listOf(0.12f to 0.08f, 0.9f to 0.14f, 0.05f to 0.48f, 0.95f to 0.55f, 0.18f to 0.9f, 0.82f to 0.92f, 0.5f to 0.02f)
    return anchors.map { (ax, ay) ->
        val cx = size.width * ax
        val cy = size.height * ay
        val base = size.minDimension * (0.12f + rnd.nextFloat() * 0.1f)
        val turns = 5 + rnd.nextInt(4)
        val steps = turns * 28
        Path().apply {
            for (s in 0..steps) {
                val a = s / 28f * 2f * PI.toFloat()
                val wob = 1f + 0.35f * sin(a * 0.37f + rnd.nextFloat() * 0.2f)
                val r = base * wob * (0.55f + 0.45f * s / steps)
                val x = cx + cos(a) * r * 1.25f
                val y = cy + sin(a * 1.07f) * r
                if (s == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }
}
