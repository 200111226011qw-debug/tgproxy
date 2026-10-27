package com.tgproxy.data

import com.tgproxy.Constants
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 连通性测试接口（面向接口编程，便于 JVM 单测注入假件）。
 */
fun interface LatencyTester {
    /**
     * 测试全部条目并回填延迟/状态；返回列表与输入顺序一致。
     * [onProgress] 每完成一条回调一次（done, total）。
     */
    suspend fun testAll(
        entries: List<ProxyEntry>,
        onProgress: (Int, Int) -> Unit,
    ): List<ProxyEntry>
}

/** 不关心进度的便捷入口。 */
suspend fun LatencyTester.testAll(entries: List<ProxyEntry>): List<ProxyEntry> =
    testAll(entries) { _, _ -> }

/**
 * TCP connect 连通性预筛（等价桌面版 test_tcp_latency + ThreadPoolExecutor(150)）。
 *
 * - 单连接超时 5s（Constants.TEST_TIMEOUT_MS）
 * - 并发上限 128（Semaphore，等价桌面版 TEST_MAX_WORKERS=150）
 * - `async` + `awaitAll` 保序；成功记往返毫秒并标 AVAILABLE，失败 latencyMs=null 标 UNAVAILABLE
 * - 阻塞式 Socket 调用包裹在 Dispatchers.IO 中（禁止主线程 IO）
 */
class ProxyTester(
    private val maxConcurrency: Int = Constants.TEST_MAX_CONCURRENCY,
    private val timeoutMs: Int = Constants.TEST_TIMEOUT_MS,
    /** 阻塞 Socket IO 的执行调度器（JVM 单测注入测试调度器避免跨线程派发竞态）。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LatencyTester {

    override suspend fun testAll(
        entries: List<ProxyEntry>,
        onProgress: (Int, Int) -> Unit,
    ): List<ProxyEntry> {
        if (entries.isEmpty()) return emptyList()
        val semaphore = Semaphore(maxConcurrency)
        val done = java.util.concurrent.atomic.AtomicInteger()
        return withContext(ioDispatcher) {
            coroutineScope {
                entries.map { entry ->
                    async {
                        semaphore.withPermit {
                            val latency = testOne(entry.host, entry.port)
                            entry.copy(
                                latencyMs = latency,
                                status = if (latency != null) {
                                    ProxyStatus.AVAILABLE
                                } else {
                                    ProxyStatus.UNAVAILABLE
                                },
                            )
                        }.also {
                            onProgress(done.incrementAndGet(), entries.size)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * 对单个 host:port 做 TCP connect，返回往返毫秒；超时/拒绝/错误返回 null（等价 test_tcp_latency）。
     */
    fun testOne(host: String, port: Int): Int? {
        val started = System.nanoTime()
        return try {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } finally {
                try {
                    socket.close()
                } catch (e: IOException) {
                    // 关闭失败不影响测试结果
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            elapsedMs.roundToInt()
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
