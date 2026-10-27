package com.tgproxy

import com.tgproxy.data.RoundLogger
import com.tgproxy.model.RoundRecord
import com.tgproxy.model.StageDurations
import com.tgproxy.model.Stats
import com.tgproxy.model.TgGateStats
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 轮次记录存储：JSONL 往返、坏行容错、超限截断、seq 单调。
 *
 * 这些性质里最容易写错的是**截断后 seq 还在涨**：如果 seq 用「列表下标」生成，
 * 截断后新记录会拿到重复 seq，UI 拿它当 LazyColumn 的 key 就会崩。
 */
class RoundLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(
        ts: String = "2026-10-28 03:12:40",
        success: Boolean = true,
        error: String? = null,
        elapsedMs: Long = 44_760,
    ) = RoundRecord(
        timestamp = ts,
        success = success,
        error = error,
        elapsedMs = elapsedMs,
        stats = Stats(
            available = 11,
            tgFailed = 260,
            overThreshold = 75,
            dropped = 1200,
            blacklisted = 40,
            tested = 1546,
        ),
        tgStats = TgGateStats(dcFail = 1200, latencyRejected = 75, webFail = 260, ok = 11),
        durations = StageDurations(
            fetchMs = 16_600,
            prepareMs = 900,
            tcpMs = 8_400,
            tgMs = 27_600,
            commitMs = 200,
            totalMs = 53_700,
        ),
    )

    @Test
    fun `写两行读两行，字段完整往返`() = runTest {
        val file = File(tmp.root, "rounds.jsonl")
        val logger = RoundLogger(file)

        logger.append(record(ts = "2026-10-28 03:00:00"))
        val all = logger.append(record(ts = "2026-10-28 03:10:00", elapsedMs = 50_000))

        assertEquals(2, all.size)
        assertEquals(listOf("2026-10-28 03:00:00", "2026-10-28 03:10:00"), all.map { it.timestamp })

        // 重新读盘：确认真的落盘了，不是只活在返回值里
        val reloaded = RoundLogger(file).load()
        assertEquals(2, reloaded.size)
        val second = reloaded[1]
        assertEquals(50_000L, second.elapsedMs)
        assertTrue(second.success)
        assertEquals(11, second.stats.available)
        assertEquals(1546, second.stats.tested)
        assertEquals(1200, second.tgStats.dcFail)
        assertEquals(27_600L, second.durations.tgMs)
        assertEquals(53_700L, second.durations.totalMs)

        // 每行一个对象：JSONL 的形态本身也是契约（追加/流式读取依赖它）
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test
    fun `损坏行跳过，其余行照常读出`() = runTest {
        val file = File(tmp.root, "rounds.jsonl")
        // 模拟用户强杀导致的半行截断，外加一行不是对象的垃圾
        file.writeText(
            """
            {"ts":"2026-10-28 03:00:00","ok":true,"elapsed_ms":1000}
            {"ts":"2026-10-28 03:0
            not json at all
            {"ts":"2026-10-28 03:20:00","ok":false,"error":"boom","elapsed_ms":2000}

            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        val loaded = RoundLogger(file).load()
        assertEquals("坏行必须跳过而不是整份失败", 2, loaded.size)
        assertEquals("2026-10-28 03:00:00", loaded[0].timestamp)
        assertEquals("2026-10-28 03:20:00", loaded[1].timestamp)
        assertFalse(loaded[1].success)
        assertEquals("boom", loaded[1].error)
        // 缺失的嵌套字段降级为 0，不是 null 崩
        assertEquals(0, loaded[1].stats.tested)
        assertEquals(0L, loaded[1].durations.totalMs)
    }

    @Test
    fun `超出上限时截断到最近 N 条，且 seq 继续单调递增`() = runTest {
        val file = File(tmp.root, "rounds.jsonl")
        val logger = RoundLogger(file, maxEntries = 3)

        repeat(5) { i ->
            logger.append(record(ts = "2026-10-28 03:0$i:00", elapsedMs = i * 1_000L))
        }

        val loaded = RoundLogger(file, maxEntries = 3).load()
        assertEquals(3, loaded.size)
        // 保留的是最后三条（旧的被挤掉）
        assertEquals(listOf("2026-10-28 03:02:00", "2026-10-28 03:03:00", "2026-10-28 03:04:00"), loaded.map { it.timestamp })
        // seq 不能因为截断而回头 —— 否则 UI 的列表 key 会撞车
        assertEquals(listOf(3L, 4L, 5L), loaded.map { it.seq })
        // 落盘文件本身也被截断（不只是返回值被裁剪）
        assertEquals(3, file.readLines(Charsets.UTF_8).count { it.isNotBlank() })

        // 再写一条：seq 必须接着 6，而不是从截断后的长度重新数
        val after = logger.append(record(ts = "2026-10-28 03:05:00"))
        assertEquals(6L, after.last().seq)
        assertEquals(3, after.size)
    }

    @Test
    fun `失败轮次往返：ok=false 且 error 保留`() = runTest {
        val file = File(tmp.root, "rounds.jsonl")
        val logger = RoundLogger(file)
        logger.append(
            record(
                ts = "2026-10-28 03:30:00",
                success = false,
                error = "抓取代理列表失败：全部 8 个数据源抓取失败",
                elapsedMs = 120_000,
            ),
        )
        val loaded = logger.load().single()
        assertFalse(loaded.success)
        assertEquals("抓取代理列表失败：全部 8 个数据源抓取失败", loaded.error)
        assertEquals(120_000L, loaded.elapsedMs)
    }

    @Test
    fun `成功但带提示的记录不会被当成失败`() = runTest {
        // 「本轮 0 个节点通过…已保留上一轮列表」这种是成功轮 + 警告，
        // 日志页要能区分，不能因为有 error 就画成失败
        val file = File(tmp.root, "rounds.jsonl")
        RoundLogger(file).append(
            record(success = true, error = "本轮 0 个节点通过 TG 实测（门槛 1000ms），已保留上一轮列表"),
        )
        val loaded = RoundLogger(file).load().single()
        assertTrue(loaded.success)
        assertTrue(loaded.hasNote)
    }

    @Test
    fun `文件不存在时返回空表而不是抛异常`() = runTest {
        val logger = RoundLogger(File(tmp.root, "nope.jsonl"))
        assertTrue(logger.load().isEmpty())
    }

    @Test
    fun `禁用态 logger 不写不读`() = runTest {
        val logger = RoundLogger.none()
        assertTrue(logger.load().isEmpty())
        assertTrue(logger.append(record()).isEmpty())
        // 不写盘的语义要一致：返回空列表，界面就不会显示一条「其实没存」的记录
        assertNull(File(tmp.root, "rounds.jsonl").takeIf { it.exists() })
    }
}
