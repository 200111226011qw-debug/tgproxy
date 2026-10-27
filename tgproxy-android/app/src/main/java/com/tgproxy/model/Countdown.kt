package com.tgproxy.model

/**
 * 倒计时快照：距下次自动刷新的秒数 + 是否已启动。
 *
 * 为什么不放在 [ProxyUiState] 里：它**每秒变一次**。放在 uiState 里意味着每秒
 * 都要 `copy()` 出一个新快照，订阅 uiState 的整页（含 2000 条节点列表）跟着重组。
 * 拆成独立 StateFlow 之后，只有真正显示倒计时的两处订阅它：
 * 顶栏副标题、Hero 卡的「下次更新」。
 *
 * 与 `progress` 是同一类处理：**高频字段单独一条流**。
 *
 * @property sec 剩余秒数（≥0）
 * @property started 是否已经跑过至少一次 tick；false 时界面显示「--」
 */
data class Countdown(
    val sec: Int = 0,
    val started: Boolean = false,
)
