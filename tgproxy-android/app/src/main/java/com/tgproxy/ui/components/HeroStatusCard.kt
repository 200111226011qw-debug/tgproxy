package com.tgproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgproxy.model.RefreshProgress
import com.tgproxy.model.RefreshStage
import com.tgproxy.model.Stats
import com.tgproxy.ui.theme.AccentPink
import com.tgproxy.ui.theme.NavyTextSecondary
import com.tgproxy.ui.theme.TgBlue
import com.tgproxy.ui.theme.TgProxyTheme
import com.tgproxy.ui.theme.WarningOrange

/**
 * 倒计时文案格式（ARCHITECTURE.md §3.5 / §8）：
 * `<0` → `--`；`>=60` → `X 分 Y 秒`；否则 `Y 秒`。
 */
fun formatCountdown(totalSec: Int): String = when {
    totalSec < 0 -> "--"
    totalSec >= 60 -> "${totalSec / 60} 分 ${totalSec % 60} 秒"
    else -> "$totalSec 秒"
}

/** 毫秒耗时文案：<60s → `X 秒`；否则 `X 分 Y 秒`。 */
fun formatDuration(ms: Long): String {
    val totalSec = ms / 1_000
    return if (totalSec >= 60) "${totalSec / 60} 分 ${totalSec % 60} 秒" else "$totalSec 秒"
}

/** Hero 大卡渐变画刷（TG 蓝 → 仪表盘青）。 */
private val HeroGradient = Brush.linearGradient(listOf(TgBlue, Color(0xFF00C2E0)))

/**
 * 仪表盘 Hero 状态大卡（v2.1.0，参考「蓝色主信息大卡 + 大数字」风格）：
 *
 * 刷新中 = 阶段名 + done/total + 白色进度条；空闲 = 大数字「可用节点」+
 * 最优延迟/上轮耗时 + 下次更新倒计时。白字浮于蓝→青渐变之上。
 */
@Composable
fun HeroStatusCard(
    fetching: Boolean,
    progress: RefreshProgress?,
    lastDurationMs: Long?,
    stats: Stats,
    bestLatencyMs: Int?,
    countdownSec: Int,
    countdownStarted: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(HeroGradient, RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        if (fetching) {
            val label = progress?.label ?: "准备中"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                if (progress != null && progress.total > 0) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "${progress.done} / ${progress.total}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val fraction = if (progress != null && progress.total > 0) {
                (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
            } else {
                null
            }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "正在实测 Telegram 两关，完成后自动更新列表",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        text = "可用节点",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${stats.available}",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "个",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(bottom = 7.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                VerticalDivider(
                    modifier = Modifier.height(40.dp),
                    thickness = 1.dp,
                    color = Color.White.copy(alpha = 0.35f),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    HeroMiniStat(label = "最优延迟", value = bestLatencyMs?.let { "$it ms" } ?: "--")
                    Spacer(modifier = Modifier.height(6.dp))
                    HeroMiniStat(label = "上轮耗时", value = lastDurationMs?.let { formatDuration(it) } ?: "--")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = buildString {
                    append("上轮测试 ${stats.tested} 个")
                    if (countdownStarted) {
                        append(" · 下次更新 ${formatCountdown(countdownSec)}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/** Hero 卡右侧小字段：灰白小标签 + 白色粗体值。 */
@Composable
private fun HeroMiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/**
 * 五段统计卡（参考「灰标签 + 粗体数值」三段式信息卡）：
 * TG 未过 / 超门槛 / TCP 不通 / 已拉黑 / 已排除。
 */
@Composable
fun StatsCard(stats: Stats, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsSegment("TG 未过", stats.tgFailed, WarningOrange)
        StatsSegment("超门槛", stats.overThreshold, AccentPink)
        StatsSegment("TCP 不通", stats.dropped, MaterialTheme.colorScheme.onSurface)
        StatsSegment("已拉黑", stats.blacklisted, MaterialTheme.colorScheme.error)
        StatsSegment("已排除", stats.excluded, NavyTextSecondary)
    }
}

/** 单段：上灰标签下粗体数值。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.StatsSegment(label: String, value: Int, color: Color) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "$value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun HeroStatusCardPreview() {
    TgProxyTheme(themeMode = com.tgproxy.model.ThemeMode.DARK) {
        Column(modifier = Modifier.padding(16.dp)) {
            HeroStatusCard(
                fetching = false,
                progress = null,
                lastDurationMs = 400_000,
                stats = Stats(available = 3, tgFailed = 40, overThreshold = 8, dropped = 900, excluded = 120, tested = 960),
                bestLatencyMs = 929,
                countdownSec = 605,
                countdownStarted = true,
            )
            Spacer(modifier = Modifier.height(10.dp))
            HeroStatusCard(
                fetching = true,
                progress = RefreshProgress(RefreshStage.TG_TEST, 18, 40),
                lastDurationMs = null,
                stats = Stats(available = 0, tgFailed = 12, overThreshold = 3, dropped = 800, excluded = 120, tested = 960),
                bestLatencyMs = null,
                countdownSec = 605,
                countdownStarted = true,
            )
            Spacer(modifier = Modifier.height(10.dp))
            StatsCard(
                Stats(available = 3, tgFailed = 40, overThreshold = 8, dropped = 900, excluded = 120, tested = 960),
            )
        }
    }
}

/**
 * 三个阶段的进度形态（progress 拆成独立流之后，这张卡是唯一订阅者）。
 *
 * 盯三件事：阶段名是否跟着换、`total` 未知时是否退回不确定进度条、
 * done/total 在窄屏上会不会把阶段名挤掉。
 */
@Preview(name = "hero_three_stages", showBackground = true, widthDp = 380)
@Composable
private fun HeroStatusCardStagesPreview() {
    TgProxyTheme(themeMode = com.tgproxy.model.ThemeMode.DARK) {
        Column(modifier = Modifier.padding(16.dp)) {
            listOf(
                RefreshProgress(RefreshStage.FETCH, 3, 8),
                RefreshProgress(RefreshStage.TCP_TEST, 412, 2000),
                // total = 0：总量未知，只显示阶段名 + 不确定进度条
                RefreshProgress(RefreshStage.TG_TEST, 0, 0),
            ).forEach { progress ->
                HeroStatusCard(
                    fetching = true,
                    progress = progress,
                    lastDurationMs = null,
                    stats = Stats(),
                    bestLatencyMs = null,
                    countdownSec = -1,
                    countdownStarted = false,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}
