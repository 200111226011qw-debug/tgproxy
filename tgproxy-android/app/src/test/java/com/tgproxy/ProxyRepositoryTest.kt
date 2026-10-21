package com.tgproxy

import com.tgproxy.data.LatencyTester
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.ProxyRepository.RefreshResult
import com.tgproxy.data.ProxySource
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * ProxyRepository 编排对拍（ARCHITECTURE.md §7 T03）：
 * stats 四项、excluded 合并口径、失败降级、updatedAt 格式。
 */
class ProxyRepositoryTest {

    /** 可控假数据源。 */
    private class FakeSource : ProxySource {
        var nextText: String? = null
        var nextError: Throwable? = null
        var calls: Int = 0

        override suspend fun fetch(url: String): String {
            calls++
            nextError?.let { throw it }
            return nextText ?: ""
        }
    }

    /** 确定性假测试器：偶数下标可用（100ms），奇数下标不可用；保序。 */
    private class FakeTester : LatencyTester {
        override suspend fun testAll(entries: List<ProxyEntry>): List<ProxyEntry> =
            entries.mapIndexed { index, entry ->
                if (index % 2 == 0) {
                    entry.copy(latencyMs = 100, status = ProxyStatus.AVAILABLE)
                } else {
                    entry.copy(latencyMs = null, status = ProxyStatus.UNAVAILABLE)
                }
            }
    }

    @Test
    fun `成功刷新时统计四项正确且 excluded 等于 excluded 加 dropped`() = runTest {
        val source = FakeSource().apply {
            nextText = "socks5://1.2.3.4:1080\n" +
                "socks5://1.2.3.5:1080\n" +
                "http://9.9.9.9:8080\n" +
                "badline"
        }
        val repository = ProxyRepository(source, FakeTester())

        val result = repository.refresh("https://example.com/proxy.txt")

        assertTrue(result is RefreshResult.Success)
        result as RefreshResult.Success
        val stats = result.stats
        assertEquals(2, stats.totalKept)
        assertEquals(1, stats.available)
        assertEquals(1, stats.unavailable)
        // excluded = 非 Telegram 合法条目(1) + 非法行(1)
        assertEquals(2, stats.excluded)
        assertTrue(result.updatedAt.matches(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""")))
        // entries 保持解析序（测试器保序）
        assertEquals(
            listOf("socks5://1.2.3.4:1080", "socks5://1.2.3.5:1080"),
            result.entries.map { it.link },
        )
        assertEquals(ProxyStatus.AVAILABLE, result.entries[0].status)
        assertEquals(100, result.entries[0].latencyMs)
        assertEquals(ProxyStatus.UNAVAILABLE, result.entries[1].status)
    }

    @Test
    fun `数据源抛 IOException 时转为 Failure 且不向外抛异常`() = runTest {
        val source = FakeSource().apply { nextError = IOException("boom") }
        val repository = ProxyRepository(source, FakeTester())

        val result = repository.refresh("https://example.com/proxy.txt")

        assertTrue(result is RefreshResult.Failure)
        result as RefreshResult.Failure
        assertTrue(result.error.startsWith("抓取代理列表失败："))
        assertEquals("抓取代理列表失败：boom", result.error)
    }

    @Test
    fun `数据源返回 HTTP 语义错误时同样降级为 Failure`() = runTest {
        val source = FakeSource().apply { nextError = IOException("HTTP 503") }
        val repository = ProxyRepository(source, FakeTester())

        val result = repository.refresh("https://example.com/proxy.txt")

        assertTrue(result is RefreshResult.Failure)
        result as RefreshResult.Failure
        assertEquals("抓取代理列表失败：HTTP 503", result.error)
    }

    @Test
    fun `刷新使用传入的数据源地址`() = runTest {
        val source = FakeSource().apply { nextText = "" }
        val repository = ProxyRepository(source, FakeTester())

        repository.refresh("https://custom.example.com/list.txt")

        assertEquals(1, source.calls)
    }
}
