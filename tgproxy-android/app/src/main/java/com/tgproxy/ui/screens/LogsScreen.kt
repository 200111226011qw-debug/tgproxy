package com.tgproxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.RoundRecord
import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.components.formatDuration
import com.tgproxy.ui.preview.PreviewLogs
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 日志页：按时间倒序的轮次卡片流。
 *
 * 每张卡折叠时只显示「时间 / 成败 / 耗时 / 一行摘要」，点开才摊开统计与漏斗 ——
 * 50 轮全摊开会很长，而多数时候用户只想找「哪几轮失败了」。
 *
 * 列表 key 用 [RoundRecord.seq] 而不是 timestamp：同一秒内可能跑完两轮
 * （全源立即失败时一轮不到 1 秒），`LazyColumn` 撞 key 会直接抛异常。
 */
@Composable
fun LogsScreen(
    logs: List<RoundRecord>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    if (logs.isEmpty()) {
        EmptyLogs(modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 存储按写入顺序（旧 → 新），界面倒序：最新一轮在最上面
        items(items = logs.asReversed(), key = { it.seq }) { record ->
            RoundCard(record)
        }
    }
}

@Composable
private fun RoundCard(record: RoundRecord) {
    // 折叠态存在卡片内部：有了稳定的 key，展开状态会跟着这一轮走，
    // 新记录插到顶部时不会错位到别的卡上
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(record.elapsedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(success = record.success)
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = summaryOf(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        val note = record.error
        if (note != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = if (record.success) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            DetailRow("候选 / 实测", "${record.stats.tested} 个测过 · ${record.stats.available} 个可用")
            DetailRow(
                "TCP 不通 / 已拉黑",
                "${record.stats.dropped} 个不通 · ${record.stats.blacklisted} 个拉黑",
            )
            DetailRow(
                "TG 两关",
                "DC 失败 ${record.tgStats.dcFail} · 超门槛 ${record.tgStats.latencyRejected} · " +
                    "站点失败 ${record.tgStats.webFail} · 通过 ${record.tgStats.ok}",
            )
            if (record.durations.totalMs > 0) {
                DetailRow(
                    "阶段耗时",
                    "抓取 ${formatDuration(record.durations.fetchMs)} · " +
                        "TCP ${formatDuration(record.durations.tcpMs)} · " +
                        "TG ${formatDuration(record.durations.tgMs)}",
                )
            } else {
                DetailRow("阶段耗时", "本轮未跑完，无分段数据")
            }
        }
    }
}

@Composable
private fun StatusBadge(success: Boolean) {
    val color = if (success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    Text(
        text = if (success) "成功" else "失败",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 折叠态的一行摘要：失败轮没有统计，别画成「测了 0 个」。 */
private fun summaryOf(record: RoundRecord): String = if (record.success) {
    "可用 ${record.stats.available} · 实测 ${record.stats.tested}"
} else {
    "本轮未完成"
}

@Composable
private fun EmptyLogs(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = "还没有轮次记录",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "每跑完一轮刷新就会记一条：成败、耗时、两关漏斗。\n" +
                "最多保留最近 50 轮。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 预览
// ---------------------------------------------------------------------------

@Preview(name = "日志 · 三轮", showBackground = true, widthDp = 411, heightDp = 640)
@Composable
private fun LogsScreenPreview() {
    TgProxyTheme(themeMode = ThemeMode.LIGHT) {
        LogsScreen(logs = PreviewLogs, listState = rememberLazyListState())
    }
}

@Preview(name = "日志 · 空", showBackground = true, widthDp = 411, heightDp = 420)
@Composable
private fun LogsScreenEmptyPreview() {
    TgProxyTheme(themeMode = ThemeMode.LIGHT) {
        LogsScreen(logs = emptyList(), listState = rememberLazyListState())
    }
}
