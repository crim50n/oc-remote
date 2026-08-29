package dev.minios.ocremote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R

/**
 * Inter variable font family.
 *
 * Both TTF files are variable fonts covering the full weight axis (100–900).
 * A single entry per style is sufficient — Android resolves the wght axis at
 * runtime when a specific [FontWeight] is requested.
 */
val InterFontFamily = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(R.font.inter_variable_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
)

// Baseline — all M3 defaults, no overrides
private val _default = Typography()

/**
 * Material 3 Typography using Inter.
 *
 * Each style is copied verbatim from [Typography] defaults; only fontFamily
 * is always changed, and letterSpacing is reduced slightly for the styles
 * whose default spacing looks too airy with Inter (positive values ≥ 0.1 sp).
 * Styles with 0 sp or negative default spacing are left completely untouched
 * beyond the font family swap.
 */
val Typography = Typography(
    displayLarge   = _default.displayLarge.copy(fontFamily = InterFontFamily),
    displayMedium  = _default.displayMedium.copy(fontFamily = InterFontFamily),
    displaySmall   = _default.displaySmall.copy(fontFamily = InterFontFamily),
    headlineLarge  = _default.headlineLarge.copy(fontFamily = InterFontFamily),
    headlineMedium = _default.headlineMedium.copy(fontFamily = InterFontFamily),
    headlineSmall  = _default.headlineSmall.copy(fontFamily = InterFontFamily),
    titleLarge     = _default.titleLarge.copy(fontFamily = InterFontFamily),
    titleMedium    = _default.titleMedium.copy(fontFamily = InterFontFamily, letterSpacing = (-0.05).sp),
    titleSmall     = _default.titleSmall.copy(fontFamily = InterFontFamily, letterSpacing = (-0.05).sp),
    bodyLarge      = _default.bodyLarge.copy(fontFamily = InterFontFamily, letterSpacing = (-0.25).sp),
    bodyMedium     = _default.bodyMedium.copy(fontFamily = InterFontFamily, letterSpacing = (-0.1).sp),
    bodySmall      = _default.bodySmall.copy(fontFamily = InterFontFamily, letterSpacing = (-0.15).sp),
    labelLarge     = _default.labelLarge.copy(fontFamily = InterFontFamily, letterSpacing = (-0.05).sp),
    labelMedium    = _default.labelMedium.copy(fontFamily = InterFontFamily, letterSpacing = (-0.2).sp),
    labelSmall     = _default.labelSmall.copy(fontFamily = InterFontFamily, letterSpacing = (-0.2).sp),
)

// Monospace for code blocks
val CodeTypography = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp
)
