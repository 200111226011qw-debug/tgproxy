package com.tgproxy.data

import com.tgproxy.Constants
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.model.RefreshProgress
import com.tgproxy.model.RefreshStage
import com.tgproxy.model.Stats
import com.tgproxy.model.TgVerdict
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 代理刷新管线编排（等价桌面版 ProxyManager._run_refresh 全流程）：
 *
 * 并发抓取 → 逐源解析/去重/抽样 → known_good 优先补入 → TCP 预筛（并发）→
 * TG 实测两关（仅 SOCKS5，经代理到 TG DC + 全链路站点）→ 1000ms 门槛 →
 * 五项统计（available+unverified+tgFailed+dropped=tested）→ known_good 沉淀。
 *
 * 纯 Kotlin 无 Android 依赖（OkHttp 抓取器由构造注入），可被 JVM 单测直接构造。
 * 本类不做刷新互斥（互斥只在 ViewModel，见 ARCHITECTURE.md §8）。
 */
class ProxyRepository(
    private val source: ProxySource,
    private val tester: LatencyTester = ProxyTester(),
    private val tgGate: TelegramGateCaller = DefaultTelegramGateCaller,
    private val knownGood: KnownGoodStore = KnownGoodStore.none(),
    private val tgLatencyLimitMs: Int = Constants.TG_LATENCY_LIMIT_MS,
    /** 阻塞测试的执行调度器（JVM 单测注入测试调度器避免跨线程派发竞态）。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 一轮刷新的结果：永不抛异常，失败转为 [Failure]。 */
    sealed interface RefreshResult {
        /**
         * 成功：[entries] 为最终保留列表（TG 实测通过且 ≤ 门槛，未排序），
         * [stats] 五项统计，[updatedAt] 本地时间文本，[keptKnownGood] 是否有 known_good 兜底。
         */
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
     */
    suspend fun refresh(proxyListUrl: String): RefreshResult =
        refresh(listOf(proxyListUrl))

    /**
     * 执行一轮多源刷新（全流程见类注释）。
     *
     * @param proxyListUrls 数据源地址列表（自动去重并截断至 Constants.FETCH_MAX_SOURCES）
     * @param onProgress 阶段进度回调（抓取逐源 / TCP 与 TG 逐条）；默认无操作
     * @return Success / Failure（协程取消除外）
     */
    suspend fun refresh(
        proxyListUrls: List<String>,
        onProgress: (RefreshProgress) -> Unit = {},
    ): RefreshResult = try {
        val urls = proxyListUrls.distinct().take(Constants.FETCH_MAX_SOURCES)
        val texts = fetchAll(urls) { done -> onProgress(RefreshProgress(RefreshStage.FETCH, done, urls.size)) }
        onProgress(RefreshProgress(RefreshStage.FETCH, urls.size, urls.size))

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

        // known_good 优先补入（不在池子里也补），并返回优先保留键集
        val preferredKeys = mergeKnownGood(merged, seenKept)

        // 全局抽样至 MAX_TOTAL：preferred 优先保留，其余随机
        val sampledTotal = samplePreferred(merged, preferredKeys, Constants.MAX_TOTAL)

        // 第一阶段：TCP 预筛（并发）
        val tested = tester.testAll(sampledTotal) { done, total ->
            onProgress(RefreshProgress(RefreshStage.TCP_TEST, done, total))
        }
        val tcpOk = tested.filter { it.status == ProxyStatus.AVAILABLE }

        // 第二阶段：TG 实测两关（仅 SOCKS5；MTProto 标记未验证）
        val verified = testTelegramEntries(tcpOk) { done, total ->
            onProgress(RefreshProgress(RefreshStage.TG_TEST, done, total))
        }

        // TG 两关通过且延迟 ≤ 门槛才保留；不通或超门槛剔除（含超慢口径，保证四项之和=测试总数）
        val kept = mutableListOf<ProxyEntry>()
        var unverified = 0
        var tgFailed = 0
        for (item in verified) {
            when (item.tgVerdict) {
                TgVerdict.UNVERIFIED -> unverified++
                TgVerdict.FAIL -> tgFailed++
                TgVerdict.PASS -> {
                    val latency = item.latencyMs
                    if (latency != null && latency <= tgLatencyLimitMs) {
                        kept.add(item)
                    } else {
                        tgFailed++
                    }
                }
            }
        }
        val stats = Stats(
            available = kept.size,
            unverified = unverified,
            tgFailed = tgFailed,
            dropped = tested.size - tcpOk.size,
            excluded = excludedCount + droppedCount,
            tested = tested.size,
        )
        val result = RefreshResult.Success(kept, stats, nowText())
        // 沉淀本轮验证通过的节点（开关关闭时不写）；失败静默（下轮再试）
        knownGood.save(kept)
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RefreshResult.Failure("抓取代理列表失败：${e.message ?: e.toString()}")
    }

    /**
     * 并发抓取全部数据源；单个源失败降级为跳过，全部失败时抛出首个底层异常。
     * 每完成一个源回调一次（无论成败）。
     */
    private suspend fun fetchAll(
        urls: List<String>,
        onOneDone: (Int) -> Unit,
    ): List<String> = coroutineScope {
        var firstError: Exception? = null
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val ok = urls.map { url ->
            async {
                try {
                    source.fetch(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstError == null) firstError = e
                    null
                } finally {
                    onOneDone(counter.incrementAndGet())
                }
            }
        }.awaitAll().filterNotNull()
        if (ok.isEmpty() && urls.isNotEmpty()) {
            throw firstError ?: IOException("全部 ${urls.size} 个数据源抓取失败")
        }
        ok
    }

    /**
     * known_good 补入：上轮验证通过但不在本轮候选里的节点补回候选，
     * 返回优先保留键集（抽样时优先保留，等价桌面版 _merge_known_good）。
     * 开关关闭时不读取、不补入。
     */
    private fun mergeKnownGood(merged: MutableList<ProxyEntry>, seen: MutableSet<Pair<String, Int>>): Set<Pair<String, Int>> {
        if (!knownGood.enabled) return emptySet()
        val preferred = HashSet<Pair<String, Int>>()
        for (entry in knownGood.load()) {
            preferred.add(entry.host to entry.port)
            if ((entry.host to entry.port) !in seen) {
                seen.add(entry.host to entry.port)
                merged.add(entry)
            }
        }
        return preferred
    }

    /** 超上限抽样：preferred 全保留，其余随机补足（等价桌面版 preferred+random.sample）。 */
    private fun samplePreferred(
        merged: List<ProxyEntry>,
        preferred: Set<Pair<String, Int>>,
        limit: Int,
    ): List<ProxyEntry> {
        if (merged.size <= limit) return merged
        val keep = merged.filter { (it.host to it.port) in preferred }
        val pool = merged.filter { (it.host to it.port) !in preferred }.shuffled()
        return keep + pool.take((limit - keep.size).coerceAtLeast(0))
    }

    /**
     * 并发 TG 实测（等价桌面版 _test_telegram_entries）：
     * 每节点一条线程内先 DC 后站点串行两关（避免对同一免费代理同时开两条连接触发限流的假失败）；
     * 仅 SOCKS5 参与；MTProto 标记 UNVERIFIED。
     */
    private suspend fun testTelegramEntries(
        entries: List<ProxyEntry>,
        onProgress: (Int, Int) -> Unit,
    ): List<ProxyEntry> {
        val socks5Count = entries.count { it.type == ProxyType.SOCKS5 }
        val done = java.util.concurrent.atomic.AtomicInteger()
        val semaphore = Semaphore(Constants.TG_TEST_MAX_CONCURRENCY)
        return withContext(ioDispatcher) {
            coroutineScope {
                entries.map { entry ->
                    if (entry.type != ProxyType.SOCKS5) {
                        // 未参与 SOCKS5 实测的（MTProto）标记为未验证，不计入 TG 进度
                        async { entry.copy(tgVerdict = TgVerdict.UNVERIFIED, latencyMs = null) }
                    } else {
                        async {
                            semaphore.withPermit {
                                val latency = try {
                                    tgGate.test(entry.host, entry.port)
                                } catch (e: Exception) {
                                    null
                                }
                                val verdict = if (latency != null) TgVerdict.PASS else TgVerdict.FAIL
                                entry.copy(
                                    latencyMs = latency,
                                    tgVerdict = verdict,
                                    status = if (latency != null) ProxyStatus.AVAILABLE else ProxyStatus.UNAVAILABLE,
                                )
                            }.also {
                                onProgress(done.incrementAndGet(), socks5Count)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** known_good 开关同步（设置流首帧与切换时调用；立即生效）。 */
    fun setKnownGoodEnabled(value: Boolean) {
        knownGood.enabled = value
    }

    /** 读取 known_good 沉淀（启动先行展示用；开关关闭恒空）。 */
    fun loadKnownGood(): List<ProxyEntry> = knownGood.load()

    /** 条目数超上限时随机抽样（等价桌面版 random.sample）。 */
    private fun <T> sampled(entries: List<T>, limit: Int): List<T> =
        if (entries.size <= limit) entries else entries.shuffled().take(limit)

    /**
     * 排序：TG 实测通过的在前按 TG 延迟升序，未验证的（MTProto）分组在后
     * （等价桌面版 sort_proxies）。
     */
    fun sortProxies(proxies: List<ProxyEntry>): List<ProxyEntry> =
        proxies.sortedWith(PROXY_COMPARATOR)

    /** 本地时间文本，格式与桌面版 strftime 一致（minSdk 24 不用 java.time）。 */
    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** 排序比较器唯一来源（ARCHITECTURE.md §8）。 */
        val PROXY_COMPARATOR: Comparator<ProxyEntry> = compareBy(
            { entry -> if (entry.tgVerdict == TgVerdict.PASS) 0 else 1 },
            { entry -> entry.latencyMs?.toLong() ?: Long.MAX_VALUE },
            { entry -> entry.host },
            { entry -> entry.port },
        )
    }
}
