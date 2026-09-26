package com.xarvis.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val XarvisCyan = Color(0xFF00E5FF)
val XarvisPurple = Color(0xFF7C4DFF)
val XarvisBlue = Color(0xFF2979FF)
val XarvisBackground = Color(0xFF05080F)
val XarvisSurface = Color(0xFF0D1524)
val XarvisText = Color(0xFFD6F7FF)
val XarvisMuted = Color(0xFF6C8499)

private val colors = darkColorScheme(
    primary = XarvisCyan,
    onPrimary = XarvisBackground,
    secondary = XarvisPurple,
    tertiary = XarvisBlue,
    background = XarvisBackground,
    onBackground = XarvisText,
    surface = XarvisSurface,
    onSurface = XarvisText,
    onSurfaceVariant = XarvisMuted,
)

private val mono = TextStyle(fontFamily = FontFamily.Monospace)

private val typography = Typography(
    titleLarge = mono.copy(fontSize = 24.sp, letterSpacing = 6.sp),
    bodyLarge = mono.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = mono.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = mono.copy(fontSize = 11.sp),
)

@Composable
fun XarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
