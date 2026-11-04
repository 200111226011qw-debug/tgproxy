package com.tgproxy

import com.tgproxy.data.LatencyTester
import com.tgproxy.data.MtProtoCaller
import com.tgproxy.data.MtProtoCode
import com.tgproxy.data.MtProtoResult
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.ProxyRepository.RefreshResult
import com.tgproxy.data.ProxySource
import com.tgproxy.data.TelegramGateCaller
import com.tgproxy.model.GateOutcome
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.RefreshProgress
import com.tgproxy.model.RefreshStage
import com.tgproxy.model.TgGateStage
import com.tgproxy.model.TgVerdict
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MTProto 通道的落账测试（v2.3.0 双通道）。
 *
 * 既有 `ProxyRepositoryTest` 只覆盖 SOCKS5；本类专测「MTProto 结论如何进入
 * 五项统计」——这是本次同步最容易接错的一环：结论映射错一格，节点就会被
 * 静默丢弃（统计仍自洽，界面上什么都不说）。
 */
class ProxyRepositoryMtProtoTest {

    private class FakeSource(private val text: String) : ProxySource {
        override suspend fun fetch(url: String): String = text
    }

    private class AllPassTester : LatencyTester {
        override suspend fun testAll(
            entries: List<ProxyEntry>,
            onProgress: (Int, Int) -> Unit,
        ): List<ProxyEntry> = entries.mapIndexed { index, entry ->
            onProgress(index + 1, entries.size)
            entry.copy(latencyMs = 40, status = ProxyStatus.AVAILABLE)
        }
    }

    /** 只会失败的 SOCKS5 闸门：本类里不应被调用。 */
    private class NeverGate : TelegramGateCaller {
        override fun test(host: String, port: Int): Int? = null
        override fun testDetailed(host: String, port: Int, maxLatencyMs: Int?): GateOutcome =
            GateOutcome(TgGateStage.DC_FAIL, null)
    }

    /** 按 host 决定结论的假探针。 */
    private class HostProbe(
        private val byHost: Map<String, MtProtoResult>,
    ) : MtProtoCaller {
        val seenSecrets = mutableListOf<String?>()
        override fun probe(host: String, port: Int, secret: String?, timeoutMs: Int): MtProtoResult {
            seenSecrets.add(secret)
            return byHost[host] ?: MtProtoResult(MtProtoCode.CONNECT_FAIL)
        }
    }

    private fun mtprotoLink(host: String, secret: String = "dd" + "ab".repeat(16)): String =
        "tg://proxy?server=$host&port=443&secret=$secret"

    @Test
    fun `握手通过的 MTProto 节点进入 available 并带上 RTT`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val probe = HostProbe(mapOf("5.6.7.8" to MtProtoResult(MtProtoCode.OK, latencyMs = 320)))
            val repo = ProxyRepository(
                FakeSource(mtprotoLink("5.6.7.8")),
                AllPassTester(), NeverGate(), ioDispatcher = td, mtProtoProbe = probe,
            )

            val result = repo.refresh("u") as RefreshResult.Success

