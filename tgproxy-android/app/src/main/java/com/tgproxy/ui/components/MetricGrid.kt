package com.tgproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.StageDurations
import com.tgproxy.model.Stats
import com.tgproxy.model.TgGateStats
import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 仪表盘指标网格（非惰性）。
 *
 * 为什么不用 `LazyVerticalGrid`：它放在 `LazyColumn` 的 item 里会抛
 * "Vertically scrollable component was measured with an infinity maximum height"
 * —— 嵌套滚动容器的经典坑。仪表盘只有 6 个指标，普通 Row 分块足够，
 * 也省掉 IntrinsicSize 那堆麻烦。
 *
 * 列数按可用宽度切：手机 2 列，平板（≥480dp）3 列。
 *
 * 注意：本组件不自带水平内边距 —— 调用方的 `LazyColumn.contentPadding`
 * 已经给了 16dp。自己再加一层会变成 32dp，比上下相邻的 Hero 卡窄一圈。
 */
@Composable
fun MetricGrid(
    stats: Stats,
    tgStats: TgGateStats,
    modifier: Modifier = Modifier,
) {
    val palette = dashboardAccents()
    val items = listOf(
        MetricItem("可用节点", stats.available, palette.green, highlight = true),
        MetricItem("超门槛剔除", tgStats.latencyRejected, palette.amber),
        MetricItem("DC 连接失败", tgStats.dcFail, palette.red),
        MetricItem("站点验证失败", tgStats.webFail, palette.red),
        MetricItem("TCP 不通", stats.dropped, palette.gray),
        MetricItem("已拉黑", stats.blacklisted, palette.gray),
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(columns).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { item ->
                        MetricCard(item, Modifier.weight(1f))
                    }
                    // 最后一行不满时补占位，避免卡片被拉宽
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class MetricItem(
    val label: String,
    val value: Int,
    val accent: Color,
    val highlight: Boolean = false,
)

@Composable
private fun MetricCard(item: MetricItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.value.toString(),
            style = if (item.highlight) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.titleLarge
            },
            fontWeight = if (item.highlight) FontWeight.Bold else FontWeight.SemiBold,
            color = item.accent,
        )
    }
}

/**
 * 漏斗解释行：把「测了 N 个却只剩 M 个」说清楚。
 *
 * 这一块是用户最容易困惑的地方 —— 没有它，2000 测数配 10 个可用看着像坏了。
 */
@Composable
fun TgGateSummary(tgStats: TgGateStats, modifier: Modifier = Modifier) {
    if (!tgStats.hasData) return
    val parts = buildList {
        if (tgStats.dcFail > 0) add("${tgStats.dcFail} 个连不上 DC")
        if (tgStats.latencyRejected > 0) add("${tgStats.latencyRejected} 个超门槛被剪枝")
        if (tgStats.webFail > 0) add("${tgStats.webFail} 个站点验证失败")
        if (tgStats.ok > 0) add("${tgStats.ok} 个两关通过")
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "TG 两关漏斗 · 本轮 ${tgStats.total} 个节点",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = parts.joinToString("，") + "。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (tgStats.latencyRejected > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "超门槛节点不会再跑第二关（第一关往返已超门槛，必然被剔除）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 分阶段耗时（对应桌面端右栏的时间轴）。
 *
 * 只展示「上轮」数据：这些值来自 `RefreshResult.Success.durations`，
 * 本轮进行中没有实时值。
 */
@Composable
fun StageTimeline(durations: StageDurations, modifier: Modifier = Modifier) {
    if (!durations.hasData) return
    val palette = dashboardAccents()
    val rows = listOf(
        Triple("抓取", durations.fetchMs, palette.blue),
        Triple("解析合并", durations.prepareMs, palette.gray),
        Triple("TCP 预筛", durations.tcpMs, palette.blue),
        Triple("TG 实测", durations.tgMs, palette.purple),
        Triple("汇总落盘", durations.commitMs, palette.green),
    )
    val total = durations.totalMs.coerceAtLeast(1)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "上轮阶段耗时 · ${formatDuration(durations.totalMs)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        rows.forEach { (label, ms, color) ->
            StageRow(label, ms, ms.toFloat() / total, color)
        }
    }
}

