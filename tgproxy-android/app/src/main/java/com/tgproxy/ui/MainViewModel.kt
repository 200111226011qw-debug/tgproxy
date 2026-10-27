package com.tgproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgproxy.Constants
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.ProxyRepository.RefreshResult
import com.tgproxy.data.SettingsStore
import com.tgproxy.model.ProxyUiState
import com.tgproxy.model.RefreshProgress
import com.tgproxy.model.Settings
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
 *    刷新期间 progress 逐阶段透传（抓取/TCP/TG done/total）；
 * 3. 倒计时循环：每秒 tick 写 countdownSec；到期后挂起等待一轮刷新完成再重置 deadline；
 * 4. onResumeSync：恢复 tick（保留 deadline）；超龄则立即补刷并重置 deadline；
 * 5. saveIntervalInput：非法输入只提示不持久化；合法写 DataStore，「已保存，立即生效」；
 * 6. setKnownGoodEnabled：写 DataStore 并同步 known_good 存储开关（立即生效）。
 */
class MainViewModel(
    private val repository: ProxyRepository,
    private val settingsStore: SettingsStore,
    private val timeSource: TimeSource = TimeSource { System.nanoTime() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

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
        try {
            _uiState.value = _uiState.value.copy(fetching = true, error = null, progress = null)
            val onProgress: (RefreshProgress) -> Unit = { progress ->
                _uiState.value = _uiState.value.copy(progress = progress)
            }
            when (val result = repository.refresh(resolveUrls(_settings.value), onProgress)) {
                is RefreshResult.Success -> {
                    val previous = _uiState.value.proxies
                    val durationMs = (timeSource.nowNanos() - startedNanos) / 1_000_000L
                    if (result.entries.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            proxies = repository.sortProxies(result.entries),
                            stats = result.stats,
                            updatedAt = result.updatedAt,
                            lastDurationMs = durationMs,
                            tgStats = result.gateStats,
                            durations = result.durations,
                            error = null,
                        )
                    } else if (previous.isNotEmpty()) {
                        // 本轮 0 达标：保留上一轮列表（等价桌面版）
                        _uiState.value = _uiState.value.copy(
                            stats = result.stats,
                            updatedAt = result.updatedAt,
                            lastDurationMs = durationMs,
                            tgStats = result.gateStats,
                            durations = result.durations,
                            error = "本轮 0 个节点通过 TG 实测（门槛 ${Constants.TG_LATENCY_LIMIT_MS}ms），已保留上一轮列表",
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            proxies = emptyList(),
                            stats = result.stats,
                            updatedAt = result.updatedAt,
                            lastDurationMs = durationMs,
                            tgStats = result.gateStats,
                            durations = result.durations,
                            error = null,
                        )
                    }
                }
                is RefreshResult.Failure -> {
                    // 失败降级：仅更新 error，proxies/stats/updatedAt 原样保留（P0-1/P0-9）
                    _uiState.value = _uiState.value.copy(error = result.error)
                }
            }
        } finally {
            _uiState.value = _uiState.value.copy(fetching = false, progress = null)
            refreshMutex.unlock()
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
                    _uiState.value = _uiState.value.copy(
                        countdownSec = (remainingNs / Constants.NANOS_PER_SEC)
                            .toInt()
                            .coerceAtLeast(0),
                        countdownStarted = true,
                    )
                    delay(Constants.COUNTDOWN_TICK_MS)
                } else {
                    _uiState.value = _uiState.value.copy(
                        countdownSec = 0,
                        countdownStarted = true,
                    )
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
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    "Unknown ViewModel class: ${modelClass.name}"
                }
                return MainViewModel(repository, settingsStore) as T
            }
        }
    }
}
