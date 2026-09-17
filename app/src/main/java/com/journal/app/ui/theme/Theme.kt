package com.journal.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** Soft rounded corners — 14dp on cards, matched to the hairline border. */
val JournalShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Card corner radius exposed for components that draw their own border. */
val CardCornerRadius = 14.dp

/** The active accent, readable anywhere in the tree without threading it through parameters. */
val LocalAccent = staticCompositionLocalOf { AccentColor.CYAN }

/**
 * The app is dark-only by design — a journal written at night should never flash white. The
 * [isSystemInDarkTheme] value is deliberately ignored.
 */
@Composable
fun MindJournalTheme(
    accent: AccentColor = AccentColor.CYAN,
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = accent.color,
        onPrimary = OnAccent,
        primaryContainer = accent.container(),
        onPrimaryContainer = accent.color,
        secondary = accent.color,
        onSecondary = OnAccent,
        secondaryContainer = accent.container(),
        onSecondaryContainer = accent.color,
        tertiary = accent.color,
        onTertiary = OnAccent,
        background = MatteBackground,
        onBackground = TextPrimary,
        surface = MatteBackground,
        onSurface = TextPrimary,
        surfaceVariant = CardSurface,
        onSurfaceVariant = TextSecondary,
        surfaceContainer = CardSurface,
        surfaceContainerHigh = ElevatedSurface,
        surfaceContainerHighest = ElevatedSurface,
        surfaceContainerLow = CardSurface,
        surfaceContainerLowest = MatteBackground,
        inverseSurface = TextPrimary,
        inverseOnSurface = MatteBackground,
        outline = CardBorder,
        outlineVariant = CardBorder,
        error = ErrorRed,
        onError = OnAccent,
        errorContainer = ErrorRed.copy(alpha = 0.16f),
        onErrorContainer = ErrorRed,
        scrim = MatteBackground.copy(alpha = 0.72f)
    )

    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = JournalTypography,
            shapes = JournalShapes,
            content = content
        )
    }
}
