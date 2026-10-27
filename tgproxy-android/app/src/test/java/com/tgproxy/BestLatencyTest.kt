package com.tgproxy

import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyType
import com.tgproxy.model.TgVerdict
import com.tgproxy.ui.screens.bestLatencyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hero 卡「最优延迟」的取数口径。
 *
 * 这条规则有个容易踩的坑：`latencyMs` 非空 ≠ 两关通过。TG 实测里第一关连上
 * 但被门槛剪枝、或者第二关站点验证失败的节点，也可能带着一个 latency 值。
 * 一旦把判据放宽成「latencyMs != null」，仪表盘就会拿一个已经判失败的节点
 * 去展示「最优延迟」—— 数字看着更漂亮，但它是假的。
 */
class BestLatencyTest {

    private fun entry(latencyMs: Int?, verdict: TgVerdict, port: Int = 1080) = ProxyEntry(
        link = "socks5://1.2.3.4:$port",
        host = "1.2.3.4",
        port = port,
        type = ProxyType.SOCKS5,
        latencyMs = latencyMs,
        tgVerdict = verdict,
    )

    @Test
    fun `只取两关通过的最小延迟`() {
        val entries = listOf(
            entry(900, TgVerdict.PASS),
            entry(320, TgVerdict.PASS),
            entry(120, TgVerdict.FAIL),
        )
        assertEquals(320, bestLatencyOf(entries))
    }

    @Test
    fun `失败与未验证节点即使有延迟也不参与`() {
        // 只有失败/未验证节点：应当没有「最优延迟」，而不是拿它们的数字充数
        assertNull(
            bestLatencyOf(
                listOf(
                    entry(88, TgVerdict.FAIL),
                    entry(64, TgVerdict.UNVERIFIED),
                ),
            ),
        )
    }

    @Test
    fun `通过但延迟未知的节点不算`() {
        assertEquals(
            500,
            bestLatencyOf(
                listOf(
                    entry(null, TgVerdict.PASS),
                    entry(500, TgVerdict.PASS),
                ),
            ),
        )
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(bestLatencyOf(emptyList()))
    }
}
