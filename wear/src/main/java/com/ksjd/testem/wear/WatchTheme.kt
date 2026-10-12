package com.ksjd.testem.wear

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val THEME_PATH = "/plan-ride/theme"

/** The phone app's colour theme (its preset, lifted for a black screen as the phone's dark mode does), sent by WatchSync. */
data class WatchTheme(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    /** The screen's ground, the chips' colour and live countdowns (sent by phones from 2.15 on). */
    val background: Int = DEFAULT_BACKGROUND,
    val surface: Int = DEFAULT_SURFACE,
    val live: Int = DEFAULT_LIVE
) {
    companion object {
        const val DEFAULT_BACKGROUND = 0xFF0B0F14.toInt()
        const val DEFAULT_SURFACE = 0xFF1F2935.toInt()
        const val DEFAULT_LIVE = 0xFF8AB4FF.toInt()
        /** Plan&Ride's default theme (Zastávka blue), as the phone sends it, until the phone has sent its own. */
        val Default = WatchTheme(0xFF6C8DD1.toInt(), 0xFF8290A2.toInt(), 0xFF8AB4FF.toInt())
    }
}

object ThemeStore {
    private val _state = MutableStateFlow(WatchTheme.Default)
    val state: StateFlow<WatchTheme> = _state
    private var loaded = false

    fun current(context: Context): WatchTheme {
        if (!loaded) {
            loaded = true
            val p = context.prefs()
            if (p.contains("primary")) _state.value = WatchTheme(
                p.getInt("primary", 0), p.getInt("secondary", 0), p.getInt("tertiary", 0),
                p.getInt("background", WatchTheme.DEFAULT_BACKGROUND), p.getInt("surface", WatchTheme.DEFAULT_SURFACE), p.getInt("live", WatchTheme.DEFAULT_LIVE)
            )
        }
        return _state.value
    }

    fun update(context: Context, map: DataMap) {
        loaded = true
        val theme = WatchTheme(
            map.getInt("primary"), map.getInt("secondary"), map.getInt("tertiary"),
            map.getInt("background", WatchTheme.DEFAULT_BACKGROUND), map.getInt("surface", WatchTheme.DEFAULT_SURFACE), map.getInt("live", WatchTheme.DEFAULT_LIVE)
        )
        context.prefs().edit().putInt("primary", theme.primary).putInt("secondary", theme.secondary).putInt("tertiary", theme.tertiary)
            .putInt("background", theme.background).putInt("surface", theme.surface).putInt("live", theme.live).apply()
        _state.value = theme
        refreshTiles(context)
    }

    private fun Context.prefs() = getSharedPreferences("theme", Context.MODE_PRIVATE)
}

/** City buses (Banská Bystrica, Zvolen…: 1–3 digit lines) green, regional buses (5–6 digits, 060xxx) blue, others grey. */
fun lineColor(line: String): Int {
    val number = line.trim().substringAfterLast(' ')
    return when {
        Regex("""\d{1,3}[A-Z]?""").matches(number) -> 0xFF157A38.toInt()
        Regex("""\d{5,6}""").matches(number) -> 0xFF1D4FB8.toInt()
        else -> 0xFF5F6B7A.toInt()
    }
}

/** How late the bus is, as a colour: on time green, a few minutes orange, more red; early (it may leave without you) orange too. */
fun delayColor(delaySeconds: Int?): Int? = when {
    delaySeconds == null -> null
    delaySeconds < -60 -> 0xFFFFA726.toInt()
    delaySeconds <= 120 -> 0xFF5BD27A.toInt()
    delaySeconds <= 300 -> 0xFFFFA726.toInt()
    else -> 0xFFFF5A52.toInt()
}

fun Int.color() = Color(this)
