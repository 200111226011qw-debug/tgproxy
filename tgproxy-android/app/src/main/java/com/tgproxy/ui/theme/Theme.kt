package com.tgproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.tgproxy.model.ThemeMode

/** 浅色主题：与桌面版 1:1 对齐（PRD 5.1 色板）。 */
private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = SurfaceWhite,
    primaryContainer = BadgeSocks5Bg,
    onPrimaryContainer = BadgeSocks5Fg,
    secondary = SuccessGreen,
    onSecondary = SurfaceWhite,
    background = PageBackground,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceGray,
    onSurfaceVariant = TextSecondary,
    outline = OutlineGray,
    outlineVariant = OutlineGray,
    error = ErrorRed,
    onError = SurfaceWhite,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

/** 深色主题：v2.1 仪表盘视觉（深藏青底 + 青色强调，参考深色数据仪表盘）。 */
private val DarkColors = darkColorScheme(
    primary = AccentCyan,
    onPrimary = NavyBackground,
    primaryContainer = TgBlueDeep,
    onPrimaryContainer = SurfaceWhite,
    secondary = SuccessGreen,
    onSecondary = NavyBackground,
    background = NavyBackground,
    onBackground = TgDarkTextPrimary,
    surface = NavySurface,
    onSurface = TgDarkTextPrimary,
    surfaceVariant = NavySurfaceHigh,
    onSurfaceVariant = NavyTextSecondary,
    outline = NavyOutline,
    outlineVariant = NavyOutline,
    error = DarkError,
    onError = NavyBackground,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

/**
 * 应用主题入口。
 *
 * @param themeMode 主题模式：SYSTEM 跟随系统 / LIGHT 强制浅色 / DARK 强制深色
 */
@Composable
fun TgProxyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TgProxyTypography,
        content = content,
    )
}
