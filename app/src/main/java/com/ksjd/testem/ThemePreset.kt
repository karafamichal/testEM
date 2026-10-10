package com.ksjd.testem

data class ThemePreset(
    val id: String,
    val name: String,
    val primary: Long,
    val secondary: Long,
    val tertiary: Long,
    /** Colours for single parts of the app ([ThemeRole] key → ARGB); a missing one is worked out from the rest. */
    val roles: Map<String, Long> = emptyMap()
)

/** Every colour a rider can set on its own, in the order Appearance lists them. */
object ThemeRole {
    const val BACKGROUND = "background"
    const val CARDS = "cards"
    const val HIGHLIGHT = "highlight"   // the next-bus card
    const val BAR = "bar"               // the floating bottom bar
    const val LIVE = "live"             // live countdowns on dark boards
    const val DELAY = "delay"
    const val LATE = "late"
    const val TEXT = "text"
    const val PLATE = "plate"           // line-number plates
    val all = listOf(BACKGROUND, CARDS, HIGHLIGHT, BAR, LIVE, DELAY, LATE, TEXT, PLATE)
}

object DefaultThemePresets {
    val all = listOf(
        ThemePreset(
            id = "planride", name = "Plan&Ride", primary = 0xFF157A38, secondary = 0xFF3E5470, tertiary = 0xFF6EE7A8,
            roles = mapOf(ThemeRole.BACKGROUND to 0xFFF2F5F3, ThemeRole.HIGHLIGHT to 0xFF157A38, ThemeRole.BAR to 0xFF14181D)
        ),
        ThemePreset(
            id = "sage", name = "Sage", primary = 0xFF157A38, secondary = 0xFF3E5470, tertiary = 0xFF6EE7A8,
            roles = mapOf(
                ThemeRole.BACKGROUND to 0xFFE2ECE4, ThemeRole.HIGHLIGHT to 0xFF0B3D1C,
                ThemeRole.BAR to 0xFF157A38, ThemeRole.PLATE to 0xFF0B3D1C
            )
        ),
        ThemePreset(
            id = "darkgreen", name = "Dark green", primary = 0xFF157A38, secondary = 0xFF3E5470, tertiary = 0xFF6EE7A8,
            roles = mapOf(
                ThemeRole.BACKGROUND to 0xFF0F1D17, ThemeRole.CARDS to 0xFF172A21, ThemeRole.HIGHLIGHT to 0xFF157A38,
                ThemeRole.BAR to 0xFF6EE7A8, ThemeRole.PLATE to 0xFF2A4A3A
            )
        ),
        ThemePreset(
            id = "inkmint", name = "Ink & mint", primary = 0xFF157A38, secondary = 0xFF3E5470, tertiary = 0xFF6EE7A8,
            roles = mapOf(
                ThemeRole.BACKGROUND to 0xFFFFFFFF, ThemeRole.CARDS to 0xFFEEF3F0, ThemeRole.HIGHLIGHT to 0xFF14181D,
                ThemeRole.BAR to 0xFF14181D
            )
        ),
        ThemePreset(id = "stop", name = "Zastávka", primary = 0xFF1D4FB8, secondary = 0xFF3E5470, tertiary = 0xFF6EE7A8),
        ThemePreset(id = "ocean", name = "Ocean", primary = 0xFF136F63, secondary = 0xFF0B4F6C, tertiary = 0xFF177E89)
    )
}
