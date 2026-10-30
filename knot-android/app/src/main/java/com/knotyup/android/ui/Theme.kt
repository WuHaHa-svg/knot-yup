package com.knotyup.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 主题色，与桌面端 THEMES 一致。 */
val THEMES = listOf(
    "indigo" to Color(0xFF5B5BD6),
    "blue" to Color(0xFF3B82F6),
    "teal" to Color(0xFF14B8A6),
    "green" to Color(0xFF10B981),
    "orange" to Color(0xFFF59E0B),
    "rose" to Color(0xFFF43F5E),
    "pink" to Color(0xFFEC4899),
    "violet" to Color(0xFF8B5CF6),
    "blackgold" to Color(0xFFC9A227),
)

private fun scheme(accent: Color, dark: Boolean): ColorScheme {
    val soft = accent.copy(alpha = if (dark) 0.22f else 0.10f)
    return if (dark) darkColorScheme(
        primary = accent,
        primaryContainer = soft,
        surface = Color(0xFF171A20),
        background = Color(0xFF0F1115),
        surfaceVariant = Color(0xFF1D2129),
        outlineVariant = Color(0xFF272C36),
    ) else lightColorScheme(
        primary = accent,
        primaryContainer = soft,
        surface = Color(0xFFFFFFFF),
        background = Color(0xFFF6F7F9),
        surfaceVariant = Color(0xFFFBFBFC),
        outlineVariant = Color(0xFFE8EAEE),
    )
}

@Composable
fun KnotYupTheme(themeId: String?, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val accent = (THEMES.firstOrNull { it.first == themeId } ?: THEMES.first()).second
    MaterialTheme(colorScheme = scheme(accent, dark), content = content)
}
