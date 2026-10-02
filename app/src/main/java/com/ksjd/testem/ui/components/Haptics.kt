package com.ksjd.testem.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Short vibrations on taps and actions. Off in Settings turns them all off; the phone's own
 * "touch feedback" setting is respected too (performHapticFeedback does that).
 */
object Haptics {
    @Volatile var enabled = true

    /** A light tick: tabs, list rows, chips. */
    const val TAP = 0
    /** Something happened: a stop starred, a bus followed, a time picked. */
    const val CONFIRM = 1
    /** Something failed. */
    const val REJECT = 2

    fun perform(view: View, kind: Int = TAP) {
        if (!enabled) return
        view.performHapticFeedback(
            when {
                kind == CONFIRM && Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
                kind == REJECT && Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.REJECT
                kind == TAP && Build.VERSION.SDK_INT >= 27 -> HapticFeedbackConstants.KEYBOARD_TAP
                else -> HapticFeedbackConstants.VIRTUAL_KEY
            }
        )
    }
}

/** haptic() for a tap, haptic(Haptics.CONFIRM) after an action. */
@Composable
fun rememberHaptic(): (Int) -> Unit {
    val view = LocalView.current
    return remember(view) { { kind: Int -> Haptics.perform(view, kind) } }
}
