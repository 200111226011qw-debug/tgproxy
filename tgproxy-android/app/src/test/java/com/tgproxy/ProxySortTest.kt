package com.tgproxy

import com.tgproxy.data.ProxyRepository
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.model.TgVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排序对拍桌面版 sort_proxies（PRD §7-2 / ARCHITECTURE.md §7 T03；v2.0 双闸版）。
 *
 * 排序键：TG 实测通过在前 → TG 延迟升序（null 排同组末尾）→ host → port。
 */
class ProxySortTest {

    private val repository = ProxyRepository(FakeSourceForSort())

    private fun entry(
        host: String,
        port: Int,
        tgVerdict: TgVerdict,
        latencyMs: Int?,
    ): ProxyEntry = ProxyEntry(
        link = "socks5://$host:$port",
        host = host,
        port = port,
        type = ProxyType.SOCKS5,
        latencyMs = latencyMs,
        status = if (tgVerdict == TgVerdict.PASS) ProxyStatus.AVAILABLE else ProxyStatus.UNAVAILABLE,
        tgVerdict = tgVerdict,
    )

    @Test
    fun `TG 通过在前、同组按延迟升序、未通过整体在后`() {
        val input = listOf(
            entry("2.0.0.1", 100, TgVerdict.FAIL, 50),
            entry("1.0.0.1", 200, TgVerdict.PASS, 100),
            entry("1.0.0.2", 300, TgVerdict.PASS, 300),
        )

        val sorted = repository.sortProxies(input)

        assertEquals(
            listOf("socks5://1.0.0.1:200", "socks5://1.0.0.2:300", "socks5://2.0.0.1:100"),
            sorted.map { it.link },
        )
    }

    @Test
    fun `未验证（MTProto）排在 TG 通过组之后`() {
        val input = listOf(
            entry("3.0.0.1", 300, TgVerdict.UNVERIFIED, null),
            entry("1.0.0.1", 100, TgVerdict.PASS, 200),
            entry("2.0.0.1", 200, TgVerdict.FAIL, null),
        )

        val sorted = repository.sortProxies(input)

        assertEquals(
            listOf(
                "socks5://1.0.0.1:100", // PASS 组
                "socks5://2.0.0.1:200", // FAIL（有延迟/null 同按规则）
                "socks5://3.0.0.1:300", // UNVERIFIED 组末尾
            ),
            sorted.map { it.link },
        )
    }

    @Test
    fun `latency 为 null 排在同组末尾`() {
        val input = listOf(
            entry("3.0.0.1", 300, TgVerdict.PASS, null),
            entry("1.0.0.1", 100, TgVerdict.PASS, 200),
            entry("2.0.0.1", 200, TgVerdict.FAIL, null),
            entry("4.0.0.1", 400, TgVerdict.FAIL, 30),
        )

        val sorted = repository.sortProxies(input)

        assertEquals(
            listOf(
                "socks5://1.0.0.1:100", // PASS 组：有延迟在前
                "socks5://3.0.0.1:300", // PASS 组：null 在末尾
                "socks5://4.0.0.1:400", // FAIL 组：有延迟在前
                "socks5://2.0.0.1:200", // FAIL 组：null 在末尾
            ),
            sorted.map { it.link },
        )
    }

    @Test
    fun `延迟相同按 host 再按 port 兜底排序`() {
        val input = listOf(
            entry("1.0.0.1", 200, TgVerdict.PASS, 100),
            entry("1.0.0.0", 100, TgVerdict.PASS, 100),
            entry("1.0.0.0", 50, TgVerdict.PASS, 100),
        )

        val sorted = repository.sortProxies(input)

        assertEquals(
            listOf(
                "socks5://1.0.0.0:50",
                "socks5://1.0.0.0:100",
                "socks5://1.0.0.1:200",
            ),
            sorted.map { it.link },
        )
    }

    @Test
    fun `排序返回新列表且不修改入参`() {
        val input = listOf(
            entry("2.0.0.1", 100, TgVerdict.FAIL, 50),
            entry("1.0.0.1", 200, TgVerdict.PASS, 100),
        )
        val snapshot = input.map { it.link }

        val sorted = repository.sortProxies(input)

        assertEquals(snapshot, input.map { it.link })
        assertEquals(
            listOf("socks5://1.0.0.1:200", "socks5://2.0.0.1:100"),
            sorted.map { it.link },
        )
    }

    @Test
    fun `空列表排序返回空列表`() {
        assertEquals(emptyList<ProxyEntry>(), repository.sortProxies(emptyList()))
    }
}

/** 排序测试专用假数据源（不会被调用，仅满足构造签名）。 */
private class FakeSourceForSort : com.tgproxy.data.ProxySource {
    override suspend fun fetch(url: String): String = ""
}
