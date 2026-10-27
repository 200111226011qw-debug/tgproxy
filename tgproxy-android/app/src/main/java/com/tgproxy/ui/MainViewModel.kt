package com.tgproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgproxy.Constants
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.ProxyRepository.RefreshResult
import com.tgproxy.data.RoundLogger
import com.tgproxy.data.SettingsStore
import com.tgproxy.model.Countdown
import com.tgproxy.model.ProxyUiState
import com.tgproxy.model.RefreshProgress
import com.tgproxy.model.RoundRecord
import com.tgproxy.model.Settings
import com.tgproxy.model.StageDurations
import com.tgproxy.model.Stats
import com.tgproxy.model.TgGateStats
import com.tgproxy.model.ThemeMode
import com.tgproxy.model.parseIntervalInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 时间源（测试注入假时钟实现虚拟时间；生产用 System.nanoTime）。
 */
fun interface TimeSource {
    fun nowNanos(): Long
}

/**
 * 主 ViewModel：UI 唯一状态源（StateFlow 直推，替代桌面版轮询 + HTTP 快照）。
 *
 * 行为契约（ARCHITECTURE.md §3.4）：
 * 1. init：known_good 先行展示（开关开且有沉淀时列表立即可见）→ 收集 settings
 *    （首个发射启动倒计时，间隔变化重置倒计时）+ 立即刷新（启动即刷新 P0-10）；
 * 2. refreshAwait：`Mutex.tryLock` 互斥（第二调用直接忽略，等价桌面版 `_fetching`）；
 *    成功写 proxies/stats/updatedAt 并清 error；本轮 0 达标但已有列表时保留旧列表
 *    （等价桌面版「失败时保留旧列表」）；失败**仅**写 error（旧列表保留）；
 *    刷新期间 progress 逐阶段透传（抓取/TCP/TG done/total），走**独立** StateFlow
 *    [progress] 而非 uiState（高频字段，混进 uiState 会让整页跟着重组）；
 * 3. 倒计时循环：每秒 tick 写 countdownSec；到期后挂起等待一轮刷新完成再重置 deadline；
 * 4. onResumeSync：恢复 tick（保留 deadline）；超龄则立即补刷并重置 deadline；
 * 5. saveIntervalInput：非法输入只提示不持久化；合法写 DataStore，「已保存，立即生效」；
 * 6. setKnownGoodEnabled：写 DataStore 并同步 known_good 存储开关（立即生效）。
 */
