package com.tgproxy

import com.tgproxy.data.KnownGoodStore
import com.tgproxy.data.LatencyTester
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.ProxyRepository.RefreshResult
import com.tgproxy.data.ProxySource
import com.tgproxy.data.TelegramGateCaller
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.RefreshStage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProxyRepository 编排对拍（ARCHITECTURE.md §7 T03；v2.0 双闸版）：
 * 五项统计口径、TG 门槛、进度序列、known_good 沉淀、失败降级、updatedAt 格式。
 *
 * 全部协程共用同一 [StandardTestDispatcher]（runTest 与仓储 ioDispatcher 同源），
 * 避免真实 IO 线程与测试调度器之间的跨线程派发竞态。
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

    /** 全通过 TCP 假测试器（TG 阶段行为由假闸门控制）。 */
    private class AllPassTester : LatencyTester {
        override suspend fun testAll(
            entries: List<ProxyEntry>,
            onProgress: (Int, Int) -> Unit,
        ): List<ProxyEntry> = entries.mapIndexed { index, entry ->
            onProgress(index + 1, entries.size)
            entry.copy(latencyMs = 50, status = ProxyStatus.AVAILABLE)
        }
    }

    /** 偶数 host 末段为 4 的过闸（120ms）、其余不过（null）。 */
    private class SelectiveGate : TelegramGateCaller {
        override fun test(host: String, port: Int): Int? =
            if (host.endsWith(".4")) 120 else null
    }

    @Test
    fun `成功刷新五项统计正确且四项之和等于 tested`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply {
                // .4 过闸保留；.5 两关失败；.6 超门槛被剔除；MTProto 未验证
                nextText = "socks5://1.2.3.4:1080\n" +
                    "socks5://1.2.3.5:1080\n" +
                    "socks5://1.2.3.6:1080\n" +
                    "tg://proxy?server=1.2.3.7&port=443&secret=abc\n" +
                    "http://9.9.9.9:8080\n" +
                    "badline"
            }
            val gate = TelegramGateCaller { host, _ ->
                when (host) {
                    "1.2.3.4" -> 120
                    "1.2.3.6" -> 1_500 // 过两关但超 1000ms 门槛
                    else -> null
                }
            }
            val repository = ProxyRepository(source, AllPassTester(), gate, ioDispatcher = td)

            val result = repository.refresh("https://example.com/proxy.txt")

            assertTrue(result is RefreshResult.Success)
            result as RefreshResult.Success
            val stats = result.stats
            assertEquals(1, stats.available)
            assertEquals(1, stats.unverified) // MTProto
            assertEquals(2, stats.tgFailed)   // .5 两关失败 + .6 超门槛
            assertEquals(0, stats.dropped)    // TCP 全通过
            assertEquals(4, stats.tested)     // SOCKS5 3 条 + MTProto 1 条
            // 口径契约：available+unverified+tgFailed+dropped = tested
            assertEquals(stats.tested, stats.available + stats.unverified + stats.tgFailed + stats.dropped)
            // excluded = 非 Telegram 合法条目(1) + 非法行(1)
            assertEquals(2, stats.excluded)
            assertTrue(result.updatedAt.matches(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""")))
            // entries 只含通过双闸且 ≤ 门槛的条目
            assertEquals(listOf("socks5://1.2.3.4:1080"), result.entries.map { it.link })
            assertEquals(120, result.entries[0].latencyMs)
        }
    }

    @Test
    fun `TCP 预筛不通的计入 dropped 不进 TG 实测`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply {
                nextText = "socks5://1.2.3.4:1080\nsocks5://1.2.3.5:1080"
            }
            val tester = object : LatencyTester {
                override suspend fun testAll(
                    entries: List<ProxyEntry>,
                    onProgress: (Int, Int) -> Unit,
                ): List<ProxyEntry> = entries.mapIndexed { index, entry ->
                    if (index == 0) {
                        entry.copy(latencyMs = 60, status = ProxyStatus.AVAILABLE)
                    } else {
                        entry.copy(latencyMs = null, status = ProxyStatus.UNAVAILABLE)
                    }
                }
            }
            var gateCalls = 0
            val gate = TelegramGateCaller { _, _ -> gateCalls++; 100 }
            val repository = ProxyRepository(source, tester, gate, ioDispatcher = td)

            val result = repository.refresh("https://example.com/proxy.txt")

            assertTrue(result is RefreshResult.Success)
            result as RefreshResult.Success
            assertEquals(1, result.stats.available)
            assertEquals(1, result.stats.dropped)
            assertEquals(1, gateCalls) // 只有 TCP 通过的 1 条进了 TG 实测
        }
    }

    @Test
    fun `进度回调覆盖三个阶段`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val repository = ProxyRepository(source, AllPassTester(), SelectiveGate(), ioDispatcher = td)
            val stages = mutableListOf<RefreshStage>()

            repository.refresh(listOf("https://example.com/proxy.txt")) { stages.add(it.stage) }

            // 抓取(逐源) → TCP(逐条) → TG(逐条)，各阶段出现且首次顺序一致
            assertTrue(stages.contains(RefreshStage.FETCH))
            assertTrue(stages.contains(RefreshStage.TCP_TEST))
            assertTrue(stages.contains(RefreshStage.TG_TEST))
            assertEquals(RefreshStage.FETCH, stages.first())
            assertEquals(
                listOf(RefreshStage.FETCH, RefreshStage.TCP_TEST, RefreshStage.TG_TEST),
                stages.distinct(),
            )
        }
    }

    @Test
    fun `known_good 开关关闭时不读不写`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val file = tempFile("kg-off")
            // 预置旧沉淀：关闭时既不应被合并也不应被覆盖
            file.writeText("""[{"link":"socks5://4.3.2.1:1080","host":"4.3.2.1","port":1080,"type":"SOCKS5"}]""")
            val before = file.readText()
            val store = KnownGoodStore(file).apply { enabled = false }
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val repository = ProxyRepository(source, AllPassTester(), SelectiveGate(), knownGood = store, ioDispatcher = td)

            val result = repository.refresh("https://example.com/proxy.txt")

            assertTrue(result is RefreshResult.Success)
            assertEquals(before, file.readText()) // 不写
            result as RefreshResult.Success
            // 不读：候选里没有 4.3.2.1（若读会补入并通过闸门出现在结果里）
            assertEquals(listOf("socks5://1.2.3.4:1080"), result.entries.map { it.link })
        }
    }

    @Test
    fun `known_good 开启时补入候选并沉淀本轮通过节点`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val file = tempFile("kg-on")
            val store = KnownGoodStore(file)
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val repository = ProxyRepository(source, AllPassTester(), SelectiveGate(), knownGood = store, ioDispatcher = td)

            val first = repository.refresh("https://example.com/proxy.txt")
            assertTrue(first is RefreshResult.Success)
            assertTrue(file.isFile) // 沉淀落盘
            assertTrue(file.readText().contains("1.2.3.4"))

            // 第二轮：源已无该节点，known_good 补入后仍通过闸门 → 结果保留
            source.nextText = ""
            val second = repository.refresh("https://example.com/proxy.txt")
            assertTrue(second is RefreshResult.Success)
            second as RefreshResult.Success
            assertEquals(listOf("socks5://1.2.3.4:1080"), second.entries.map { it.link })
        }
    }

    @Test
    fun `数据源抛 IOException 时转为 Failure 且不向外抛异常`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextError = IOException("boom") }
            val repository = ProxyRepository(source, AllPassTester(), SelectiveGate(), ioDispatcher = td)

            val result = repository.refresh("https://example.com/proxy.txt")

            assertTrue(result is RefreshResult.Failure)
            result as RefreshResult.Failure
            assertTrue(result.error.startsWith("抓取代理列表失败："))
            assertEquals("抓取代理列表失败：boom", result.error)
        }
    }

    @Test
    fun `刷新使用传入的数据源地址`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextText = "" }
            val repository = ProxyRepository(source, AllPassTester(), SelectiveGate(), ioDispatcher = td)

            repository.refresh("https://custom.example.com/list.txt")

            assertEquals(1, source.calls)
        }
    }

    private fun tempFile(name: String): File {
        val dir = File.createTempFile(name, "").let {
            it.delete()
            it.mkdirs()
            it
        }
        assertTrue(dir.isDirectory)
        return File(dir, "known_good.json")
    }
}