            assertEquals(1, result.stats.available)
            assertEquals(0, result.stats.unverified)
            assertEquals(0, result.stats.tgFailed)
            assertEquals(listOf(mtprotoLink("5.6.7.8")), result.entries.map { it.link })
            assertEquals(320, result.entries[0].latencyMs)
            assertEquals(TgVerdict.PASS, result.entries[0].tgVerdict)
        }
    }

    @Test
    fun `握手失败计入 tgFailed 而不是未验证`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val probe = HostProbe(mapOf("5.6.7.8" to MtProtoResult(MtProtoCode.PROTOCOL, detail = "nonce 不回显")))
            val repo = ProxyRepository(
                FakeSource(mtprotoLink("5.6.7.8")),
                AllPassTester(), NeverGate(), ioDispatcher = td, mtProtoProbe = probe,
            )

            val result = repo.refresh("u") as RefreshResult.Success

            assertEquals(0, result.stats.available)
            assertEquals(0, result.stats.unverified)
            assertEquals(1, result.stats.tgFailed)
            assertTrue(result.entries.isEmpty())
        }
    }

    @Test
    fun `握手通过但超门槛计入 overThreshold`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val probe = HostProbe(mapOf("5.6.7.8" to MtProtoResult(MtProtoCode.OK, latencyMs = 2_400)))
            val repo = ProxyRepository(
                FakeSource(mtprotoLink("5.6.7.8")),
                AllPassTester(), NeverGate(), ioDispatcher = td, mtProtoProbe = probe,
            )

            val result = repo.refresh("u") as RefreshResult.Success

            assertEquals(0, result.stats.available)
            assertEquals(1, result.stats.overThreshold)
            // 超门槛仍在「实测通过」口径里：不该累计连败
            assertEquals(0, result.stats.tgFailed)
        }
    }

    @Test
    fun `探针抛异常按失败计且不影响同轮其他节点`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val throwing = object : MtProtoCaller {
                override fun probe(host: String, port: Int, secret: String?, timeoutMs: Int): MtProtoResult {
                    if (host == "5.6.7.8") throw IllegalStateException("boom")
                    return MtProtoResult(MtProtoCode.OK, latencyMs = 100)
                }
            }
            val repo = ProxyRepository(
                FakeSource(mtprotoLink("5.6.7.8") + "\n" + mtprotoLink("5.6.7.9")),
                AllPassTester(), NeverGate(), ioDispatcher = td, mtProtoProbe = throwing,
            )

            val result = repo.refresh("u") as RefreshResult.Success

            assertEquals(1, result.stats.tgFailed)
            assertEquals(1, result.stats.available)
            assertEquals(listOf(mtprotoLink("5.6.7.9")), result.entries.map { it.link })
        }
    }

    @Test
    fun `探针拿到的是链接里的 secret 而不是空值`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val probe = HostProbe(mapOf("5.6.7.8" to MtProtoResult(MtProtoCode.OK, latencyMs = 90)))
            val repo = ProxyRepository(
                FakeSource(mtprotoLink("5.6.7.8", secret = "dd" + "cd".repeat(16))),
                AllPassTester(), NeverGate(), ioDispatcher = td, mtProtoProbe = probe,
            )

            repo.refresh("u")

            assertEquals(listOf("dd" + "cd".repeat(16)), probe.seenSecrets)
        }
    }

    @Test
    fun `百分号编码的 secret 解析后透传给探针`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val probe = HostProbe(mapOf("5.6.7.8" to MtProtoResult(MtProtoCode.OK, latencyMs = 90)))
            val link = "tg://proxy?server=5.6.7.8&port=443&secret=MDEyMzQ1Njc4OWFiY2RlZg%3D%3D"
            val repo = ProxyRepository(FakeSource(link), AllPassTester(), NeverGate(), ioDispatcher = td, mtProtoProbe = probe)

            repo.refresh(listOf("u"))

            // 解析阶段已把 %3D%3D 解回 ==，探针收到的是解码后的 secret，而非原文
            assertEquals(listOf("MDEyMzQ1Njc4OWFiY2RlZg=="), probe.seenSecrets)
        }
    }

    @Test
    fun `TG 进度分母包含 MTProto（不再只报 SOCKS5）`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val probe = HostProbe(mapOf("5.6.7.8" to MtProtoResult(MtProtoCode.OK, latencyMs = 90)))
            val repo = ProxyRepository(
                FakeSource(mtprotoLink("5.6.7.8") + "\nsocks5://1.2.3.4:1080"),
                AllPassTester(), NeverGate(), ioDispatcher = td, mtProtoProbe = probe,
            )
            val progress = mutableListOf<RefreshProgress>()

            repo.refresh(listOf("u"), onProgress = { progress.add(it) })

            val tg = progress.filter { it.stage == RefreshStage.TG_TEST }
            assertEquals(2, tg.first().total)
            assertEquals(2, tg.last().done)
        }
    }

    @Test
    fun `无法判定的节点既不算通过也不算失败`() {
        val td = StandardTestDispatcher()
        runTest(td) {
            val probe = HostProbe(mapOf("5.6.7.8" to MtProtoResult(MtProtoCode.SECRET_UNSUPPORTED)))
            val repo = ProxyRepository(
                FakeSource(mtprotoLink("5.6.7.8", secret = "ee" + "11".repeat(16))),
                AllPassTester(), NeverGate(), ioDispatcher = td, mtProtoProbe = probe,
            )

            val result = repo.refresh("u") as RefreshResult.Success

            assertEquals(1, result.stats.unverified)
            assertEquals(0, result.stats.tgFailed)
            assertEquals(0, result.stats.available)
            assertNull(result.entries.firstOrNull())
        }
    }
}
