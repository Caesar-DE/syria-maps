package io.github.caesarde.syriamaps.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * "Ink on yellow" palette: a saturated yellow ground, black ink lines, warm cream paper and a
 * brown accent, like a pen drawing on a yellow poster.
 */
object Palette {
    val Yellow = Color(0xFFFFD21F)
    val Ink = Color(0xFF141311)
    val Charcoal = Color(0xFF1F1D1A)
    val Graphite = Color(0xFF2B2824)
    val Cream = Color(0xFFFFF4D6)
    val Sand = Color(0xFFEBD9B4)
    val Brown = Color(0xFF6B4426)
    val Olive = Color(0xFF3E7B2C)
    val OliveLight = Color(0xFF9BD67E)
    val Rust = Color(0xFFA33A17)
    val RustLight = Color(0xFFFF9A73)
}

/** Colours the custom components need beyond Material's scheme. */
@Immutable
data class InkColors(
    val ground: Color,      // screen background
    val paper: Color,       // card surface
    val ink: Color,         // outlines, shadows, primary text
    val text: Color,
    val accent: Color,      // icon fills, highlights
    val onAccent: Color,
    val muted: Color,       // secondary text
    val ok: Color,
    val bad: Color,
    val scribble: Color,    // background doodle strokes
    val dark: Boolean,
)

private val LightInk = InkColors(
    ground = Palette.Yellow,
    paper = Palette.Cream,
    ink = Palette.Ink,
    text = Palette.Ink,
    accent = Palette.Yellow,
    onAccent = Palette.Ink,
    muted = Color(0xFF5B4A35),
    ok = Palette.Olive,
    bad = Palette.Rust,
    scribble = Palette.Ink.copy(alpha = 0.17f),
    dark = false,
)

private val DarkInk = InkColors(
    ground = Palette.Ink,
    paper = Palette.Charcoal,
    ink = Palette.Yellow,
    text = Palette.Cream,
    accent = Palette.Yellow,
    onAccent = Palette.Ink,
    muted = Palette.Sand.copy(alpha = 0.72f),
    ok = Palette.OliveLight,
    bad = Palette.RustLight,
    scribble = Palette.Yellow.copy(alpha = 0.12f),
    dark = true,
)

val LocalInk = staticCompositionLocalOf { LightInk }

private val InkTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Black),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    )
}

@Composable
fun SyriaMapsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) DarkInk else LightInk
    val scheme = if (dark) {
        darkColorScheme(
            primary = Palette.Yellow, onPrimary = Palette.Ink,
            secondary = Palette.Sand, onSecondary = Palette.Ink,
            background = ink.ground, onBackground = Palette.Cream,
            surface = ink.paper, onSurface = Palette.Cream,
            surfaceVariant = Palette.Graphite, onSurfaceVariant = Palette.Sand,
            outline = Palette.Yellow, error = Palette.RustLight,
        )
    } else {
        lightColorScheme(
            primary = Palette.Ink, onPrimary = Palette.Yellow,
            secondary = Palette.Brown, onSecondary = Palette.Cream,
            background = ink.ground, onBackground = Palette.Ink,
            surface = ink.paper, onSurface = Palette.Ink,
            surfaceVariant = Palette.Sand, onSurfaceVariant = Palette.Ink,
            outline = Palette.Ink, error = Palette.Rust,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = InkTypography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
        ),
    ) {
        // The interface is Arabic-first: always right-to-left, independent of the device language.
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalInk provides ink,
            content = content,
        )
    }
}
