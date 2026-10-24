package com.tgproxy

import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.ui.toTelegramProxyUrl
import com.tgproxy.ui.toTelegramScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * toTelegramScheme 纯函数对拍（ARCHITECTURE.md §7 T03）。
 */
class ProxyLinksTest {

    @Test
    fun `tg 开头链接原样返回`() {
        val link = "tg://proxy?server=1.2.3.4&port=443&secret=abc"
        assertEquals(link, toTelegramScheme(link))
    }

    @Test
    fun `https t me proxy 链接转换为 tg scheme`() {
        assertEquals(
            "tg://proxy?server=1.2.3.4&port=443&secret=abc",
            toTelegramScheme("https://t.me/proxy?server=1.2.3.4&port=443&secret=abc"),
        )
    }

    @Test
    fun `http www t me proxy 链接转换为 tg scheme`() {
        assertEquals(
            "tg://proxy?server=1.2.3.4&port=443&secret=abc",
            toTelegramScheme("http://www.t.me/proxy?server=1.2.3.4&port=443&secret=abc"),
        )
    }

    @Test
    fun `socks5 链接原样返回`() {
        val link = "socks5://1.2.3.4:1080"
        assertEquals(link, toTelegramScheme(link))
    }

    @Test
    fun `非 t me 的 http 链接原样返回`() {
        val link = "https://example.com/proxy?server=1.2.3.4&port=443&secret=abc"
        assertEquals(link, toTelegramScheme(link))
    }

    /** 构造测试用代理条目。 */
    private fun entry(type: ProxyType, link: String, host: String, port: Int) = ProxyEntry(
        link = link,
        host = host,
        port = port,
        type = type,
        latencyMs = 100,
        status = ProxyStatus.AVAILABLE,
    )

    @Test
    fun `socks5 条目转换为 tg proxy 的 server port 链接`() {
        val url = toTelegramProxyUrl(
            entry(ProxyType.SOCKS5, "socks5://1.2.3.4:1080", "1.2.3.4", 1080),
        )
        assertEquals("tg://proxy?server=1.2.3.4&port=1080", url)
    }

    @Test
    fun `mtproto 条目沿用自带 secret 的 tg 链接`() {
        val link = "tg://proxy?server=1.2.3.4&port=443&secret=abc"
        val url = toTelegramProxyUrl(
            entry(ProxyType.MTPROTO, link, "1.2.3.4", 443),
        )
        assertEquals(link, url)
    }
}