class MainViewModel(
    private val repository: ProxyRepository,
    private val settingsStore: SettingsStore,
    private val timeSource: TimeSource = TimeSource { System.nanoTime() },
    /** 轮次记录存储；默认禁用态（不读不写），生产由 AppContainer 注入真实文件。 */
    private val roundLogger: RoundLogger = RoundLogger.none(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    /**
     * 刷新进度：独立于 [uiState] 的高频通道。
     *
     * 为什么不放 uiState：进度每测一个节点就变一次（2000 节点 ≈ 2000 次），
     * 每次都要 `copy()` 出一个新 `ProxyUiState`；而 `ProxyUiState` 含
     * `List<ProxyEntry>`（接口类型 → Compose unstable），订阅它的整页都会
     * 跟着重组。拆出来之后只有 Hero 卡订阅这条流，节点列表/漏斗/耗时卡
     * 在进度跳动时纹丝不动。
     *
     * 生命周期：每轮刷新开始置 null，结束（含异常）置 null。
     */
    private val _progress = MutableStateFlow<RefreshProgress?>(null)
    val progress: StateFlow<RefreshProgress?> = _progress.asStateFlow()

    /**
     * 倒计时：另一条高频通道（每秒一次）。
     *
     * 与 [progress] 同一个道理 —— 放在 uiState 里，每秒都要 `copy()` 一次快照，
     * 订阅 uiState 的整页跟着重组。拆出来之后只有顶栏副标题和 Hero 卡订阅它。
     */
    private val _countdown = MutableStateFlow(Countdown())
    val countdown: StateFlow<Countdown> = _countdown.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** 刷新互斥锁：只存在于 ViewModel（Repository 不做互斥）。 */
    private val refreshMutex = Mutex()

    /** 倒计时协程；onPause 取消（deadline 保留），onResume 重启。 */
    private var countdownJob: Job? = null

    /** 下次自动刷新的纳秒时间点（虚拟时钟基准由 timeSource 决定）。 */
    private var deadlineNs: Long = 0L

    /** 当前间隔（秒），来自 settings Flow。 */
    private var intervalSec: Long = Constants.DEFAULT_INTERVAL_MINUTES * 60L

    init {
        // 先读历史轮次：冷启动进日志页就有内容，不用等下一轮刷新
        viewModelScope.launch {
            val recent = roundLogger.load()
            // 只在本轮还没写入任何记录时填充：刷新可能已经先落了一条，
            // 别用旧历史把它盖掉（与 known_good 先行展示同一套守卫思路）
            if (recent.isNotEmpty() && _uiState.value.logs.isEmpty()) {
                _uiState.value = _uiState.value.copy(logs = recent)
            }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { newSettings ->
                val intervalChanged =
                    _settings.value.intervalMinutes != newSettings.intervalMinutes
                val knownGoodChanged =
                    _settings.value.knownGoodEnabled != newSettings.knownGoodEnabled
                _settings.value = newSettings
                repository.setKnownGoodEnabled(newSettings.knownGoodEnabled)
                intervalSec = newSettings.intervalMinutes
                    .coerceAtLeast(Constants.MIN_INTERVAL_MINUTES) * 60L
                when {
                    countdownJob == null -> {
                        // known_good 先行展示：开关开且有沉淀时，启动即有列表（下轮刷新再验）
                        if (newSettings.knownGoodEnabled) {
                            val saved = repository.loadKnownGood()
                            if (saved.isNotEmpty() && _uiState.value.proxies.isEmpty()) {
                                _uiState.value = _uiState.value.copy(
                                    proxies = repository.sortProxies(saved),
                                    updatedAt = null,
                                )
                            }
                        }
                        launchCountdown(resetDeadline = true)
                    }
                    intervalChanged -> launchCountdown(resetDeadline = true)
                    knownGoodChanged && !newSettings.knownGoodEnabled -> {
                        // 关闭沉淀：不影响当前列表，仅停止后续读写（存储侧已同步）
                    }
                }
            }
        }
        // 启动即刷新（P0-10，等价桌面版 main() 的 refresh_async）
        refresh()
    }

    /**
     * 触发一轮刷新（启动/手动/下拉/到期/补刷）：fire-and-forget，互斥由 tryLock 保证。
     */
    fun refresh() {
        viewModelScope.launch { refreshAwait() }
    }

    /**
     * 挂起执行一轮刷新；已在刷新中（tryLock 失败）则直接返回。
     */
    private suspend fun refreshAwait() {
        if (!refreshMutex.tryLock()) return
        val startedNanos = timeSource.nowNanos()
        // 本轮记录：在锁内组装，锁外落盘（见 finally 之后的注释）
        var record: RoundRecord? = null
        try {
            _uiState.value = _uiState.value.copy(fetching = true, error = null)
            _progress.value = null
            val onProgress: (RefreshProgress) -> Unit = { progress ->
                // 只写独立通道，绝不碰 _uiState —— 这条路径每轮要跑上千次
                _progress.value = progress
            }
            val result = repository.refresh(resolveUrls(_settings.value), onProgress)
            // 本轮耗时在这里统一算：成功/失败都要，且日志与 lastDurationMs 用同一个数
            val elapsedMs = (timeSource.nowNanos() - startedNanos) / 1_000_000L
            // 成功轮也可能带提示（0 达标保留旧列表）；它就是界面那根横幅的文案
            var note: String? = null
            when (result) {
                is RefreshResult.Success -> {
                    val previous = _uiState.value.proxies
                    if (result.entries.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            proxies = repository.sortProxies(result.entries),
                            stats = result.stats,
                            updatedAt = result.updatedAt,
                            lastDurationMs = elapsedMs,
                            tgStats = result.gateStats,
                            durations = result.durations,
                            error = null,
                        )
                    } else if (previous.isNotEmpty()) {
                        // 本轮 0 达标：保留上一轮列表（等价桌面版）
                        note = "本轮 0 个节点通过 TG 实测（门槛 ${Constants.TG_LATENCY_LIMIT_MS}ms），已保留上一轮列表"
                        _uiState.value = _uiState.value.copy(
                            stats = result.stats,
                            updatedAt = result.updatedAt,
                            lastDurationMs = elapsedMs,
                            tgStats = result.gateStats,
                            durations = result.durations,
                            error = note,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            proxies = emptyList(),
                            stats = result.stats,
                            updatedAt = result.updatedAt,
                            lastDurationMs = elapsedMs,
                            tgStats = result.gateStats,
                            durations = result.durations,
                            error = null,
                        )
                    }
                }
                is RefreshResult.Failure -> {
                    // 失败降级：仅更新 error，proxies/stats/updatedAt 原样保留（P0-1/P0-9）
                    note = result.error
                    _uiState.value = _uiState.value.copy(error = result.error)
                }
            }
            // 轮次记录：成功失败都记。失败轮不填 stats/durations —— 阶段没跑完，
            // 填 0 会被读成「测了 0 个」，不如留空让界面只显示耗时与错误。
            // 这里只组装（纯内存），落盘放到互斥区外。
            record = RoundRecord(
                timestamp = RoundLogger.formatTimestamp(System.currentTimeMillis()),
                success = result is RefreshResult.Success,
                error = note,
                elapsedMs = elapsedMs,
                stats = (result as? RefreshResult.Success)?.stats ?: Stats(),
                tgStats = (result as? RefreshResult.Success)?.gateStats ?: TgGateStats(),
                durations = (result as? RefreshResult.Success)?.durations ?: StageDurations(),
            )
        } finally {
            _uiState.value = _uiState.value.copy(fetching = false)
            _progress.value = null
            refreshMutex.unlock()
        }
        // 落盘放在互斥区外：一次文件写入不该决定「下一轮能不能开始」。
        // 放在 finally 里的话，锁的释放要等 append 从 IO 线程切回来 ——
        // 生产上只是多占几毫秒，测试里（虚拟时间）会让下一轮 refresh 直接被
        // tryLock 挡掉，整串用例连锁失败。
        val pending = record ?: return
        val logs = roundLogger.append(pending)
        if (logs.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(logs = logs)
        }
    }

    /**
     * 本轮刷新的数据源列表：内置多源 + 用户自定义源（不与内置重复时追加）。
     */
    private fun resolveUrls(settings: Settings): List<String> =
        (Constants.DEFAULT_PROXY_LIST_URLS + listOfNotNull(settings.proxyListUrl.takeIf { url -> url !in Constants.DEFAULT_PROXY_LIST_URLS },)).distinct().take(Constants.FETCH_MAX_SOURCES)

    /**
     * 保存间隔输入：非法（非整数/越界/空）→ 错误提示且不持久化；
     * 合法 → 持久化 + 「已保存，立即生效」；倒计时重置由 settings Flow 发射触发。
     */
    fun saveIntervalInput(text: String) {
        val value = parseIntervalInput(text)
        if (value == null) {
            _uiState.value = _uiState.value.copy(
                settingsMessage = "请输入 1~1440 之间的整数",
                settingsMessageIsError = true,
            )
            return
        }
        viewModelScope.launch {
            try {
                settingsStore.setIntervalMinutes(value)
                _uiState.value = _uiState.value.copy(
                    settingsMessage = "已保存，立即生效",
                    settingsMessageIsError = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    settingsMessage = "保存失败：${e.message ?: e.toString()}",
                    settingsMessageIsError = true,
                )
            }
        }
    }

    /** 切换主题模式（循环 SYSTEM → LIGHT → DARK）。 */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    /** 切换 known_good 沉淀开关：持久化 + 存储侧立即生效。 */
    fun setKnownGoodEnabled(value: Boolean) {
        viewModelScope.launch {
            try {
                settingsStore.setKnownGoodEnabled(value)
                repository.setKnownGoodEnabled(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    settingsMessage = "保存失败：${e.message ?: e.toString()}",
                    settingsMessageIsError = true,
                )
            }
        }
    }

    /**
     * 回前台同步（ON_RESUME）：tick 已停则重启循环但不重置 deadline（保进度）；
     * 距上次成功刷新已超过间隔（超龄）则立即补刷并重置 deadline。
     */
    fun onResumeSync() {
        if (countdownJob?.isActive != true) {
            launchCountdown(resetDeadline = false)
        }
        val now = timeSource.nowNanos()
        if (deadlineNs > 0 && now >= deadlineNs) {
            refresh()
            deadlineNs = now + intervalSec * Constants.NANOS_PER_SEC
        }
    }

    /** 退后台：取消倒计时 tick 省电，deadline 保留。 */
    fun onPause() {
        countdownJob?.cancel()
    }

    /**
     * 启动（或重启）倒计时循环。
     *
     * @param resetDeadline 是否重置 deadline 为 now + interval（true：启动/间隔变化；false：回前台恢复）
     */
    private fun launchCountdown(resetDeadline: Boolean) {
        if (resetDeadline) {
            deadlineNs = timeSource.nowNanos() + intervalSec * Constants.NANOS_PER_SEC
        }
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                val remainingNs = deadlineNs - timeSource.nowNanos()
                if (remainingNs > 0) {
                    // 只写独立通道：每秒一次，绝不能碰 _uiState
                    _countdown.value = Countdown(
                        sec = (remainingNs / Constants.NANOS_PER_SEC)
                            .toInt()
                            .coerceAtLeast(0),
                        started = true,
                    )
                    delay(Constants.COUNTDOWN_TICK_MS)
                } else {
                    _countdown.value = Countdown(sec = 0, started = true)
                    // 到期：等待本轮刷新完成（tryLock 被手动刷新占用时立即返回）
                    refreshAwait()
                    deadlineNs = timeSource.nowNanos() + intervalSec * Constants.NANOS_PER_SEC
                }
            }
        }
    }

    companion object {
        /**
         * 手工 Factory（供 `by viewModels { ... }` 使用）。
         */
        fun factory(
            repository: ProxyRepository,
            settingsStore: SettingsStore,
            roundLogger: RoundLogger,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    "Unknown ViewModel class: ${modelClass.name}"
                }
                return MainViewModel(repository, settingsStore, roundLogger = roundLogger) as T
            }
        }
    }
}
