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

// ============================================================================
// Telegram 品牌色（v2.0 界面重做：主视觉统一为 Telegram 蓝）
// ============================================================================

/** Telegram 品牌蓝（telegram.org #2AABEE）。 */
val TgBlue = Color(0xFF2AABEE)

/** Telegram 品牌蓝深变体（按钮按压/渐变端点 #229ED9）。 */
val TgBlueDeep = Color(0xFF229ED9)

/** Telegram 深色聊天背景（#17212B）。 */
val TgDarkBackground = Color(0xFF17212B)

/** Telegram 深色消息气泡底（#232E3C）。 */
val TgDarkSurface = Color(0xFF232E3C)

/** Telegram 深色次级面板（#2C3A4B）。 */
val TgDarkSurfaceHigh = Color(0xFF2C3A4B)

/** 深色下列表分隔/描边。 */
val TgDarkOutline = Color(0xFF35455A)

/** 深色下主文本（#EAF0F4 偏白）。 */
val TgDarkTextPrimary = Color(0xFFEAF0F4)

/** 深色下次要文本（#7D8E9E）。 */
val TgDarkTextSecondary = Color(0xFF7D8E9E)

// ============================================================================
// v2.1 仪表盘视觉（参考深色数据仪表盘风格：深藏青底 + 蓝→青渐变 + 霓虹点缀）
// ============================================================================

/** 页面深藏青底（参考 #0D1526）。 */
val NavyBackground = Color(0xFF0D1526)

/** 卡片深藏青面（比背景亮一级）。 */
val NavySurface = Color(0xFF141F36)

/** 次级面板/输入底（再亮一级）。 */
val NavySurfaceHigh = Color(0xFF1C2A47)

/** 深藏青系描边/分隔。 */
val NavyOutline = Color(0xFF27365C)

/** 仪表盘强调青（渐变端点/选中态 #00E5D0）。 */
val AccentCyan = Color(0xFF00E5D0)

/** 仪表盘强调粉（超门槛等警示高亮 #FF4D8D）。 */
val AccentPink = Color(0xFFFF4D8D)

/** 仪表盘次级文本（灰蓝 #8A94A6）。 */
val NavyTextSecondary = Color(0xFF8A94A6)
