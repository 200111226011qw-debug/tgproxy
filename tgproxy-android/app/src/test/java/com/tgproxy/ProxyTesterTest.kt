package com.tgproxy

import com.tgproxy.data.ProxyTester
import com.tgproxy.data.testAll
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * ProxyTester 对拍桌面版 test_tcp_latency + Semaphore(20)（ARCHITECTURE.md §7 T03）。
 */
class ProxyTesterTest {

    private lateinit var server: ServerSocket

    @Before
    fun setUp() {
        // 监听随机本地端口，作为「可用」目标
        server = ServerSocket(0)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun entry(host: String, port: Int): ProxyEntry = ProxyEntry(
        link = "socks5://$host:$port",
        host = host,
        port = port,
        type = ProxyType.SOCKS5,
    )

    @Test
    fun `监听端口可连通并记录延迟`() = runTest {
        val tester = ProxyTester(maxConcurrency = 20, timeoutMs = 5_000)

        val results = tester.testAll(listOf(entry("127.0.0.1", server.localPort)))

        assertEquals(1, results.size)
        assertEquals(ProxyStatus.AVAILABLE, results[0].status)
        assertNotNull(results[0].latencyMs)
    }

    @Test
    fun `已关闭端口不可连通且延迟为空`() = runTest {
        val tester = ProxyTester(maxConcurrency = 20, timeoutMs = 5_000)

        val results = tester.testAll(listOf(entry("127.0.0.1", 1)))

        assertEquals(1, results.size)
        assertEquals(ProxyStatus.UNAVAILABLE, results[0].status)
        assertNull(results[0].latencyMs)
    }

    @Test
    fun `50 条混合条目顺序保持且数量守恒`() = runTest {
        val tester = ProxyTester(maxConcurrency = 20, timeoutMs = 5_000)
        val entries = (0 until 50).map { index ->
            if (index % 2 == 0) {
                entry("127.0.0.1", server.localPort)
            } else {
                entry("127.0.0.1", 1)
            }
        }

        val results = tester.testAll(entries)

        assertEquals(50, results.size)
        // 顺序与输入一致
        assertEquals(entries.map { it.link }, results.map { it.link })
        // 25 可用 + 25 不可用
        assertEquals(25, results.count { it.status == ProxyStatus.AVAILABLE })
        assertEquals(25, results.count { it.status == ProxyStatus.UNAVAILABLE })
        // 可用项均有延迟，不可用项均为 null
        assert(results.all { entry ->
            if (entry.status == ProxyStatus.AVAILABLE) {
                entry.latencyMs != null
            } else {
                entry.latencyMs == null
            }
        }
        )
    }

    @Test
    fun `空列表直接返回空结果`() = runTest {
        val tester = ProxyTester()
        assertEquals(emptyList<ProxyEntry>(), tester.testAll(emptyList()))
    }
}
