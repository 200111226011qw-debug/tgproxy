package com.tgproxy.model

/**
 * 一轮刷新的分阶段耗时（与桌面版 `stage_durations` 逐项对齐）。
 *
 * 为什么要它：只知道总耗时，看不出时间花在哪。桌面端实测过 —— 补上这个之后
 * 才发现 TG 阶段占 62%、TCP 只占 21%，从而把优化重点从流水线改到了超时与剪枝。
 *
 * 计时口径：用 `System.nanoTime()`，不用 `System.currentTimeMillis()`。
 * 后者会被 NTP 校时或用户改表跳变，算出的差值可能是负数或异常大。
 *
 * 阶段边界：
 * - [fetchMs]   并发抓取全部数据源（含单源失败降级）
 * - [prepareMs] 解析、去重、known_good 补入、黑名单过滤、抽样
 * - [tcpMs]     TCP 预筛线程池执行完毕
 * - [tgMs]      TG 两关实测（含第一关剪枝）
 * - [commitMs]  统计汇总、连败记账、known_bad/known_good 落盘
 *
 * [totalMs] 是独立测量的整轮耗时，不是五项相加 —— 相加之和不含阶段间隙，
 * 会低估。两者应当接近（差值来自纳秒转毫秒的截断）。
 */
data class StageDurations(
    val fetchMs: Long = 0,
    val prepareMs: Long = 0,
    val tcpMs: Long = 0,
    val tgMs: Long = 0,
    val commitMs: Long = 0,
    val totalMs: Long = 0,
) {
    /** 五项之和（用于与 [totalMs] 对账：应接近但不必然相等）。 */
    val measuredSumMs: Long
        get() = fetchMs + prepareMs + tcpMs + tgMs + commitMs

    companion object {
        /** 纳秒差转毫秒（向下取整，避免虚报）。 */
        fun msBetween(startNanos: Long, endNanos: Long): Long =
            (endNanos - startNanos) / 1_000_000L
    }
}
