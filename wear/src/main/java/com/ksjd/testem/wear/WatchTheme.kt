package com.ksjd.testem.wear

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val THEME_PATH = "/plan-ride/theme"

/** The phone app's colour theme (its preset, lifted for a black screen as the phone's dark mode does), sent by WatchSync. */
data class WatchTheme(val primary: Int, val secondary: Int, val tertiary: Int) {
    companion object {
        /** Plan&Ride's default theme ("Zastávka"), as the phone sends it, until the phone has sent its own. */
        val Default = WatchTheme(0xFF6C8DD1.toInt(), 0xFF8290A2.toInt(), 0xFFFFC14B.toInt())
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
            if (p.contains("primary")) _state.value = WatchTheme(p.getInt("primary", 0), p.getInt("secondary", 0), p.getInt("tertiary", 0))
        }
        return _state.value
    }

    fun update(context: Context, map: DataMap) {
        loaded = true
        val theme = WatchTheme(map.getInt("primary"), map.getInt("secondary"), map.getInt("tertiary"))
        context.prefs().edit().putInt("primary", theme.primary).putInt("secondary", theme.secondary).putInt("tertiary", theme.tertiary).apply()
        _state.value = theme
        refreshTiles(context)
    }

    private fun Context.prefs() = getSharedPreferences("theme", Context.MODE_PRIVATE)
}

/** City buses (Banská Bystrica, Zvolen…: 1–3 digit lines) blue, regional buses (5–6 digits, 060xxx) orange, others grey. */
fun lineColor(line: String): Int {
    val number = line.trim().substringAfterLast(' ')
    return when {
        Regex("""\d{1,3}[A-Z]?""").matches(number) -> 0xFF2F6FDB.toInt()
        Regex("""\d{5,6}""").matches(number) -> 0xFFE07B12.toInt()
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
