package com.tgproxy.data

import com.tgproxy.Constants
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 单源镜像竞速：多个候选并发、首个通过校验的胜出、其余取消。
 *
 * 与桌面 `race_mirrors_async` 语义对齐，两处有意差异（见 ARCHITECTURE.md）：
 * 1. 并发上限 [maxConcurrent]：手机 8、桌面 64 —— 超出排队，避免同时打 20 源 × 4 候选；
 * 2. 落败者靠协程取消传播到 `fetchCandidate` 的 `invokeOnCancellation`（桌面是 future 取消），
 *    因此生产 `fetchCandidate` 必须把取消接到 `call.cancel()` 上。
 *
 * 纯协程逻辑，无 Android / OkHttp 依赖；`fetchCandidate` 由调用方注入（生产=HTTP，测试=假函数）。
 */
class MirrorRace(
    private val staggerMs: Long = Constants.FETCH_RACE_STAGGER_MS,
    maxConcurrent: Int = Constants.FETCH_RACE_MAX,
) {

    /** 单个候选。label 只用于「全部失败」时说明各自原因。 */
    data class Candidate(val url: String, val label: String)

    /** 跨 [race] 调用共享：同一个 fetcher 抓多个源时，候选并发总量也受此上限约束。 */
    private val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))

    /**
     * 竞速：返回首个通过校验的候选内容；全部失败抛 [IOException]，信息里带各自原因。
     *
     * @param fetchCandidate 抓取并校验单个候选；失败抛异常（即「该候选失败」，不中断整轮）。
     */
    suspend fun race(
        candidates: List<Candidate>,
        fetchCandidate: suspend (String) -> String,
    ): String = coroutineScope {
        if (candidates.isEmpty()) throw IOException("无候选")
        val winner = CompletableDeferred<String>()
        val errors = arrayOfNulls<Throwable>(candidates.size)
        val remaining = AtomicInteger(candidates.size)

        val jobs = candidates.mapIndexed { index, candidate ->
            launch {
                try {
                    // 错峰：direct 立即发，镜像依次延后 index×staggerMs，让直连有轻微先发优势
                    if (index > 0 && staggerMs > 0) delay(index * staggerMs)
                    semaphore.withPermit {
                        val text = fetchCandidate(candidate.url)
                        winner.complete(text)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[index] = e
                    if (remaining.decrementAndGet() == 0) {
                        winner.completeExceptionally(combine(candidates, errors))
                    }
                }
            }
        }

        try {
            winner.await()
        } finally {
            // 胜出后取消其余：落败者此刻还在下载整份列表，不 cancel 流量白烧
            jobs.forEach { it.cancel() }
        }
    }

    private fun combine(candidates: List<Candidate>, errors: Array<Throwable?>): IOException {
        val detail = candidates.mapIndexed { index, candidate ->
            "${candidate.label}: ${errors[index]?.message ?: "未知错误"}"
        }.joinToString("; ")
        return IOException("所有候选均失败 → $detail")
    }
}
