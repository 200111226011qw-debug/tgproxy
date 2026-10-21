package com.tgproxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.ui.theme.BadgeAvailableBg
import com.tgproxy.ui.theme.BadgeAvailableFg
import com.tgproxy.ui.theme.BadgeMtprotoBg
import com.tgproxy.ui.theme.BadgeMtprotoFg
import com.tgproxy.ui.theme.BadgeSocks5Bg
import com.tgproxy.ui.theme.BadgeSocks5Fg
import com.tgproxy.ui.theme.BadgeUnavailableBg
import com.tgproxy.ui.theme.BadgeUnavailableFg
import com.tgproxy.ui.theme.LatencyGreen
import com.tgproxy.ui.theme.LatencyNone
import com.tgproxy.ui.theme.LatencyOrange
import com.tgproxy.ui.theme.LatencyRed
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 状态徽章（可用/不可用），配色与桌面版 .badge-available / .badge-unavailable 一致。
 */
@Composable
fun StatusBadge(status: ProxyStatus, modifier: Modifier = Modifier) {
    when (status) {
        ProxyStatus.AVAILABLE -> Pill("可用", BadgeAvailableBg, BadgeAvailableFg, modifier)
        ProxyStatus.UNAVAILABLE -> Pill("不可用", BadgeUnavailableBg, BadgeUnavailableFg, modifier)
    }
}

/**
 * 类型徽章（SOCKS5/MTProto），配色与桌面版 .badge-socks5 / .badge-mtproto 一致。
 */
@Composable
fun TypeBadge(type: ProxyType, modifier: Modifier = Modifier) {
    when (type) {
        ProxyType.SOCKS5 -> Pill("SOCKS5", BadgeSocks5Bg, BadgeSocks5Fg, modifier)
        ProxyType.MTPROTO -> Pill("MTProto", BadgeMtprotoBg, BadgeMtprotoFg, modifier)
    }
}

/**
 * 延迟着色唯一来源（ARCHITECTURE.md §8）：
 * null 灰 #9CA3AF / <200 绿 #16A34A / <600 橙 #D97706 / ≥600 红 #DC2626。
 */
fun latencyColor(latencyMs: Int?): Color = when {
    latencyMs == null -> LatencyNone
    latencyMs < 200 -> LatencyGreen
    latencyMs < 600 -> LatencyOrange
    else -> LatencyRed
}

/**
 * 延迟文本：`X ms`；失败（null）显示「—」（与桌面版 formatLatency 一致）。
 */
@Composable
fun LatencyText(latencyMs: Int?, modifier: Modifier = Modifier) {
    Text(
        text = latencyMs?.let { "$it ms" } ?: "—",
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = latencyColor(latencyMs),
    )
}

/** 胶囊徽章（桌面版 .badge 的圆角 pill 形态）。 */
@Composable
private fun Pill(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = background,
        contentColor = foreground,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BadgesPreview() {
    TgProxyTheme {
        Surface {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(ProxyStatus.AVAILABLE)
                StatusBadge(ProxyStatus.UNAVAILABLE)
                TypeBadge(ProxyType.SOCKS5)
                TypeBadge(ProxyType.MTPROTO)
                LatencyText(latencyMs = 120)
                LatencyText(latencyMs = 350)
                LatencyText(latencyMs = 900)
                LatencyText(latencyMs = null)
            }
        }
    }
}
