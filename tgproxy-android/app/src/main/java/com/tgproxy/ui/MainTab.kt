package com.tgproxy.ui

/**
 * 主界面三个页签。
 *
 * 顺序即 `HorizontalPager` 的页序，也即底部 Tab 的排列顺序 —— 只有这一处定义，
 * 别再在别处写死 0/1/2。
 */
enum class MainTab(val title: String) {
    DASHBOARD("仪表盘"),
    NODES("节点列表"),
    LOGS("日志"),
}
