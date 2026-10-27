package com.tgproxy.ui.preview

import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.model.ProxyUiState
import com.tgproxy.model.StageDurations
import com.tgproxy.model.Stats
import com.tgproxy.model.TgGateStats
import com.tgproxy.model.TgVerdict

/**
 * 预览用假数据 —— 只服务 `@Preview`，不参与任何运行逻辑。
 *
 * 单独放一个文件是为了避免每处预览各写一套：同一组数字分散在几个文件里，
 * 改一次忘一处，看起来就像两套不同的界面。数值量级取自 2026-10-28 桌面
 * 基线的一轮真实分布（不是真实抓取结果，只为检查折行、间距、深浅色对比）。
 */

internal val PreviewStats = Stats(
    available = 11,
    unverified = 0,
    tgFailed = 260,
    overThreshold = 75,
    dropped = 1200,
    excluded = 0,
    blacklisted = 40,
    tested = 1546,
)

internal val PreviewTgStats = TgGateStats(
    dcFail = 1200,
    latencyRejected = 75,
    webFail = 260,
    ok = 11,
)

internal val PreviewDurations = StageDurations(
    fetchMs = 16_600,
    prepareMs = 900,
    tcpMs = 8_400,
    tgMs = 27_600,
    commitMs = 200,
    totalMs = 53_700,
)

/** 三条 SOCKS5（两关通过）+ 一条 MTProto（未验证），覆盖两种行样式。 */
internal val PreviewProxies = listOf(
    ProxyEntry(
        link = "socks5://5.6.7.8:1080",
        host = "5.6.7.8",
        port = 1080,
        type = ProxyType.SOCKS5,
        latencyMs = 387,
        status = ProxyStatus.AVAILABLE,
        tgVerdict = TgVerdict.PASS,
        location = "荷兰",
        isp = "AS21396",
        addedAt = "2026-10-28 03:12:40",
    ),
    ProxyEntry(
        link = "socks5://1.2.3.4:1080",
        host = "1.2.3.4",
        port = 1080,
        type = ProxyType.SOCKS5,
        latencyMs = 929,
        status = ProxyStatus.AVAILABLE,
        tgVerdict = TgVerdict.PASS,
        location = "德国",
        isp = "AS24940",
        addedAt = "2026-10-28 03:12:40",
    ),
    ProxyEntry(
        link = "socks5://9.9.9.9:1080",
        host = "9.9.9.9",
        port = 1080,
        type = ProxyType.SOCKS5,
        latencyMs = null,
        status = ProxyStatus.UNAVAILABLE,
        tgVerdict = TgVerdict.FAIL,
        addedAt = "2026-10-28 03:12:40",
    ),
    ProxyEntry(
        link = "https://t.me/proxy?server=mt.example.com&port=443&secret=ee00",
        host = "mt.example.com",
        port = 443,
        type = ProxyType.MTPROTO,
        tgVerdict = TgVerdict.UNVERIFIED,
        location = "美国",
        addedAt = "2026-10-28 03:12:40",
    ),
)

internal val PreviewUiState = ProxyUiState(
    updatedAt = "2026-10-28 03:12:40",
    fetching = false,
    lastDurationMs = 44_760,
    error = null,
    stats = PreviewStats,
    tgStats = PreviewTgStats,
    durations = PreviewDurations,
    proxies = PreviewProxies,
    countdownSec = 605,
    countdownStarted = true,
)
