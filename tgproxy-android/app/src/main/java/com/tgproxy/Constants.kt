package com.tgproxy

/**
 * 全局常量集中地（与桌面版 proxy_manager.py 逐项一致，见 ARCHITECTURE.md §8）。
 *
 * 本对象为纯 Kotlin，无任何 Android 依赖，可在 JVM 单测中直接引用。
 */
object Constants {
    /** 默认数据源地址（桌面版 DEFAULT_PROXY_LIST_URL） */
    const val DEFAULT_PROXY_LIST_URL =
        "https://raw.githubusercontent.com/watchttvv/free-proxy-list/refs/heads/main/proxy.txt"

    /** 抓取请求 UA，与桌面版一致 */
    const val USER_AGENT = "tgproxy/1.0 (local Telegram proxy manager)"

    /** 抓取超时（OkHttp connect/read 均为此值），等价桌面版 FETCH_TIMEOUT_SEC=15 */
    const val FETCH_TIMEOUT_MS = 15_000L

    /** 单次 TCP connect 超时，等价桌面版 TEST_TIMEOUT_SEC=5.0 */
    const val TEST_TIMEOUT_MS = 5_000

    /** 并发测试上限，等价桌面版 TEST_MAX_WORKERS=20 */
    const val TEST_MAX_CONCURRENCY = 20

    /** 自动更新间隔默认值（分钟） */
    const val DEFAULT_INTERVAL_MINUTES = 10

    /** 自动更新间隔最小值（分钟） */
    const val MIN_INTERVAL_MINUTES = 1

    /** 自动更新间隔最大值（分钟） */
    const val MAX_INTERVAL_MINUTES = 1440

    /** 复制图标态反馈时长，等价桌面版 1500ms */
    const val COPY_FEEDBACK_MS = 1_500L

    /** 倒计时 tick 间隔 */
    const val COUNTDOWN_TICK_MS = 1_000L

    /** 一纳秒秒换算：倒计时计算用 */
    const val NANOS_PER_SEC = 1_000_000_000L
}
