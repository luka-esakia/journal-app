package com.journal.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.journal.app.R

// ------------------------------------------------------------------ neutrals

/** Deep matte gray — the app never uses pure black, which smears on OLED scrolling. */
val MatteBackground = Color(0xFF121212)

/** Card surface, one step above the background. */
val CardSurface = Color(0xFF1E1E1E)

/** A slightly lifted surface for sheets, dialogs and the bottom bar. */
val ElevatedSurface = Color(0xFF232323)

/** 1px hairline that separates a card from the background without a shadow. */
val CardBorder = Color(0xFF2C2C2C)

/** Soft off-white — #FFFFFF on #121212 is harsh in long reading sessions. */
val TextPrimary = Color(0xFFE0E0E0)
val TextSecondary = Color(0xFF9E9E9E)
val TextTertiary = Color(0xFF6B6B6B)

val ErrorRed = Color(0xFFFF6E6E)

/** Text/icon color drawn on top of an accent fill. */
val OnAccent = Color(0xFF0C0C0C)

// -------------------------------------------------------------------- accents

/**
 * The user-selectable accent palette. Every accent is a saturated hue that stays legible at 1px
 * hairline width on [MatteBackground].
 */
enum class AccentColor(
    val color: Color,
    @StringRes val labelRes: Int
) {
    CYAN(Color(0xFF00E5FF), R.string.accent_cyan),
    GREEN(Color(0xFF00E676), R.string.accent_green),
    AMBER(Color(0xFFFFC400), R.string.accent_amber),
    PURPLE(Color(0xFFD500F9), R.string.accent_purple),
    CORAL(Color(0xFFFF6E40), R.string.accent_coral);

    /** Low-opacity fill used by chips and selected states. */
    fun container(): Color = color.copy(alpha = 0.14f)

    /** Hairline border in the accent hue. */
    fun outline(): Color = color.copy(alpha = 0.45f)

    companion object {
        fun fromKey(key: String?): AccentColor =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: CYAN
    }
}
