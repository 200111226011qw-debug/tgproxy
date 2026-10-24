package com.tgproxy.data

import com.tgproxy.Constants
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.Stats
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 代理刷新管线编排：fetch → parse → test → stats（等价桌面版 ProxyManager._run_refresh）。
 *
 * 纯 Kotlin 无 Android 依赖，可被 JVM 单测直接构造，也可被 P2 的 WorkManager 复用。
 * 本类不做刷新互斥（互斥只在 ViewModel，见 ARCHITECTURE.md §8）。
 */
class ProxyRepository(
    private val source: ProxySource,
    private val tester: LatencyTester = ProxyTester(),
) {

    /** 一轮刷新的结果：永不抛异常，失败转为 [Failure]。 */
    sealed interface RefreshResult {
        /** 成功：[entries] 为测试后（未排序）列表，[stats] 四项统计，[updatedAt] 本地时间文本。 */
        data class Success(
            val entries: List<ProxyEntry>,
            val stats: Stats,
            val updatedAt: String,
        ) : RefreshResult

        /** 失败：[error] 固定前缀「抓取代理列表失败：」。 */
        data class Failure(val error: String) : RefreshResult
    }

    /**
     * 执行一轮刷新（单源便捷入口，等价 [refresh] 传入单元素列表）。
     *
     * @param proxyListUrl 数据源地址
     * @return Success / Failure；抓取或测试阶段的任何异常都转为 Failure（协程取消除外）
     */
    suspend fun refresh(proxyListUrl: String): RefreshResult =
        refresh(listOf(proxyListUrl))

    /**
     * 执行一轮多源刷新：并发抓取全部数据源 -> 合并去重 -> 抽样 -> 测试。
     *
     * 与桌面版 _run_refresh 一致：部分源失败不影响整体；全部失败才转为 Failure，
     * 错误消息保留首个底层异常文本（兼容既有「抓取代理列表失败：<原因>」契约）。
     *
     * @param proxyListUrls 数据源地址列表（自动去重并截断至 Constants.FETCH_MAX_SOURCES）
     * @return Success / Failure（协程取消除外）
     */
    suspend fun refresh(proxyListUrls: List<String>): RefreshResult = try {
        val urls = proxyListUrls.distinct().take(Constants.FETCH_MAX_SOURCES)
        val texts = fetchAll(urls)
        val merged = mutableListOf<ProxyEntry>()
        val seenKept = HashSet<Pair<String, Int>>()
        var excludedCount = 0
        var droppedCount = 0
        for (text in texts) {
            val parseResult = ProxyParser().parse(text)
            excludedCount += parseResult.excludedCount
            droppedCount += parseResult.droppedCount
            for (entry in sampled(parseResult.entries, Constants.MAX_PER_SOURCE)) {
                val key = entry.host to entry.port
                if (key in seenKept) continue
                seenKept.add(key)
                merged.add(entry)
            }
        }
        val tested = tester.testAll(sampled(merged, Constants.MAX_TOTAL))
        val available = tested.count { it.status == ProxyStatus.AVAILABLE }
        val stats = Stats(
            totalKept = tested.size,
            available = available,
            unavailable = tested.size - available,
            // 与桌面版一致：已排除 = 非 Telegram 类型合法条目 + 非法行
            excluded = excludedCount + droppedCount,
        )
        RefreshResult.Success(tested, stats, nowText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RefreshResult.Failure("抓取代理列表失败：${e.message ?: e.toString()}")
    }

    /**
     * 并发抓取全部数据源；单个源失败降级为跳过，全部失败时抛出首个底层异常。
     */
    private suspend fun fetchAll(urls: List<String>): List<String> = coroutineScope {
        var firstError: Exception? = null
        val ok = urls.map { url ->
            async {
                try {
                    source.fetch(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstError == null) firstError = e
                    null
                }
            }
        }.awaitAll().filterNotNull()
        if (ok.isEmpty() && urls.isNotEmpty()) {
            throw firstError ?: IOException("全部 ${urls.size} 个数据源抓取失败")
        }
        ok
    }

    /** 条目数超上限时随机抽样（等价桌面版 random.sample）。 */
    private fun <T> sampled(entries: List<T>, limit: Int): List<T> =
        if (entries.size <= limit) entries else entries.shuffled().take(limit)

    /**
     * 排序：可用在前（0/1）→ 延迟升序（null 视为无穷大排末尾）→ host → port。
     * 返回新列表，不修改入参（等价桌面版 sort_proxies）。
     */
    fun sortProxies(proxies: List<ProxyEntry>): List<ProxyEntry> =
        proxies.sortedWith(PROXY_COMPARATOR)

    /** 本地时间文本，格式与桌面版 strftime 一致（minSdk 24 不用 java.time）。 */
    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** 排序比较器唯一来源（ARCHITECTURE.md §8）。 */
        val PROXY_COMPARATOR: Comparator<ProxyEntry> = compareBy(
            { entry -> if (entry.status == ProxyStatus.AVAILABLE) 0 else 1 },
            { entry -> entry.latencyMs?.toLong() ?: Long.MAX_VALUE },
            { entry -> entry.host },
            { entry -> entry.port },
        )
    }
}
