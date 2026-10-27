package com.tgproxy.model

/**
 * UI 状态快照（PRD 5.4；桌面版 /api/proxies 响应体的安卓进程内等价物）。
 *
 * **不含刷新进度**：进度每测一个节点就变一次（2000 节点 = 2000 次），
 * 放在这里会让每次变化都 `copy()` 出一个新快照，进而带着节点列表、
 * 漏斗卡、耗时卡一起重组。它由 `MainViewModel.progress` 这条独立
 * StateFlow 承载，只有 Hero 卡订阅。
 *
 * @property updatedAt 最近一轮成功刷新的时间，格式 `yyyy-MM-dd HH:mm:ss`；从未成功时为 null
 * @property fetching 是否正在刷新（按钮禁用 + 列表遮罩）
 * @property lastDurationMs 最近一轮刷新耗时（毫秒）；从未完成过时为 null
 * @property error 错误信息（抓取失败等）；下一轮成功刷新时清除
 * @property stats 六项统计
 * @property proxies 已排序的代理列表（TG 实测通过在前、延迟升序）
 * @property countdownSec 距下次自动刷新的秒数（≥0）
 * @property countdownStarted false 时倒计时显示「--」
 * @property settingsMessage 间隔保存结果提示（不自动清除，下次保存时覆盖）
 * @property settingsMessageIsError 保存提示是否为错误态
 * @property logs 最近若干轮的记录（旧 → 新，最多 [com.tgproxy.Constants.ROUND_LOG_MAX_ENTRIES] 条）。
 *   只有刷新结束后才变，不属于高频字段；界面展示时倒序。
 */
data class ProxyUiState(
    val updatedAt: String? = null,
    val fetching: Boolean = false,
    val lastDurationMs: Long? = null,
    val error: String? = null,
    val stats: Stats = Stats(),
    /** 本轮 TG 两关分布（漏斗卡片用）。 */
    val tgStats: TgGateStats = TgGateStats(),
    /** 本轮分阶段耗时（时间轴卡片用）。 */
    val durations: StageDurations = StageDurations(),
    val proxies: List<ProxyEntry> = emptyList(),
    val countdownSec: Int = 0,
    val countdownStarted: Boolean = false,
    val settingsMessage: String? = null,
    val settingsMessageIsError: Boolean = false,
    val logs: List<RoundRecord> = emptyList(),
)