@Composable
private fun StageRow(label: String, ms: Long, fraction: Float, color: Color) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatDuration(ms)} · ${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 强调色板
// ---------------------------------------------------------------------------

/**
 * 强调色随主题切换。
 *
 * 浅色下用 600 档（够深才压得住白底），深色下换 400 档 —— 同一个 `#16A34A`
 * 放在深藏青底上对比度只有 2.4:1，数字会糊成一团。
 *
 * 判据取自 `MaterialTheme.colorScheme.background` 的亮度，而不是
 * `isSystemInDarkTheme()`：应用内的 `ThemeMode` 可以强制反着来（系统深色 +
 * 用户选浅色），只有读实际生效的配色才知道眼前是哪种。
 */
private data class DashboardAccents(
    val green: Color,
    val amber: Color,
    val red: Color,
    val blue: Color,
    val purple: Color,
    val gray: Color,
)

@Composable
private fun dashboardAccents(): DashboardAccents {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        DashboardAccents(
            green = Color(0xFF4ADE80),
            amber = Color(0xFFFBBF24),
            red = Color(0xFFF87171),
            blue = Color(0xFF60A5FA),
            purple = Color(0xFFA78BFA),
            gray = Color(0xFF9CA3AF),
        )
    } else {
        DashboardAccents(
            green = Color(0xFF16A34A),
            amber = Color(0xFFD97706),
            red = Color(0xFFDC2626),
            blue = Color(0xFF2563EB),
            purple = Color(0xFF7C3AED),
            gray = Color(0xFF6B7280),
        )
    }
}

// ---------------------------------------------------------------------------
// 预览
//
// 这些数字取自 2026-10-28 桌面基线的一轮真实分布（量级一致，非真实抓取结果），
// 用来在 Android Studio 里检查折行、间距、深浅色对比，不参与任何运行逻辑。
// ---------------------------------------------------------------------------

private val PreviewStats = Stats(
    available = 11,
    unverified = 0,
    tgFailed = 260,
    overThreshold = 75,
    dropped = 1200,
    excluded = 0,
    blacklisted = 40,
    tested = 1546,
)

private val PreviewTgStats = TgGateStats(
    dcFail = 1200,
    latencyRejected = 75,
    webFail = 260,
    ok = 11,
)

private val PreviewDurations = StageDurations(
    fetchMs = 16_600,
    prepareMs = 900,
    tcpMs = 8_400,
    tgMs = 27_600,
    commitMs = 200,
    totalMs = 53_700,
)

/**
 * 与 MainScreen 一致的组合方式：外层 16dp 水平内边距（等价 `contentPadding`），
 * 三块之间 8dp 间距（等价 `Arrangement.spacedBy`）。
 */
@Composable
private fun DashboardBlocks(
    stats: Stats,
    tgStats: TgGateStats,
    durations: StageDurations,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricGrid(stats = stats, tgStats = tgStats)
        TgGateSummary(tgStats = tgStats)
        StageTimeline(durations = durations)
    }
}

@Preview(name = "仪表盘 · 浅色 · 手机 411dp", showBackground = true, widthDp = 411, heightDp = 780)
@Composable
private fun PreviewDashboardLight() {
    TgProxyTheme(themeMode = ThemeMode.LIGHT) {
        DashboardBlocks(PreviewStats, PreviewTgStats, PreviewDurations)
    }
}

@Preview(name = "仪表盘 · 深色 · 手机 411dp", showBackground = true, widthDp = 411, heightDp = 780)
@Composable
private fun PreviewDashboardDark() {
    TgProxyTheme(themeMode = ThemeMode.DARK) {
        DashboardBlocks(PreviewStats, PreviewTgStats, PreviewDurations)
    }
}

@Preview(name = "仪表盘 · 浅色 · 平板 800dp", showBackground = true, widthDp = 800, heightDp = 600)
@Composable
private fun PreviewDashboardTablet() {
    TgProxyTheme(themeMode = ThemeMode.LIGHT) {
        DashboardBlocks(PreviewStats, PreviewTgStats, PreviewDurations)
    }
}

/**
 * 冷启动：没有任何一轮数据。
 *
 * 这个预览专门用来盯「空卡片留下的间距」—— `TgGateSummary` 和 `StageTimeline`
 * 在无数据时都不发射节点，如果调用方还留着 item 槽位，`spacedBy` 会在中间
 * 留出看不见的空隙。
 */
@Preview(name = "仪表盘 · 冷启动（无数据）", showBackground = true, widthDp = 411, heightDp = 420)
@Composable
private fun PreviewDashboardColdStart() {
    TgProxyTheme(themeMode = ThemeMode.LIGHT) {
        DashboardBlocks(Stats(), TgGateStats(), StageDurations())
    }
}
