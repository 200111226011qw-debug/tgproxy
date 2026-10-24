package com.tgproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.Stats
import com.tgproxy.ui.theme.LatencyNone
import com.tgproxy.ui.theme.Primary
import com.tgproxy.ui.theme.SuccessGreen
import com.tgproxy.ui.theme.TgProxyTheme
import com.tgproxy.ui.theme.WarningOrange

/**
 * 统计卡区（对应桌面版 .stats-grid）：手机竖屏 2×2，宽屏（≥600dp）4 列。
 *
 * 卡片左边条颜色与桌面版 .stat-card 一致：已保留 主色 / 可用 绿 / 不可用 灰 / 已排除 橙。
 */
@Composable
fun StatsGrid(stats: Stats, modifier: Modifier = Modifier) {
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 2
    val cards = listOf(
        StatsCardData("可用", stats.available, SuccessGreen),
        StatsCardData("不通已剔除", stats.dropped, WarningOrange),
        StatsCardData("已排除", stats.excluded, LatencyNone),
        StatsCardData("本轮测试", stats.tested, Primary),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.chunked(columns).forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowCards.forEach { card ->
                    StatsCard(
                        label = card.label,
                        value = card.value,
                        accent = card.accent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 统计卡数据。 */
private data class StatsCardData(val label: String, val value: Int, val accent: Color)

/** 单张统计卡：左侧 4dp 彩色边条 + 标签 + 数值（对应桌面版 .stat-card）。 */
@Composable
private fun StatsCard(
    label: String,
    value: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun StatsGridPhonePreview() {
    TgProxyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            StatsGrid(
                stats = Stats(available = 5, dropped = 7, excluded = 42, tested = 12),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 700)
@Composable
private fun StatsGridWidePreview() {
    TgProxyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            StatsGrid(
                stats = Stats(available = 5, dropped = 7, excluded = 42, tested = 12),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
