package com.magplay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.magplay.data.AppPrefs

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

/**
 * 主题：跟随系统 / 强制浅色 / 强制深色，由设置页决定（AppPrefs.themeMode）。
 * 改设置时 AppPrefs 里的状态变化会触发这里重组，不用重启 App。
 */
@Composable
fun MagPlayTheme(content: @Composable () -> Unit) {
    val dark = when (AppPrefs.themeMode) {
        AppPrefs.THEME_LIGHT -> false
        AppPrefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

/** 当前配色是不是深色底。只用来挑图标底色，跟随 MaterialTheme 而不是跟随系统。 */
@Composable
fun isDarkColors(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * 文件类型对应的图标底色 / 前景色（卡片左侧色块）。
 * 必须跟主题走：之前写死浅色值，深色模式下这两个色块亮得刺眼。
 */
object FileTint {
    @Composable
    fun folderBg(): Color = if (isDarkColors()) Color(0xFF3B2F14) else Color(0xFFFFF1D6)

    @Composable
    fun folderFg(): Color = if (isDarkColors()) Color(0xFFFFC24B) else Color(0xFFE8920C)

    @Composable
    fun videoBg(): Color = if (isDarkColors()) Color(0xFF28357A) else Color(0xFFE4E9FF)

    @Composable
    fun videoFg(): Color = if (isDarkColors()) Color(0xFF9FB4FF) else Color(0xFF3B5BDB)
}
