package com.tgproxy.model

/**
 * 一轮刷新的历史记录（`rounds.jsonl` 的一行）。
 *
 * 用途：日志页按轮次回看「什么时候、成没成、花了多久、漏斗长什么样」。
 * 与 [ProxyUiState] 的区别 —— 那是**当前**状态，这是**过去**的结果，不带
 * `progress`（进行中的状态没有历史意义）。
 *
 * @property timestamp 轮次开始时间，格式 `yyyy-MM-dd HH:mm:ss`（与 `updatedAt` 同格式）
 * @property success 流程是否走通。注意**不等于**「本轮有可用节点」：0 达标但保留
 *   旧列表也算走通，那种情况会在 [error] 里带一条提示
 * @property error 失败原因；成功时可能带警告（如「本轮 0 个节点通过…已保留上一轮列表」），
 *   因为它在界面上和失败共用同一根横幅
 * @property elapsedMs 本轮实际耗时（ViewModel 侧观测，成功失败都有值）
 * @property durations 分阶段耗时（仓库内部测量）。失败轮为空 —— 阶段没跑完就没有意义。
 *   与 [elapsedMs] 接近但不等（后者含 ViewModel 侧开销），**展示只用 [elapsedMs]**，
 *   阶段拆分只用于展开详情
 * @property seq 单调递增的稳定标识，由 [com.tgproxy.data.RoundLogger] 赋值。
 *   UI 拿它当列表 key —— 用 timestamp 当 key 会在同一秒内两轮时撞车，
 *   `LazyColumn` 撞 key 会直接抛异常
 *
 * 刻意**没有** `testedCount` / `availableCount`：它们就是 [stats] 里的
 * `tested` / `available`，同一个事实不写两遍（否则早晚一处改一处忘）。
 */
data class RoundRecord(
    val timestamp: String,
    val success: Boolean,
    val error: String? = null,
    val elapsedMs: Long = 0L,
    val stats: Stats = Stats(),
    val tgStats: TgGateStats = TgGateStats(),
    val durations: StageDurations = StageDurations(),
    val seq: Long = 0L,
) {
    /** 列表展示用：成功且无提示 / 成功但有提示 / 失败。 */
    val hasNote: Boolean get() = error != null
}
