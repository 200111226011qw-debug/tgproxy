package com.tgproxy.data

import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.Stats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

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
     * 执行一轮刷新。
     *
     * @param proxyListUrl 数据源地址
     * @return Success / Failure；抓取或测试阶段的任何异常都转为 Failure（协程取消除外）
     */
    suspend fun refresh(proxyListUrl: String): RefreshResult = try {
        val text = source.fetch(proxyListUrl)
        val parseResult = ProxyParser().parse(text)
        val tested = tester.testAll(parseResult.entries)
        val available = tested.count { it.status == ProxyStatus.AVAILABLE }
        val stats = Stats(
            totalKept = tested.size,
            available = available,
            unavailable = tested.size - available,
            // 与桌面版一致：已排除 = 非 Telegram 类型合法条目 + 非法行
            excluded = parseResult.excludedCount + parseResult.droppedCount,
        )
        RefreshResult.Success(tested, stats, nowText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RefreshResult.Failure("抓取代理列表失败：${e.message ?: e.toString()}")
    }

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
