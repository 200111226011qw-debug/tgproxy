package com.tgproxy

import com.tgproxy.data.MirrorRace
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像竞速核心语义（纯协程，fetchCandidate 注入假函数，无 OkHttp / 网络）。
 *
 * 与桌面 race_mirrors 对齐的 5 条验收，逐条对照：
 * 1. 首个通过校验的候选胜出（快者先到）
 * 2. 失败/空体候选不占先，慢的镜像胜（竞速安全网）
 * 3. 全部失败抛带各自原因的错误
 * 4. 胜出后落败者被取消（配合 fetchCandidate 的 invokeOnCancellation → call.cancel）
 * 5. 错峰：第 i 个候选在 i×stagger 之后才发出
 *
 * 注意 1/2 用 stagger=0 隔离「先到先得」与「错峰」两个维度；错峰单独在 5 里测。
 */
class MirrorRaceTest {

    private fun candidates(vararg labels: String): List<MirrorRace.Candidate> =
        labels.mapIndexed { index, label -> MirrorRace.Candidate("u$index", label) }

    @Test
    fun `首个通过校验的候选胜出（快者先到）`() = runTest {
        val race = MirrorRace(staggerMs = 0, maxConcurrent = 8)
        val result = race.race(candidates("direct", "jsdelivr-cdn")) { url ->
            when (url) {
                "u0" -> {
                    delay(300); "DIRECT"
                }
                else -> {
                    delay(100); "MIRROR"
                }
            }
        }
        assertEquals("MIRROR", result)
    }

    @Test
    fun `空体候选被拒、慢的镜像胜`() = runTest {
        val race = MirrorRace(staggerMs = 0, maxConcurrent = 8)
        val result = race.race(candidates("direct", "jsdelivr-cdn")) { url ->
            when (url) {
                // 直连快但空体（模拟 min_bytes 校验失败）：不能占先
                "u0" -> {
                    delay(50); throw IOException("响应仅 0 字节（< 64），按截断处理")
                }
                else -> {
                    delay(400); "GOOD"
                }
            }
        }
        assertEquals("GOOD", result)
    }

    @Test
    fun `全部失败抛带各自原因的错误`() = runTest {
        val race = MirrorRace(staggerMs = 0, maxConcurrent = 8)
        var thrown: IOException? = null
        try {
            race.race(candidates("direct", "jsdelivr-cdn")) { url ->
                throw IOException("失败于 $url")
            }
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull("应当抛错", thrown)
        assertTrue(thrown!!.message!!.contains("direct: 失败于 u0"))
        assertTrue(thrown.message!!.contains("jsdelivr-cdn: 失败于 u1"))
    }

    @Test
    fun `胜出后落败者被取消`() = runTest {
        val race = MirrorRace(staggerMs = 0, maxConcurrent = 8)
        val cancelled = mutableListOf<String>()
        val result = race.race(candidates("direct", "jsdelivr-cdn")) { url ->
            if (url == "u0") "WIN" else suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { cancelled.add(url) }
                // 永不 resume：只会在被取消时结束
            }
        }
        assertEquals("WIN", result)
        assertEquals(listOf("u1"), cancelled)
    }

    @Test
    fun `错峰：候选按 index×stagger 依次发出`() = runTest {
        val scheduler = testScheduler
        val race = MirrorRace(staggerMs = 250, maxConcurrent = 8)
        val arrivals = mutableListOf<Pair<String, Long>>()
        try {
            race.race(candidates("direct", "jsdelivr-cdn", "jsdelivr-gcore", "jsdelivr-fastly")) { url ->
                arrivals.add(url to scheduler.currentTime)
                throw IOException("失败于 $url")
            }
        } catch (e: IOException) {
            // 预期：全部失败
        }
        assertEquals(listOf("u0", "u1", "u2", "u3"), arrivals.map { it.first })
        assertEquals(listOf(0L, 250L, 500L, 750L), arrivals.map { it.second })
    }
}
