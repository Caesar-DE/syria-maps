package io.github.caesarde.syriamaps.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val Green = Color(0xFF007A3D)
private val GreenLight = Color(0xFF6FD99A)

@Composable
fun SyriaMapsTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = GreenLight, secondary = GreenLight)
    } else {
        lightColorScheme(primary = Green, secondary = Green)
    }
    MaterialTheme(colorScheme = colors) {
        // The interface is Arabic-first: always right-to-left, independent of the device language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
