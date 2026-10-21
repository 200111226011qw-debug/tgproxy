package com.tgproxy

import com.tgproxy.data.ProxyRepository
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排序对拍桌面版 sort_proxies（PRD §7-2 / ARCHITECTURE.md §7 T03）。
 *
 * 排序三键：可用在前 → 延迟升序（null 排同组末尾）→ host → port。
 */
class ProxySortTest {

    private val repository = ProxyRepository(FakeSourceForSort())

    private fun entry(
        host: String,
        port: Int,
        status: ProxyStatus,
        latencyMs: Int?,
    ): ProxyEntry = ProxyEntry(
        link = "socks5://$host:$port",
        host = host,
        port = port,
        type = ProxyType.SOCKS5,
        latencyMs = latencyMs,
        status = status,
    )

    @Test
    fun `可用在前、同可用按延迟升序、不可用整体在后`() {
        val input = listOf(
            entry("2.0.0.1", 100, ProxyStatus.UNAVAILABLE, 50),
            entry("1.0.0.1", 200, ProxyStatus.AVAILABLE, 100),
            entry("1.0.0.2", 300, ProxyStatus.AVAILABLE, 300),
        )

        val sorted = repository.sortProxies(input)

        assertEquals(
            listOf("socks5://1.0.0.1:200", "socks5://1.0.0.2:300", "socks5://2.0.0.1:100"),
            sorted.map { it.link },
        )
    }

    @Test
    fun `latency 为 null 排在同组末尾`() {
        val input = listOf(
            entry("3.0.0.1", 300, ProxyStatus.AVAILABLE, null),
            entry("1.0.0.1", 100, ProxyStatus.AVAILABLE, 200),
            entry("2.0.0.1", 200, ProxyStatus.UNAVAILABLE, null),
            entry("4.0.0.1", 400, ProxyStatus.UNAVAILABLE, 30),
        )

        val sorted = repository.sortProxies(input)

        assertEquals(
            listOf(
                "socks5://1.0.0.1:100", // 可用组：有延迟在前
                "socks5://3.0.0.1:300", // 可用组：null 在末尾
                "socks5://4.0.0.1:400", // 不可用组：有延迟在前
                "socks5://2.0.0.1:200", // 不可用组：null 在末尾
            ),
            sorted.map { it.link },
        )
    }

    @Test
    fun `延迟相同按 host 再按 port 兜底排序`() {
        val input = listOf(
            entry("1.0.0.1", 200, ProxyStatus.AVAILABLE, 100),
            entry("1.0.0.0", 100, ProxyStatus.AVAILABLE, 100),
            entry("1.0.0.0", 50, ProxyStatus.AVAILABLE, 100),
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
            entry("2.0.0.1", 100, ProxyStatus.UNAVAILABLE, 50),
            entry("1.0.0.1", 200, ProxyStatus.AVAILABLE, 100),
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
