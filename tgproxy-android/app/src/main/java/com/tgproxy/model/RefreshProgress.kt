package com.tgproxy.model

/**
 * 刷新阶段（进度上报用）。
 *
 * @property FETCH 抓取数据源
 * @property TCP_TEST TCP 预筛
 * @property TG_TEST TG 实测两关
 */
enum class RefreshStage { FETCH, TCP_TEST, TG_TEST }

/**
 * 单条进度：done/total 均为 0 表示阶段总量未知（只显示阶段名）。
 */
data class RefreshProgress(val stage: RefreshStage, val done: Int, val total: Int) {
    /** 阶段中文描述（UI 直显）。 */
    val label: String
        get() = when (stage) {
            RefreshStage.FETCH -> "抓取数据源"
            RefreshStage.TCP_TEST -> "TCP 预筛"
            RefreshStage.TG_TEST -> "TG 实测"
        }
}
