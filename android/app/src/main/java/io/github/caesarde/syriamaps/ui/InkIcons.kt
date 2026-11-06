package io.github.caesarde.syriamaps.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Original duotone icons (24-unit grid): a coloured fill shape under a rounded ink outline,
 * in the style of a pen drawing. Each icon is a list of layers so it can be re-coloured per theme.
 */
enum class InkGlyph(internal val layers: List<Layer>) {
    Pin(
        listOf(
            fill("M12 2.6c-3.9 0-7 3.1-7 7 0 5.1 7 12 7 12s7-6.9 7-12c0-3.9-3.1-7-7-7z"),
            dot("M12 7.1a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z"),
        ),
    ),
    Layers(
        listOf(
            line("M3.2 15.6l8.8 4.4 8.8-4.4"),
            line("M3.2 12l8.8 4.4 8.8-4.4"),
            fill("M12 3.6L3.2 8l8.8 4.4L20.8 8z"),
        ),
    ),
    Raster(
        listOf(
            fill("M4.5 4.5h15a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18V6a1.5 1.5 0 0 1 1.5-1.5z"),
            line("M3.4 17.2l5.1-5.4 4 4 2.6-2.6 5.5 5.3"),
            dot("M15.6 7.4a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2z"),
        ),
    ),
    Cube(
        listOf(
            fill("M12 3l8 4.5-8 4.5-8-4.5z"),
            line("M4 7.5v9l8 4.5 8-4.5v-9"),
            line("M12 12v9"),
        ),
    ),
    Server(
        listOf(
            fill("M5.5 3.8h13A1.5 1.5 0 0 1 20 5.3v3.4a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 8.7V5.3a1.5 1.5 0 0 1 1.5-1.5z"),
            line("M5.5 13.8h13a1.5 1.5 0 0 1 1.5 1.5v3.4a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.7v-3.4a1.5 1.5 0 0 1 1.5-1.5z"),
            line("M7.6 7h.01M7.6 17h.01M11 7h5.2M11 17h5.2", width = 2.2f),
        ),
    ),
    Sync(
        listOf(
            dotFill("M12 9.6a2.4 2.4 0 1 0 0 4.8a2.4 2.4 0 1 0 0-4.8z"),
            line("M19.6 10.6a7.8 7.8 0 0 0-14-3.8M4.4 13.4a7.8 7.8 0 0 0 14 3.8"),
            line("M5.2 3.4v3.9h3.9M18.8 20.6v-3.9h-3.9"),
        ),
    ),
    Navigate(
        listOf(fill("M3.6 10.9L20.4 3.6 13.1 20.4l-2.2-7.3z")),
    ),
    Download(
        listOf(
            fill("M4 15.5v2.5a2.5 2.5 0 0 0 2.5 2.5h11a2.5 2.5 0 0 0 2.5-2.5v-2.5"),
            line("M12 3.5v11.5M7.3 10.3l4.7 4.7 4.7-4.7"),
        ),
    ),
    Shield(
        listOf(
            fill("M12 2.9l7.6 3v5.6c0 4.6-3.2 8.1-7.6 9.6-4.4-1.5-7.6-5-7.6-9.6V5.9z"),
            line("M8.6 12.1l2.4 2.4 4.5-4.8"),
        ),
    ),
    Check(
        listOf(
            fill("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"),
            line("M7.9 12.3l2.8 2.8 5.4-5.6"),
        ),
    ),
    Warning(
        listOf(
            fill("M12 3.4c.5 0 1 .3 1.3.8l8 14.2c.6 1-.1 2.2-1.3 2.2H4c-1.2 0-1.9-1.2-1.3-2.2l8-14.2c.3-.5.8-.8 1.3-.8z"),
            line("M12 9.4v4.6M12 17.1h.01", width = 2.2f),
        ),
    ),
    Store(
        listOf(
            fill("M4.5 9.5h15v9.5a1.5 1.5 0 0 1-1.5 1.5H6a1.5 1.5 0 0 1-1.5-1.5z"),
            line("M3.4 9.5L5.2 4h13.6l1.8 5.5z"),
            line("M9.6 20.5v-5h4.8v5"),
        ),
    ),
    Gear(
        listOf(
            fill("M10.3 2.8h3.4l.5 2.4c.6.2 1.2.5 1.7.9l2.3-.8 1.7 2.9-1.8 1.6c.1.6.1 1.3 0 1.9l1.8 1.6-1.7 2.9-2.3-.8c-.5.4-1.1.7-1.7.9l-.5 2.4h-3.4l-.5-2.4c-.6-.2-1.2-.5-1.7-.9l-2.3.8-1.7-2.9 1.8-1.6c-.1-.6-.1-1.3 0-1.9L4.2 8.2l1.7-2.9 2.3.8c.5-.4 1.1-.7 1.7-.9z"),
            dot("M12 8.6a3.4 3.4 0 1 0 0 6.8a3.4 3.4 0 1 0 0-6.8z"),
            dotFill("M12 10.3a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4z"),
        ),
    ),
    Globe(
        listOf(
            fill("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"),
            line("M3.2 12h17.6M12 3c2.5 2.6 3.7 5.6 3.7 9s-1.2 6.4-3.7 9c-2.5-2.6-3.7-5.6-3.7-9S9.5 5.6 12 3z"),
        ),
    ),
    Sun(
        listOf(
            fill("M12 7.2a4.8 4.8 0 1 0 0 9.6a4.8 4.8 0 1 0 0-9.6z"),
            line("M12 2.4v1.8M12 19.8v1.8M2.4 12h1.8M19.8 12h1.8M5.2 5.2l1.3 1.3M17.5 17.5l1.3 1.3M5.2 18.8l1.3-1.3M17.5 6.5l1.3-1.3"),
        ),
    ),
    Moon(
        listOf(
            fill("M20 14.6A8.2 8.2 0 0 1 9.4 4a8.2 8.2 0 1 0 10.6 10.6z"),
            line("M16.6 3.6l.5 1.4 1.4.5-1.4.5-.5 1.4-.5-1.4-1.4-.5 1.4-.5z", width = 1.3f),
        ),
    ),
    Phone(
        listOf(
            fill("M8 2.8h8a1.8 1.8 0 0 1 1.8 1.8v14.8a1.8 1.8 0 0 1-1.8 1.8H8a1.8 1.8 0 0 1-1.8-1.8V4.6A1.8 1.8 0 0 1 8 2.8z"),
            line("M10.6 18h2.8"),
            line("M9.2 6.4h5.6v8H9.2z", width = 1.4f),
        ),
    ),
    Arrow(
        listOf(line("M4.5 12h15M13.5 6l6 6-6 6", width = 2.2f)),
    ),
    Pencil(
        listOf(
            fill("M15.6 4.2l4.2 4.2L9 19.2l-5.3 1.1 1.1-5.3z"),
            line("M13.4 6.4l4.2 4.2"),
        ),
    ),
    Closed(
        listOf(
            fill("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"),
            line("M5.7 5.7l12.6 12.6", width = 2.2f),
        ),
    ),
    Flag(
        listOf(
            fill("M5.5 4h11.2l-2.4 4.2 2.4 4.3H5.5z"),
            line("M5.5 3v18", width = 2f),
        ),
    ),
    Close(
        listOf(line("M6 6l12 12M18 6L6 18", width = 2.2f)),
    ),
    Spark(
        listOf(
            fill("M12 2.8l1.9 5.7 5.7 1.9-5.7 1.9L12 18l-1.9-5.7-5.7-1.9 5.7-1.9z"),
            line("M18.6 15.6l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z", width = 1.4f),
        ),
    ),
}

