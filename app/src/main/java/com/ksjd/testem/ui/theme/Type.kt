package com.ksjd.testem.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ksjd.testem.R

/**
 * Barlow Semi Condensed: a DIN-like grotesque drawn from road and transit
 * signage. Used for headings, line numbers and times; body copy stays in the
 * system sans for long-form legibility.
 */
val Barlow = FontFamily(
    Font(R.font.barlow_sc_medium, FontWeight.Medium),
    Font(R.font.barlow_sc_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_sc_bold, FontWeight.Bold)
)

private const val TABULAR = "tnum"

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 56.sp, fontFeatureSettings = TABULAR),
    displayMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, fontFeatureSettings = TABULAR),
    displaySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp, fontFeatureSettings = TABULAR),
    headlineLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp)
)

/** Times and countdowns: tabular figures so columns of times line up. */
val TimeStyle = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, fontFeatureSettings = TABULAR)

/** Line number on a plate. */
val PlateStyle = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 18.sp, fontFeatureSettings = TABULAR)
