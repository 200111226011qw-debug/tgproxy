package com.tgproxy

import com.tgproxy.data.KnownGoodStore
import com.tgproxy.data.LatencyTester
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.ProxyRepository.RefreshResult
import com.tgproxy.data.ProxySource
import com.tgproxy.data.TelegramGateCaller
import com.tgproxy.model.GateOutcome
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.RefreshStage
import com.tgproxy.model.TgGateStage
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
            assertEquals(1, stats.unverified)   // MTProto
            assertEquals(1, stats.tgFailed)     // .5 两关失败
            assertEquals(1, stats.overThreshold) // .6 过两关但超 1000ms 门槛
            assertEquals(0, stats.dropped)      // TCP 全通过
            assertEquals(4, stats.tested)       // SOCKS5 3 条 + MTProto 1 条
            // 口径契约：available+unverified+tgFailed+overThreshold+dropped = tested
            assertEquals(
                stats.tested,
                stats.available + stats.unverified + stats.tgFailed + stats.overThreshold + stats.dropped,
            )
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

    // ========================================================================
    // 连续失败拉黑（v2.1.0）
    // ========================================================================

    /** 计数假测试器：记录送入 TCP 预筛的条数。 */
    private class CountingTester : LatencyTester {
        var fedCount = 0
        override suspend fun testAll(
            entries: List<ProxyEntry>,
            onProgress: (Int, Int) -> Unit,
        ): List<ProxyEntry> {
            fedCount += entries.size
            return entries.mapIndexed { index, entry ->
                onProgress(index + 1, entries.size)
                entry.copy(latencyMs = 50, status = ProxyStatus.AVAILABLE)
            }
        }
    }

    /** 恒失败闸门。 */
    private class FailingGate : TelegramGateCaller {
        var calls = 0
        override fun test(host: String, port: Int): Int? {
            calls++
            return null
        }
    }

    /** 按 URL 返回不同文本的假数据源（多源池子用）。 */
    private class PerUrlSource(private val texts: Map<String, String>) : ProxySource {
        override suspend fun fetch(url: String): String = texts[url] ?: ""
    }

    /** 记录收到的门槛参数：验证仓库确实把门槛传给了闸门（否则剪枝不生效）。 */
    private class RecordingGate(private val result: Int?) : TelegramGateCaller {
        val thresholds = mutableListOf<Int?>()

        override fun test(host: String, port: Int): Int? = test(host, port, null)

        override fun test(host: String, port: Int, maxLatencyMs: Int?): Int? {
            thresholds += maxLatencyMs
            return result
        }
    }

    @Test
    fun `TG 阶段把延迟门槛传给闸门以启用剪枝`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val gate = RecordingGate(500)
            val repository = ProxyRepository(
                source, AllPassTester(), gate,
                tgLatencyLimitMs = 1234, ioDispatcher = td,
            )

            repository.refresh("https://example.com/proxy.txt")

            assertTrue("闸门没被调用", gate.thresholds.isNotEmpty())
            assertTrue(
                "门槛没传进去：${gate.thresholds}",
                gate.thresholds.all { it == 1234 },
            )
        }
    }

    /** 阻塞式假测试器：用真实耗时让 tcpMs 可观测（测试调度器的 delay 是虚拟时间）。 */
    private class BlockingTester(private val sleepMs: Long) : LatencyTester {
        override suspend fun testAll(
            entries: List<ProxyEntry>,
            onProgress: (Int, Int) -> Unit,
        ): List<ProxyEntry> {
            Thread.sleep(sleepMs)
            return entries.map { it.copy(latencyMs = 30, status = ProxyStatus.AVAILABLE) }
        }
    }

    @Test
    fun `分阶段耗时齐全且五段之和接近总耗时`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val repository = ProxyRepository(
                source, BlockingTester(25), SelectiveGate(), ioDispatcher = td,
            )

            val result = repository.refresh("https://example.com/proxy.txt")

            assertTrue(result is RefreshResult.Success)
            result as RefreshResult.Success
            val d = result.durations

            // 每段非负
            listOf(d.fetchMs, d.prepareMs, d.tcpMs, d.tgMs, d.commitMs, d.totalMs)
                .forEach { assertTrue("耗时为负：$d", it >= 0) }
            // TCP 段真的被测量到（假件阻塞 25ms）
            assertTrue("tcpMs 没测到：$d", d.tcpMs >= 15)
            // 五段之和 ≈ 总耗时（差额只来自纳秒转毫秒的截断）
            assertTrue(
                "五段之和 ${d.measuredSumMs} 与总耗时 ${d.totalMs} 差得太多",
                kotlin.math.abs(d.measuredSumMs - d.totalMs) <= 10,
            )
            // 总耗时不得小于 TCP 段
            assertTrue("totalMs 小于 tcpMs：$d", d.totalMs >= d.tcpMs)
        }
    }

    @Test
    fun `抓取失败时返回 Failure 且不产出耗时`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextError = IOException("boom") }
            val repository = ProxyRepository(source, AllPassTester(), SelectiveGate(), ioDispatcher = td)

            val result = repository.refresh("https://example.com/proxy.txt")

            assertTrue(result is RefreshResult.Failure)
        }
    }

    /** 按 host 脚本化落点的假闸门（验证四桶聚合）。 */
    private class StagedGate(private val byHost: Map<String, GateOutcome>) : TelegramGateCaller {
        override fun test(host: String, port: Int): Int? =
            testDetailed(host, port, null).latencyMs

        override fun test(host: String, port: Int, maxLatencyMs: Int?): Int? =
            testDetailed(host, port, maxLatencyMs).latencyMs

        override fun testDetailed(host: String, port: Int, maxLatencyMs: Int?): GateOutcome =
            byHost[host] ?: GateOutcome(TgGateStage.DC_FAIL, null)
    }

    @Test
    fun `两关分布按落点正确分桶`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val hosts = listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4")
            val source = FakeSource().apply {
                nextText = hosts.joinToString("\n") { "socks5://$it:1080" }
            }
            val gate = StagedGate(
                mapOf(
                    "1.1.1.1" to GateOutcome(TgGateStage.OK, 500),
                    "2.2.2.2" to GateOutcome(TgGateStage.LATENCY_REJECTED, 2_000),
                    "3.3.3.3" to GateOutcome(TgGateStage.WEB_FAIL, null),
                    "4.4.4.4" to GateOutcome(TgGateStage.DC_FAIL, null),
                ),
            )
            val repository = ProxyRepository(source, AllPassTester(), gate, ioDispatcher = td)

            val result = repository.refresh("https://example.com/proxy.txt")

            assertTrue(result is RefreshResult.Success)
            result as RefreshResult.Success
            val g = result.gateStats
            assertEquals("ok 分桶错误：$g", 1, g.ok)
            assertEquals("超门槛分桶错误：$g", 1, g.latencyRejected)
            assertEquals("站点失败分桶错误：$g", 1, g.webFail)
            assertEquals("连接失败分桶错误：$g", 1, g.dcFail)
            assertEquals(4, g.total)
        }
    }

    @Test
    fun `超门槛节点不计入连败（落点不能当成失败）`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextText = "socks5://2.2.2.2:1080" }
            val gate = StagedGate(
                mapOf("2.2.2.2" to GateOutcome(TgGateStage.LATENCY_REJECTED, 2_000)),
            )
            val bad = com.tgproxy.data.KnownBadStore(badFile("kb-prune"))
            val repository = ProxyRepository(
                source, AllPassTester(), gate, knownBad = bad, ioDispatcher = td,
            )

            repository.refresh("https://example.com/proxy.txt")

            // 超门槛是「连得上但太慢」：tgVerdict=PASS → recordSuccess 清零连败，
            // 不能像 dc_fail 那样累计。否则三轮后被误拉黑。
            assertTrue("超门槛节点被记成连败了", bad.isBlacklisted("2.2.2.2", 1080).not())
            assertEquals(0, repository.refresh("https://example.com/proxy.txt")
                .let { (it as RefreshResult.Success).stats.blacklisted })
        }
    }

    @Test
    fun `黑名单节点不占 MAX_TOTAL 配额（先过滤后抽样）`() {
        // 回归（2026-10-28）：此前是「先抽样、后过滤」，黑名单节点会占掉
        // MAX_TOTAL 配额 → 实测数随黑名单大小漂移且缺失非随机，双端通过率
        // 不可比。改为「先过滤、后抽样」后，测数恒为 min(合格池, MAX_TOTAL)。
        val td = StandardTestDispatcher()
        runTest(td) {
            // 4 个源各 1000 条（MAX_PER_SOURCE=1000）→ 池子 4000 > MAX_TOTAL 3000
            val urls = (1..4).map { "https://s$it.example.com/list.txt" }
            val texts = urls.indices.map { idx ->
                (0 until 1000).joinToString("\n") { i ->
                    "socks5://10.$idx.${i / 256}.${i % 256}:1080"
                }
            }
            val source = PerUrlSource(urls.zip(texts).toMap())

            // 把第 1 个源的 500 条打成拉黑（连败 3 次）
            val bad = com.tgproxy.data.KnownBadStore(badFile("kb-quota"))
            for (i in 0 until 500) {
                val host = "10.0.${i / 256}.${i % 256}"
                repeat(3) { bad.recordFailure(host, 1080) }
            }
            bad.persist()

            val tester = CountingTester()
            val repository = ProxyRepository(
                source, tester, SelectiveGate(), knownBad = bad, ioDispatcher = td,
            )
            val result = repository.refresh(urls)

            assertTrue(result is RefreshResult.Success)
            result as RefreshResult.Success
            assertEquals(500, result.stats.blacklisted)  // 全池口径（抽样前）
            assertEquals(Constants.MAX_TOTAL, result.stats.tested)  // 配额没被吃掉
            assertEquals(Constants.MAX_TOTAL, tester.fedCount)
        }
    }

    private fun badFile(name: String): File {
        val dir = File.createTempFile(name, "").let {
            it.delete()
            it.mkdirs()
            it
        }
        return File(dir, "known_bad.json")
    }

    @Test
    fun `连续三轮 TG 失败后第四轮拉黑跳过测试`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply {
                nextText = "socks5://1.2.3.4:1080\nsocks5://1.2.3.5:1080"
            }
            val tester = CountingTester()
            val gate = FailingGate()
            val bad = com.tgproxy.data.KnownBadStore(badFile("kb-3rounds"))
            val repository = ProxyRepository(source, tester, gate, knownBad = bad, ioDispatcher = td)

            repeat(3) { repository.refresh("https://example.com/proxy.txt") }
            assertEquals(6, tester.fedCount) // 前三轮都全量测试
            assertEquals(6, gate.calls)

            val fourth = repository.refresh("https://example.com/proxy.txt")
            assertTrue(fourth is RefreshResult.Success)
            fourth as RefreshResult.Success
            assertEquals(2, fourth.stats.blacklisted) // 两个节点都被拉黑
            assertEquals(0, fourth.stats.tested)      // 没有节点参与测试
            assertEquals(0, tester.fedCount - 6)      // TCP 预筛没再喂
            assertEquals(0, gate.calls - 6)           // TG 也没再测
        }
    }

    @Test
    fun `中途通过会清零连败不拉黑`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val gate = FailingGate()
            val bad = com.tgproxy.data.KnownBadStore(badFile("kb-reset"))
            val repository = ProxyRepository(source, AllPassTester(), gate, knownBad = bad, ioDispatcher = td)

            repository.refresh("https://example.com/proxy.txt") // 败 1
            repository.refresh("https://example.com/proxy.txt") // 败 2
            // 第三轮换通过闸门：连败清零
            val passRepo = ProxyRepository(source, AllPassTester(), SelectiveGate(), knownBad = bad, ioDispatcher = td)
            val third = passRepo.refresh("https://example.com/proxy.txt")
            assertTrue(third is RefreshResult.Success)
            third as RefreshResult.Success
            assertEquals(1, third.stats.available)
            // 第四轮再败：连败从 0 重新计
            repository.refresh("https://example.com/proxy.txt")
            val fifth = repository.refresh("https://example.com/proxy.txt")
            assertTrue(fifth is RefreshResult.Success)
            fifth as RefreshResult.Success
            assertEquals(0, fifth.stats.blacklisted)
            assertEquals(1, fifth.stats.tgFailed)
        }
    }

    @Test
    fun `超门槛节点按通过清零连败`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val failGate = FailingGate()
            val bad = com.tgproxy.data.KnownBadStore(badFile("kb-over"))
            // 两轮失败
            val failRepo = ProxyRepository(source, AllPassTester(), failGate, knownBad = bad, ioDispatcher = td)
            failRepo.refresh("https://example.com/proxy.txt")
            failRepo.refresh("https://example.com/proxy.txt")
            // 第三轮：过两关但 1500ms 超门槛（节点活着只是慢）→ 应清零
            val slowGate = TelegramGateCaller { _, _ -> 1_500 }
            val slowRepo = ProxyRepository(source, AllPassTester(), slowGate, knownBad = bad, ioDispatcher = td)
            slowRepo.refresh("https://example.com/proxy.txt")
            // 第四轮再失败：连败为 1，不拉黑
            val fourth = failRepo.refresh("https://example.com/proxy.txt")
            assertTrue(fourth is RefreshResult.Success)
            fourth as RefreshResult.Success
            assertEquals(0, fourth.stats.blacklisted)
            assertEquals(1, fourth.stats.tgFailed)
            assertEquals(1, fourth.stats.tested)
        }
    }

    @Test
    fun `拉黑 48 小时后自动解封重测`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            var now = 1_000_000L
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val tester = CountingTester()
            val gate = FailingGate()
            val bad = com.tgproxy.data.KnownBadStore(badFile("kb-expiry")) { now }
            val repository = ProxyRepository(source, tester, gate, knownBad = bad, ioDispatcher = td)

            repeat(3) { repository.refresh("https://example.com/proxy.txt") }
            val blacklistedRound = repository.refresh("https://example.com/proxy.txt")
            assertTrue(blacklistedRound is RefreshResult.Success)
            blacklistedRound as RefreshResult.Success
            assertEquals(1, blacklistedRound.stats.blacklisted)

            // 推进 49 小时：过保质期 → 回到测试池
            now += 49 * 3_600_000L
            val revived = repository.refresh("https://example.com/proxy.txt")
            assertTrue(revived is RefreshResult.Success)
            revived as RefreshResult.Success
            assertEquals(0, revived.stats.blacklisted)
            assertEquals(1, revived.stats.tested)
            assertEquals(1, revived.stats.tgFailed)
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