internal enum class LayerKind { FILL, LINE, DOT, DOT_FILL }

internal class Layer(val path: String, val kind: LayerKind, val width: Float)

private fun fill(p: String) = Layer(p, LayerKind.FILL, 1.8f)
private fun line(p: String, width: Float = 1.8f) = Layer(p, LayerKind.LINE, width)
private fun dot(p: String) = Layer(p, LayerKind.DOT, 0f)
private fun dotFill(p: String) = Layer(p, LayerKind.DOT_FILL, 1.6f)

private fun buildGlyph(glyph: InkGlyph, ink: Color, fill: Color): ImageVector =
    ImageVector.Builder(glyph.name, 24.dp, 24.dp, 24f, 24f).apply {
        glyph.layers.forEach { layer ->
            val nodes = addPathNodes(layer.path)
            when (layer.kind) {
                LayerKind.FILL, LayerKind.DOT_FILL -> addPath(
                    nodes,
                    fill = SolidColor(fill),
                    stroke = SolidColor(ink),
                    strokeLineWidth = layer.width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                LayerKind.LINE -> addPath(
                    nodes,
                    stroke = SolidColor(ink),
                    strokeLineWidth = layer.width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                LayerKind.DOT -> addPath(nodes, fill = SolidColor(ink))
            }
        }
    }.build()

/** Draws [glyph] with a black ink outline over a cream (or custom) fill. Usually sits on a yellow badge. */
@Composable
fun InkIcon(
    glyph: InkGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    ink: Color = Palette.Ink,
    fill: Color = Palette.Cream,
) {
    val vector = remember(glyph, ink, fill) { buildGlyph(glyph, ink, fill) }
    Image(vector, contentDescription = null, modifier = modifier.size(size))
}
