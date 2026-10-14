package com.weighttrend.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material You: base colours follow the phone's wallpaper palette (Android 12+).
 * On top of that a small fixed "mood" palette: green when things go well,
 * amber when they need attention, coral when the dynamics are negative.
 */
@Composable
fun WeightTrendTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    CompositionLocalProvider(LocalMoodColors provides if (dark) DarkMood else LightMood) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** How things are going — drives colour and icon of status elements. */
enum class Mood { GOOD, CAUTION, BAD, NEUTRAL }

@Immutable
data class MoodColor(
    /** Lines, icons, numbers. */
    val accent: Color,
    /** Card background. */
    val container: Color,
    /** Text on [container]. */
    val onContainer: Color,
)

@Immutable
data class MoodColors(val good: MoodColor, val caution: MoodColor, val bad: MoodColor)

private val LightMood = MoodColors(
    good = MoodColor(Color(0xFF1F8A5B), Color(0xFFDDF3E6), Color(0xFF0B3B25)),
    caution = MoodColor(Color(0xFFB86E00), Color(0xFFFFEFD3), Color(0xFF4A2C00)),
    bad = MoodColor(Color(0xFFD0475A), Color(0xFFFDE4E7), Color(0xFF5A1220)),
)

private val DarkMood = MoodColors(
    good = MoodColor(Color(0xFF6FD3A0), Color(0xFF123A2A), Color(0xFFD2F5E2)),
    caution = MoodColor(Color(0xFFF3BC66), Color(0xFF3B2B0F), Color(0xFFFCE7C4)),
    bad = MoodColor(Color(0xFFF2909B), Color(0xFF441820), Color(0xFFFBD6DB)),
)

val LocalMoodColors = staticCompositionLocalOf { LightMood }

/** Mood colours; NEUTRAL maps to the dynamic primary palette. */
@Composable
@ReadOnlyComposable
fun moodColor(mood: Mood): MoodColor {
    val m = LocalMoodColors.current
    val c = MaterialTheme.colorScheme
    return when (mood) {
        Mood.GOOD -> m.good
        Mood.CAUTION -> m.caution
        Mood.BAD -> m.bad
        Mood.NEUTRAL -> MoodColor(c.primary, c.surfaceContainerHigh, c.onSurface)
    }
}

/** Soft, rounded shapes everywhere. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Light weights for big numbers, slightly tighter headings. */
private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Light),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Normal),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(letterSpacing = 0.6.sp),
    )
}

/** Numbers that line up (tabular figures). */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")
