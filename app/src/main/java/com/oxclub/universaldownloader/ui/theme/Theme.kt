package com.oxclub.universaldownloader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.oxclub.universaldownloader.domain.model.AccentColor
import com.oxclub.universaldownloader.domain.model.ThemeMode

private fun buildScheme(accent: AccentColor, dark: Boolean): ColorScheme {
    val seed = Color(accent.argb)
    return if (!dark) {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = lerp(seed, Color.White, 0.85f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.65f),
            secondary = lerp(seed, Color(0xFF625B71), 0.6f),
            secondaryContainer = lerp(seed, Color.White, 0.88f),
            onSecondaryContainer = lerp(seed, Color.Black, 0.7f),
        )
    } else {
        darkColorScheme(
            primary = lerp(seed, Color.White, 0.55f),
            onPrimary = lerp(seed, Color.Black, 0.75f),
            primaryContainer = lerp(seed, Color.Black, 0.5f),
            onPrimaryContainer = lerp(seed, Color.White, 0.85f),
            secondary = lerp(seed, Color(0xFFCCC2DC), 0.6f),
            secondaryContainer = lerp(seed, Color.Black, 0.6f),
            onSecondaryContainer = lerp(seed, Color.White, 0.85f),
        )
    }
}

@Composable
fun UniversalDownloaderTheme(
    themeMode: ThemeMode,
    accent: AccentColor,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = remember(dark, accent) { buildScheme(accent, dark) }
    MaterialTheme(colorScheme = scheme, content = content)
}
