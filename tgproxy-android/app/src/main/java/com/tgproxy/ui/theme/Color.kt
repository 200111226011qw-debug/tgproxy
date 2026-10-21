package com.tgproxy.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// 桌面版色板 1:1 对齐（static/index.html）
// ============================================================================

/** 主色：按钮/链接/统计卡左边条（桌面版 #2563EB）。 */
val Primary = Color(0xFF2563EB)

/** 可用/成功（桌面版 #16A34A）。 */
val SuccessGreen = Color(0xFF16A34A)

/** 警告/中延迟（桌面版 #D97706）。 */
val WarningOrange = Color(0xFFD97706)

/** 错误（桌面版 #DC2626）。 */
val ErrorRed = Color(0xFFDC2626)

/** 页面背景（桌面版 #F4F6FA）。 */
val PageBackground = Color(0xFFF4F6FA)

/** 卡片/表格底色（桌面版 #FFFFFF）。 */
val SurfaceWhite = Color(0xFFFFFFFF)

/** 卡片描边（桌面版 #E5E7EB）。 */
val OutlineGray = Color(0xFFE5E7EB)

/** 主文本（桌面版 #1F2937）。 */
val TextPrimary = Color(0xFF1F2937)

/** 次要文本（桌面版 #6B7280）。 */
val TextSecondary = Color(0xFF6B7280)

/** 浅灰表面（桌面版 #F9FAFB：表头/悬浮底色）。 */
val SurfaceGray = Color(0xFFF9FAFB)

/** 错误条浅色 容器/内容（桌面版 .error-banner #FEE2E2 / #B91C1C）。 */
val ErrorContainerLight = Color(0xFFFEE2E2)
val OnErrorContainerLight = Color(0xFFB91C1C)

// ============================================================================
// 徽章配色（浅色，与桌面版 .badge-* 一一对应）
// ============================================================================

/** 可用徽章 背景/前景（桌面版 #DCFCE7 / #15803D）。 */
val BadgeAvailableBg = Color(0xFFDCFCE7)
val BadgeAvailableFg = Color(0xFF15803D)

/** 不可用徽章 背景/前景（桌面版 #F3F4F6 / #6B7280）。 */
val BadgeUnavailableBg = Color(0xFFF3F4F6)
val BadgeUnavailableFg = Color(0xFF6B7280)

/** SOCKS5 徽章 背景/前景（桌面版 #DBEAFE / #1D4ED8）。 */
val BadgeSocks5Bg = Color(0xFFDBEAFE)
val BadgeSocks5Fg = Color(0xFF1D4ED8)

/** MTProto 徽章 背景/前景（桌面版 #EDE9FE / #6D28D9）。 */
val BadgeMtprotoBg = Color(0xFFEDE9FE)
val BadgeMtprotoFg = Color(0xFF6D28D9)

// ============================================================================
// 延迟三档着色（桌面版 .lat-*）
// ============================================================================

/** <200ms 绿（桌面版 #16A34A）。 */
val LatencyGreen = Color(0xFF16A34A)

/** <600ms 橙（桌面版 #D97706）。 */
val LatencyOrange = Color(0xFFD97706)

/** ≥600ms 红（桌面版 #DC2626）。 */
val LatencyRed = Color(0xFFDC2626)

/** 失败/无数据灰（桌面版 #9CA3AF）。 */
val LatencyNone = Color(0xFF9CA3AF)

// ============================================================================
// 深色主题派生（同色系降亮度变体，移动端增强 P1-4）
// ============================================================================

/** 深色主色（亮化 #2563EB）。 */
val DarkPrimary = Color(0xFF93B8FF)

/** 深色页面背景。 */
val DarkBackground = Color(0xFF111827)

/** 深色卡片表面。 */
val DarkSurface = Color(0xFF1F2937)

/** 深色表面变体。 */
val DarkSurfaceVariant = Color(0xFF374151)

/** 深色主/次文本。 */
val DarkTextPrimary = Color(0xFFE5E7EB)
val DarkTextSecondary = Color(0xFF9CA3AF)

/** 深色描边。 */
val DarkOutline = Color(0xFF4B5563)

/** 深色错误容器/内容（桌面版 #FEE2E2 / #B91C1C 的暗色变体）。 */
val DarkError = Color(0xFFF87171)
val DarkErrorContainer = Color(0xFF451A1A)
val DarkOnErrorContainer = Color(0xFFFECACA)
