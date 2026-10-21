package com.tgproxy

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
}
