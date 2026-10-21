package com.tgproxy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material3 默认排版（中文场景下系统字体已足够）。 */
val TgProxyTypography = Typography()

/** 代理链接等宽样式（对应桌面版 .link-btn 的 monospace 字体族）。 */
val MonoLinkStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)
