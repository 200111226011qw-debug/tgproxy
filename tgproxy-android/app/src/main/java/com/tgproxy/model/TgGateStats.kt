package com.tgproxy.model

/**
 * 单节点 TG 实测的落点（两关漏斗的四个桶）。
 *
 * 为什么要区分：只看「通过/不通过」无法解释「本轮测了 2000 个却只有 10 个可用」。
 * 桌面端补上这个分布后才发现 —— 86 个过两关的节点里只有 11 个 ≤1000ms，
 * 其余 75 个全是超门槛，于是优化重点才落到剪枝上。
 */
enum class TgGateStage {
    /** 第一关（经代理 CONNECT Telegram DC）没连上。 */
    DC_FAIL,

    /** 第一关连上了，但往返已超延迟门槛 —— 被剪枝，第二关未跑。 */
    LATENCY_REJECTED,

    /** 第二关（TLS + HTTP 访问站点）失败。含「第一关耗尽预算、第二关没来得及跑」。 */
    WEB_FAIL,

    /** 两关全过。 */
    OK,
}

/** 闸门返回：落点 + 延迟毫秒（失败为 null）。 */
data class GateOutcome(val stage: TgGateStage, val latencyMs: Int?)

/**
 * 本轮 TG 两关分布。
 *
 * 注意 [LATENCY_REJECTED] 与 [DC_FAIL] 的区别：前者是「连得上但太慢」，
 * 后者是「根本连不上」。混为一谈会让漏斗撒谎，也会让连败计数算错
 * （超门槛的节点不该累计连败，见 ProxyRepository 的 recordSuccess 分支）。
 */
data class TgGateStats(
    val dcFail: Int = 0,
    val latencyRejected: Int = 0,
    val webFail: Int = 0,
    val ok: Int = 0,
) {
    val total: Int get() = dcFail + latencyRejected + webFail + ok
}
