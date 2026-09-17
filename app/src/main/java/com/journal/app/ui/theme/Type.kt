package com.journal.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Typography tuned for Georgian (Mkhedruli).
 *
 * Georgian glyphs have a tall x-height with many descenders and no capitals, so the default
 * Material scale reads cramped. Body text is therefore set at 17sp with a 1.5× line height (25sp)
 * and letter spacing pulled back to 0, which is where Mkhedruli sits most comfortably. Line height
 * is trimmed at neither edge so multi-line entries keep an even rhythm.
 */
private val GeorgianLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private val Georgian = FontFamily.Default

val JournalTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    headlineMedium = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.1).sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    headlineSmall = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    titleLarge = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    titleMedium = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    titleSmall = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    // The reading workhorse: 17sp / 25sp ≈ 1.5× leading.
    bodyLarge = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    bodyMedium = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    bodySmall = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    labelLarge = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    labelMedium = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    ),
    labelSmall = TextStyle(
        fontFamily = Georgian,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        lineHeightStyle = GeorgianLineHeightStyle
    )
)
