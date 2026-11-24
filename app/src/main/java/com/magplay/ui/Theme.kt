package com.magplay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 一套克制的蓝灰配色，替代默认的紫色 Material 主题
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E9FF),
    onPrimaryContainer = Color(0xFF1B2F7B),
    secondary = Color(0xFF0CA678),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F5E9),
    onSecondaryContainer = Color(0xFF075845),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF1C1E24),
    surface = Color.White,
    onSurface = Color(0xFF1C1E24),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF646B79),
    outline = Color(0xFFD8DCE4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FB4FF),
    onPrimary = Color(0xFF0A1A55),
    primaryContainer = Color(0xFF28357A),
    onPrimaryContainer = Color(0xFFDFE5FF),
    secondary = Color(0xFF5EE0B8),
    onSecondary = Color(0xFF00382B),
    background = Color(0xFF121418),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF1C1F25),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF272B33),
    onSurfaceVariant = Color(0xFFA8AFBD),
    outline = Color(0xFF3A3F49),
)

@Composable
fun MagPlayTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}

// 文件类型对应的图标底色 / 前景色（卡片左侧色块）
object FileTint {
    val FOLDER_BG = Color(0xFFFFF1D6)
    val FOLDER_FG = Color(0xFFE8920C)
    val VIDEO_BG = Color(0xFFE4E9FF)
    val VIDEO_FG = Color(0xFF3B5BDB)
}
