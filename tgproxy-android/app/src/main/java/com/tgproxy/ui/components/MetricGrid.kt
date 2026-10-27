package com.tgproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tgproxy.model.StageDurations
import com.tgproxy.model.Stats
import com.tgproxy.model.TgGateStats

/**
 * 仪表盘指标网格（非惰性）。
 *
 * 为什么不用 `LazyVerticalGrid`：它放在 `LazyColumn` 的 item 里会抛
 * "Vertically scrollable component was measured with an infinity maximum height"
 * —— 嵌套滚动容器的经典坑。仪表盘只有 6 个指标，普通 Row 分块足够，
 * 也省掉 IntrinsicSize 那堆麻烦。
 *
 * 列数按可用宽度切：手机 2 列，平板（≥480dp）3 列。
 */
@Composable
fun MetricGrid(
    stats: Stats,
    tgStats: TgGateStats,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        MetricItem("可用节点", stats.available, AccentGreen, highlight = true),
        MetricItem("超门槛剔除", tgStats.latencyRejected, AccentAmber),
        MetricItem("DC 连接失败", tgStats.dcFail, AccentRed),
        MetricItem("站点验证失败", tgStats.webFail, AccentRed),
        MetricItem("TCP 不通", stats.dropped, AccentGray),
        MetricItem("已拉黑", stats.blacklisted, AccentGray),
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
    if (tgStats.total == 0) return
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
    if (durations.totalMs <= 0) return
    val rows = listOf(
        Triple("抓取", durations.fetchMs, AccentBlue),
        Triple("解析合并", durations.prepareMs, AccentGray),
        Triple("TCP 预筛", durations.tcpMs, AccentBlue),
        Triple("TG 实测", durations.tgMs, AccentPurple),
        Triple("汇总落盘", durations.commitMs, AccentGreen),
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

private val AccentGreen = Color(0xFF16A34A)
private val AccentAmber = Color(0xFFD97706)
private val AccentRed = Color(0xFFDC2626)
private val AccentBlue = Color(0xFF2563EB)
private val AccentPurple = Color(0xFF7C3AED)
private val AccentGray = Color(0xFF6B7280)
