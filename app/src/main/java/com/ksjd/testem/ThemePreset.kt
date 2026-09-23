package com.ksjd.testem

data class ThemePreset(
    val id: String,
    val name: String,
    val primary: Long,
    val secondary: Long,
    val tertiary: Long
)

object DefaultThemePresets {
    val all = listOf(
        ThemePreset(id = "stop", name = "Zastávka", primary = 0xFF1D4FB8, secondary = 0xFF3E5470, tertiary = 0xFFFFB21E),
        ThemePreset(id = "ocean", name = "Ocean", primary = 0xFF136F63, secondary = 0xFF0B4F6C, tertiary = 0xFF177E89),
        ThemePreset(id = "sunset", name = "Sunset", primary = 0xFFE85D04, secondary = 0xFFDC2F02, tertiary = 0xFF6A040F)
    )
}
