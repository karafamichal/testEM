package com.ksjd.testem.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ksjd.testem.ThemePreset

private val BaseDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    secondary = DarkSecondary,
    tertiary = DarkTertiary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    outline = DarkOutline,
    primaryContainer = DarkPrimaryContainer,
    secondaryContainer = DarkSecondaryContainer,
    error = DarkError,
    errorContainer = DarkErrorContainer,
    onPrimary = DarkOnPrimary,
    onSecondary = DarkOnSecondaryContainer,
    onTertiary = DarkOnSurface,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    onPrimaryContainer = DarkOnPrimaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    onError = DarkOnError,
    onErrorContainer = DarkOnErrorContainer
)

private val BaseLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    secondary = LightSecondary,
    tertiary = LightTertiary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    outline = LightOutline,
    primaryContainer = LightPrimaryContainer,
    secondaryContainer = LightSecondaryContainer,
    error = LightError,
    errorContainer = LightErrorContainer,
    onPrimary = LightOnPrimary,
    onSecondary = LightOnSecondaryContainer,
    onTertiary = LightOnSurface,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    onPrimaryContainer = LightOnPrimaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    onError = LightOnError,
    onErrorContainer = LightOnErrorContainer
)

private fun presetColorScheme(preset: ThemePreset, darkTheme: Boolean): androidx.compose.material3.ColorScheme {
    val base = if (darkTheme) BaseDarkColorScheme else BaseLightColorScheme
    return base.copy(
        primary = Color(preset.primary),
        secondary = Color(preset.secondary),
        tertiary = Color(preset.tertiary)
    )
}

@Composable
fun TestEMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    themePreset: ThemePreset? = null,
    amoledMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        themePreset != null -> presetColorScheme(themePreset, darkTheme)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BaseDarkColorScheme
        else -> BaseLightColorScheme
    }
    val colorScheme = if (darkTheme && amoledMode) {
        baseScheme.copy(
            background = Color(0xFF000000),
            surface = Color(0xFF000000),
            surfaceVariant = Color(0xFF0A0A0A)
        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}