package com.tgproxy

import com.tgproxy.Constants
import com.tgproxy.model.ThemeMode
import com.tgproxy.model.normalizeIntervalMinutes
import com.tgproxy.model.normalizeProxyListUrl
import com.tgproxy.model.normalizeThemeMode
import com.tgproxy.model.parseIntervalInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设置 normalize / 输入解析对拍（PRD §7-5 / ARCHITECTURE.md §7 T03）。
 */
class SettingsNormalizeTest {

    @Test
    fun `间隔校 null 回退默认值，越界夹紧到 1 到 1440`() {
        assertEquals(10, normalizeIntervalMinutes(null))
        assertEquals(1, normalizeIntervalMinutes(0))
        assertEquals(1440, normalizeIntervalMinutes(1441))
        assertEquals(1, normalizeIntervalMinutes(-5))
        assertEquals(10, normalizeIntervalMinutes(10))
        assertEquals(1, normalizeIntervalMinutes(1))
        assertEquals(1440, normalizeIntervalMinutes(1440))
    }

    @Test
    fun `数据源地址校 null 空值与非法协议回退默认源`() {
        assertEquals(Constants.DEFAULT_PROXY_LIST_URL, normalizeProxyListUrl(null))
        assertEquals(Constants.DEFAULT_PROXY_LIST_URL, normalizeProxyListUrl(""))
        assertEquals(Constants.DEFAULT_PROXY_LIST_URL, normalizeProxyListUrl("   "))
        assertEquals(Constants.DEFAULT_PROXY_LIST_URL, normalizeProxyListUrl("ftp://x"))
        assertEquals("http://a", normalizeProxyListUrl("http://a"))
        assertEquals("https://a", normalizeProxyListUrl("https://a"))
    }

    @Test
    fun `间隔输入仅接受 1 到 1440 的纯整数`() {
        assertNull(parseIntervalInput(""))
        assertNull(parseIntervalInput("abc"))
        assertNull(parseIntervalInput("0"))
        assertNull(parseIntervalInput("1441"))
        assertNull(parseIntervalInput("10abc"))
        assertNull(parseIntervalInput("-1"))
        assertEquals(10, parseIntervalInput("10"))
        assertEquals(10, parseIntervalInput(" 10 "))
        assertEquals(1, parseIntervalInput("1"))
        assertEquals(1440, parseIntervalInput("1440"))
    }

    @Test
    fun `主题模式校未知值回退 SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, normalizeThemeMode(null))
        assertEquals(ThemeMode.SYSTEM, normalizeThemeMode(""))
        assertEquals(ThemeMode.SYSTEM, normalizeThemeMode("xxx"))
        assertEquals(ThemeMode.SYSTEM, normalizeThemeMode("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, normalizeThemeMode("LIGHT"))
        assertEquals(ThemeMode.LIGHT, normalizeThemeMode("light"))
        assertEquals(ThemeMode.DARK, normalizeThemeMode("DARK"))
        assertEquals(ThemeMode.DARK, normalizeThemeMode("dark"))
    }
}
