package com.ksjd.testem.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.ksjd.testem.DefaultThemePresets
import com.ksjd.testem.ThemePreset

private fun onColorFor(color: Color): Color = if (color.luminance() > 0.45f) Ink else Color.White

private fun lightScheme(preset: ThemePreset): ColorScheme {
    val primary = Color(preset.primary)
    val secondary = Color(preset.secondary)
    val tertiary = Color(preset.tertiary)
    return lightColorScheme(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = lerp(primary, Color.White, 0.86f),
        onPrimaryContainer = lerp(primary, Color.Black, 0.55f),
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = lerp(secondary, Color.White, 0.86f),
        onSecondaryContainer = lerp(secondary, Color.Black, 0.55f),
        tertiary = tertiary,
        onTertiary = onColorFor(tertiary),
        tertiaryContainer = lerp(tertiary, Color.White, 0.8f),
        onTertiaryContainer = lerp(tertiary, Color.Black, 0.6f),
        background = Paper,
        onBackground = Ink,
        surface = PaperRaised,
        onSurface = Ink,
        surfaceVariant = PaperSunken,
        onSurfaceVariant = InkMuted,
        surfaceContainerLowest = PaperRaised,
        surfaceContainerLow = PaperRaised,
        surfaceContainer = PaperRaised,
        surfaceContainerHigh = PaperRaised,
        surfaceContainerHighest = PaperSunken,
        outline = PaperLine,
        outlineVariant = PaperSunken,
        error = ErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight
    )
}

private fun darkScheme(preset: ThemePreset, amoled: Boolean): ColorScheme {
    // Lift the brand colour so it keeps contrast on dark surfaces.
    val primary = lerp(Color(preset.primary), Color.White, 0.35f)
    val secondary = lerp(Color(preset.secondary), Color.White, 0.35f)
    val tertiary = lerp(Color(preset.tertiary), Color.White, 0.2f)
    val background = if (amoled) Color.Black else Night
    val surface = if (amoled) Color.Black else NightRaised
    val sunken = if (amoled) Color(0xFF0E0E0E) else NightSunken
    return darkColorScheme(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = lerp(Color(preset.primary), Color.Black, 0.55f),
        onPrimaryContainer = lerp(primary, Color.White, 0.6f),
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = lerp(Color(preset.secondary), Color.Black, 0.55f),
        onSecondaryContainer = lerp(secondary, Color.White, 0.6f),
        tertiary = tertiary,
        onTertiary = onColorFor(tertiary),
        tertiaryContainer = lerp(Color(preset.tertiary), Color.Black, 0.6f),
        onTertiaryContainer = lerp(tertiary, Color.White, 0.6f),
        background = background,
        onBackground = NightInk,
        surface = surface,
        onSurface = NightInk,
        surfaceVariant = sunken,
        onSurfaceVariant = NightInkMuted,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = sunken,
        surfaceContainerHighest = sunken,
        outline = if (amoled) Color(0xFF2A2A2A) else NightLine,
        outlineVariant = sunken,
        error = ErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark
    )
}

@Composable
fun TestEMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themePreset: ThemePreset? = null,
    amoledMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val preset = themePreset ?: DefaultThemePresets.all.first()
    val colorScheme = if (darkTheme) darkScheme(preset, amoledMode) else lightScheme(preset)
    val transit = if (darkTheme) {
        if (amoledMode) DarkTransitColors.copy(board = Color.Black, boardRow = Color(0xFF0E0E0E)) else DarkTransitColors
    } else {
        LightTransitColors
    }
    CompositionLocalProvider(LocalTransitColors provides transit) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}

object TransitTheme {
    val colors: TransitColors
        @Composable get() = LocalTransitColors.current
}
