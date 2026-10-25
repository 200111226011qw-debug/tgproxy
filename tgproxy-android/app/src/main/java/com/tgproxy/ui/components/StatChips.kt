package com.tgproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.Stats
import com.tgproxy.ui.theme.LatencyGreen
import com.tgproxy.ui.theme.LatencyNone
import com.tgproxy.ui.theme.WarningOrange
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 五项统计胶囊条（桌面版统计卡的移动化：横滑一行，颜色区分口径）。
 *
 * 口径（available+unverified+tgFailed+dropped=tested，见 [Stats]）：
 * 验证保留 绿 / 未验证 灰 / TG 未过 橙 / TCP 不通 灰 / 已排除 深灰。
 */
@Composable
fun StatChips(stats: Stats, modifier: Modifier = Modifier) {
    val chips = listOf(
        ChipData("验证保留", stats.available, LatencyGreen),
        ChipData("未验证", stats.unverified, LatencyNone),
        ChipData("TG 未过", stats.tgFailed, WarningOrange),
        ChipData("TCP 不通", stats.dropped, LatencyNone),
        ChipData("已排除", stats.excluded, MaterialTheme.colorScheme.primary),
    )
    Row(
        modifier = modifier
            .fillMaxHeight()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { index, chip ->
            if (index > 0) Box(modifier = Modifier.width(8.dp))
            StatChip(label = chip.label, value = chip.value, accent = chip.accent)
        }
    }
}

/** 统计胶囊数据。 */
private data class ChipData(val label: String, val value: Int, val accent: Color)

/** 单枚胶囊：数字（强调色）+ 标签。 */
@Composable
private fun StatChip(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun StatChipsPreview() {
    TgProxyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            StatChips(
                stats = Stats(
                    available = 15,
                    unverified = 2,
                    tgFailed = 40,
                    dropped = 900,
                    excluded = 120,
                    tested = 957,
                ),
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
