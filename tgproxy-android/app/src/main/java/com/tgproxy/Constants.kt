package com.tgproxy

/**
 * 全局常量集中地（与桌面版 proxy_manager.py 逐项一致，见 ARCHITECTURE.md §8）。
 *
 * 本对象为纯 Kotlin，无任何 Android 依赖，可在 JVM 单测中直接引用。
 */
object Constants {
    /** 默认数据源地址（桌面版 DEFAULT_PROXY_LIST_URLS[0]，保留供单源设置与旧数据回退） */
    const val DEFAULT_PROXY_LIST_URL =
        "https://raw.githubusercontent.com/watchttvv/free-proxy-list/refs/heads/main/proxy.txt"

    /**
     * 默认数据源列表（与桌面版 DEFAULT_PROXY_LIST_URLS 一致，2026-10-25 统一体检后精选；
     * raw.githubusercontent.com 失败时抓取层自动回退 jsDelivr 镜像）。
     */
    val DEFAULT_PROXY_LIST_URLS = listOf(
        "https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks5.txt",
        "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=5000&country=all",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
        DEFAULT_PROXY_LIST_URL,
    )

    /** 抓取请求 UA，与桌面版一致 */
    const val USER_AGENT = "tgproxy/1.0 (local Telegram proxy manager)"

    /** 抓取超时（OkHttp connect/read 均为此值），等价桌面版 FETCH_TIMEOUT_SEC=15 */
    const val FETCH_TIMEOUT_MS = 15_000L

    /** 同时抓取的数据源上限，等价桌面版 FETCH_MAX_SOURCES=8 */
    const val FETCH_MAX_SOURCES = 8

    /** 单源解析条目抽样上限，等价桌面版 MAX_PER_SOURCE=1000 */
    const val MAX_PER_SOURCE = 1000

    /** 单轮测试总条目抽样上限，等价桌面版 MAX_TOTAL=3000 */
    const val MAX_TOTAL = 3000

    /** raw.githubusercontent.com 直连失败时依次尝试的 jsDelivr 镜像 */
    val MIRROR_HOSTS = listOf("cdn.jsdelivr.net", "gcore.jsdelivr.net", "fastly.jsdelivr.net")

    /** 单次 TCP connect 超时，等价桌面版 TEST_TIMEOUT_SEC=5.0 */
    const val TEST_TIMEOUT_MS = 5_000

    /** TCP 预筛并发上限（桌面版 TEST_MAX_WORKERS=150；移动端 128 平衡吞吐与功耗） */
    const val TEST_MAX_CONCURRENCY = 128

    // ============================================================================
    // TG 实测（两关串行，与桌面版 proxy_manager.py 逐项一致）
    // ============================================================================

    /** 第一关目标：Telegram 数据中心 IP（桌面版 TELEGRAM_DC_HOST/PORT） */
    const val TELEGRAM_DC_HOST = "149.154.167.51"
    const val TELEGRAM_DC_PORT = 443

    /** 第二关目标：Telegram 站点（桌面版 TELEGRAM_TEST_HOST/PORT） */
    const val TELEGRAM_TEST_HOST = "desktop.telegram.org"
    const val TELEGRAM_TEST_PORT = 443

    /** 单关预算（连接+握手+CONNECT 共享），等价桌面版 TG_TEST_TIMEOUT_SEC=8.0 */
    const val TG_TEST_TIMEOUT_MS = 8_000

    /** 单节点两关总预算，等价桌面版 TG_TEST_DEADLINE_SEC=20.0 */
    const val TG_TEST_DEADLINE_MS = 20_000

    /** TG 实测并发上限（桌面版 TG_TEST_MAX_WORKERS=100；移动端取 96——TG 多为 8s 超时等待，
     *  瓶颈在等待而非 CPU，高并发可显著缩短整轮耗时） */
    const val TG_TEST_MAX_CONCURRENCY = 96

    /** 延迟硬门槛：仅保留经代理到 TG 数据中心往返 ≤1000ms 的节点（桌面版 TG_LATENCY_LIMIT_MS） */
    const val TG_LATENCY_LIMIT_MS = 1_000

    // ============================================================================
    // 连续失败拉黑（v2.1.0，与桌面版 v1.9.0 对齐）
    // ============================================================================

    /** 连败达到此次数即拉黑（跳过后续轮次测试） */
    const val KNOWN_BAD_STREAK_LIMIT = 3

    /** 拉黑保质期：过期自动解封重测（免费池 IP 会轮换/复活） */
    const val KNOWN_BAD_EXPIRY_MS = 48 * 3_600_000L

    /** 拉黑表容量上限（超量按最久未失败淘汰，防无限膨胀） */
    const val KNOWN_BAD_MAX_ENTRIES = 10_000

    // ============================================================================
    // known_good 沉淀容量（与桌面版 known_good.json 对齐）
    // ============================================================================

    /**
     * 沉淀表容量上限：超量时按 TG 延迟升序保留最快的 200 条。
     *
     * 桌面版 `_save_known_good` 是「先按 tg_latency_ms 升序、再 records[:200]」，
     * 安卓此前整表覆盖且无排序——沉淀会无限增长，并在抽样时挤占新候选的配额
     * （`samplePreferred` 的 `limit - keep.size` 趋 0）。
     */
    const val KNOWN_GOOD_MAX_ENTRIES = 200

    /**
     * 轮次记录保留条数（`rounds.jsonl`）。
     *
     * 50 条按 10 分钟间隔约等于 8 小时的历史，够看清「刚才那几轮为什么失败」，
     * 又不至于让文件无限增长 —— 每轮一行，写一次全量重写也只有 ~10KB。
     */
    const val ROUND_LOG_MAX_ENTRIES = 50

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
