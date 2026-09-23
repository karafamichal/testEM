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

/** Colours specific to transit UI that Material's scheme has no slot for. */
@Immutable
data class TransitColors(
    /** Departure board background ("asphalt"). */
    val board: Color,
    val boardRow: Color,
    val boardText: Color,
    val boardDim: Color,
    /** Live countdowns only. */
    val amber: Color,
    val onTime: Color,
    val late: Color,
    /** Line-number plate on light surfaces. */
    val plate: Color,
    val onPlate: Color
)

val LightTransitColors = TransitColors(
    board = Color(0xFF1C2127),
    boardRow = Color(0xFF262C34),
    boardText = Color(0xFFF3F5F7),
    boardDim = Color(0xFF8D97A3),
    amber = Color(0xFFFFB21E),
    onTime = Color(0xFF1F8A55),
    late = Color(0xFFC8372D),
    plate = Ink,
    onPlate = Color.White
)

val DarkTransitColors = TransitColors(
    board = Color(0xFF0B0E12),
    boardRow = Color(0xFF161B21),
    boardText = Color(0xFFF3F5F7),
    boardDim = Color(0xFF8D97A3),
    amber = Color(0xFFFFB21E),
    onTime = Color(0xFF4CC38A),
    late = Color(0xFFFF6B61),
    plate = Color(0xFFE8ECF1),
    onPlate = Ink
)

val LocalTransitColors = staticCompositionLocalOf { LightTransitColors }
