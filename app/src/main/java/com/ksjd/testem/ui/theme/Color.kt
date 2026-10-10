package com.ksjd.testem.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Base neutrals. Cool paper and ink rather than warm tones: this app lives
// next to bus-stop signage, not on a menu.
val Paper = Color(0xFFF4F6F8)
val PaperRaised = Color(0xFFFFFFFF)
val PaperSunken = Color(0xFFE7EBF0)
val PaperLine = Color(0xFFCBD3DD)
val Ink = Color(0xFF14181D)
val InkMuted = Color(0xFF56606C)

val Night = Color(0xFF101317)
val NightRaised = Color(0xFF171B21)
val NightSunken = Color(0xFF222831)
val NightLine = Color(0xFF37404B)
val NightInk = Color(0xFFE8ECF1)
val NightInkMuted = Color(0xFFA8B2BE)

val ErrorLight = Color(0xFFC8372D)
val ErrorContainerLight = Color(0xFFFCE3E0)
val OnErrorContainerLight = Color(0xFF5C120C)
val ErrorDark = Color(0xFFFF8A80)
val ErrorContainerDark = Color(0xFF45160F)
val OnErrorContainerDark = Color(0xFFFFDAD5)

/** Colours specific to transit UI that Material's scheme has no slot for. Each can be set by the rider ([com.ksjd.testem.ThemeRole]). */
@Immutable
data class TransitColors(
    /** Departure board background ("asphalt"). */
    val board: Color,
    val boardRow: Color,
    val boardText: Color,
    val boardDim: Color,
    /** Live countdowns on the board. */
    val live: Color,
    val onTime: Color,
    /** A small delay (under 5 minutes). */
    val delay: Color,
    /** 5 minutes or more, and cancelled. */
    val late: Color,
    /** Line-number plate on light surfaces. */
    val plate: Color,
    val onPlate: Color,
    /** The next-bus card on Home. */
    val highlight: Color,
    val onHighlight: Color,
    /** The floating bottom bar. */
    val bar: Color,
    val onBar: Color
)

val LightTransitColors = TransitColors(
    board = Color(0xFF1C2127),
    boardRow = Color(0xFF262C34),
    boardText = Color(0xFFF3F5F7),
    boardDim = Color(0xFF8D97A3),
    live = Color(0xFF6EE7A8),
    onTime = Color(0xFF56606C),
    delay = Color(0xFFB5530A),
    late = Color(0xFFB42318),
    plate = Ink,
    onPlate = Color.White,
    highlight = Color(0xFF157A38),
    onHighlight = Color.White,
    bar = Ink,
    onBar = Color.White
)

val DarkTransitColors = TransitColors(
    board = Color(0xFF0B0E12),
    boardRow = Color(0xFF161B21),
    boardText = Color(0xFFF3F5F7),
    boardDim = Color(0xFF8D97A3),
    live = Color(0xFF6EE7A8),
    onTime = Color(0xFFA8B2BE),
    delay = Color(0xFFFFA866),
    late = Color(0xFFFF6B61),
    plate = Color(0xFFE8ECF1),
    onPlate = Ink,
    highlight = Color(0xFF157A38),
    onHighlight = Color.White,
    bar = Color(0xFF6EE7A8),
    onBar = Color(0xFF0B3D1C)
)

val LocalTransitColors = staticCompositionLocalOf { LightTransitColors }
