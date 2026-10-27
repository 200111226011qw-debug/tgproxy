package com.tgproxy

import com.tgproxy.model.StageDurations
import com.tgproxy.model.TgGateStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表盘数据模型的派生量。
 *
 * 这些派生量本身很小，但它们是 UI 是否渲染整块卡片的唯一判据 —— 错了就是
 * 「冷启动显示『本轮 0 个节点』」或者「时间轴除零」这类看着像数据错、其实
 * 是判据错的问题。
 */
class StageMetricsTest {

    @Test
    fun `冷启动两项都没有数据`() {
        assertFalse("默认 TgGateStats 不该被认为有数据", TgGateStats().hasData)
        assertFalse("默认 StageDurations 不该被认为有数据", StageDurations().hasData)
    }

    @Test
    fun `漏斗任一个桶非零就算有数据`() {
        // 四个桶逐一单独点亮：任何一桶非零都必须让卡片出现，
        // 否则「全部失败」的那一轮会静默不显示漏斗（正是最需要看的一轮）
        val buckets = listOf(
            TgGateStats(dcFail = 1),
            TgGateStats(latencyRejected = 1),
            TgGateStats(webFail = 1),
            TgGateStats(ok = 1),
        )
        buckets.forEach { stats ->
            assertTrue("$stats 应当有数据", stats.hasData)
            assertEquals(1, stats.total)
        }
    }

    @Test
    fun `耗时判据只看整轮总时长`() {
        // 分段有值但总时长为 0：这是不可能的落盘组合，但判据必须以分母为准，
        // 否则 StageTimeline 里 ms / totalMs 会除零
        val inconsistent = StageDurations(fetchMs = 100, prepareMs = 50, totalMs = 0)
        assertFalse("totalMs 为 0 时不该画时间轴", inconsistent.hasData)

        val normal = StageDurations(fetchMs = 100, prepareMs = 50, totalMs = 200)
        assertTrue(normal.hasData)
    }

    @Test
    fun `measuredSumMs 是五项之和`() {
        val d = StageDurations(
            fetchMs = 16_600,
            prepareMs = 900,
            tcpMs = 8_400,
            tgMs = 27_600,
            commitMs = 200,
            totalMs = 53_700,
        )
        assertEquals(53_700, d.measuredSumMs)
        // 独立测量的总时长与分段之和应当接近（本例恰好相等）
        assertTrue(kotlin.math.abs(d.measuredSumMs - d.totalMs) <= 10)
    }

    @Test
    fun `纳秒差转毫秒向下取整`() {
        // 999_999ns 不足 1ms，必须归 0 而不是四舍五入成 1 —— 虚报耗时比少报更难排查
        assertEquals(0, StageDurations.msBetween(0, 999_999))
        assertEquals(1, StageDurations.msBetween(0, 1_000_000))
        assertEquals(3, StageDurations.msBetween(1_000, 3_500_000))
    }
}
