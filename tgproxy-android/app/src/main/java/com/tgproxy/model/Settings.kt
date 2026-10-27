package com.tgproxy.model

import com.tgproxy.Constants

/** 主题模式：跟随系统 / 浅色 / 深色（P1-4，持久化键 theme_mode）。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 应用设置（与桌面版 config.json 的安卓子集对应）。
 *
 * @property intervalMinutes 自动更新间隔（分钟，默认 10）
 * @property proxyListUrl 数据源地址
 * @property themeMode 主题模式
 * @property knownGoodEnabled known_good 沉淀开关（默认开，等价桌面版 v1.8.0）
 */
data class Settings(
    val intervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val proxyListUrl: String = Constants.DEFAULT_PROXY_LIST_URL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val knownGoodEnabled: Boolean = true,
)

/**
 * 校正间隔分钟数：null 回退默认值，越界夹紧至 1~1440（等价桌面版 normalize_config）。
 */
fun normalizeIntervalMinutes(raw: Int?): Int {
    val value = raw ?: return Constants.DEFAULT_INTERVAL_MINUTES
    return value.coerceIn(Constants.MIN_INTERVAL_MINUTES, Constants.MAX_INTERVAL_MINUTES)
}

/**
 * 校正数据源地址：null / 空 / 纯空白 / 非 http(s):// 开头一律回退默认源。
 */
fun normalizeProxyListUrl(raw: String?): String {
    val trimmed = raw?.trim()
    if (trimmed.isNullOrEmpty()) return Constants.DEFAULT_PROXY_LIST_URL
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return Constants.DEFAULT_PROXY_LIST_URL
    }
    return trimmed
}

/**
 * 校正主题模式：未知值回退 SYSTEM。
 */
fun normalizeThemeMode(raw: String?): ThemeMode =
    when (raw?.trim()?.uppercase()) {
        "LIGHT" -> ThemeMode.LIGHT
        "DARK" -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

/**
 * 解析间隔输入框文本：仅接受 1~1440 的纯整数（去除首尾空白），其余返回 null。
 *
 * 比桌面版 `parseInt` 更严格（"10abc" 之类一律拒绝），对应 PRD P0-7。
 */
fun parseIntervalInput(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toIntOrNull() ?: return null
    return if (value in Constants.MIN_INTERVAL_MINUTES..Constants.MAX_INTERVAL_MINUTES) value else null
}
