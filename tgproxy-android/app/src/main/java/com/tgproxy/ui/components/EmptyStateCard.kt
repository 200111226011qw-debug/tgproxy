package com.tgproxy.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.Constants
import com.tgproxy.model.Stats
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 空列表解释卡（v2.1.0：0 节点时不再只说「暂无」，把本轮结果摊开讲清楚——
 * 是真的测了、还是节点太慢，让用户知道工具在工作）。
 */
@Composable
fun EmptyStateCard(
    stats: Stats,
    fetching: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (fetching) "正在测试，稍候…" else "当前没有 ≤${Constants.TG_LATENCY_LIMIT_MS}ms 的可用节点",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (stats.tested > 0 || stats.blacklisted > 0) {
                Text(
                    text = buildString {
                        append("本轮已测试 ${stats.tested} 个节点：\n")
                        append("· TCP 预筛通过 ${stats.tested - stats.dropped} 个\n")
                        append("· TG 两关通过 ${stats.available + stats.overThreshold} 个")
                        append("（其中 ${stats.available} 个 ≤${Constants.TG_LATENCY_LIMIT_MS}ms，${stats.overThreshold} 个超门槛被剔除）\n")
                        append("· TG 两关未过 ${stats.tgFailed} 个")
                        if (stats.blacklisted > 0) {
                            append("\n· 连续失败拉黑跳过 ${stats.blacklisted} 个（48 小时后自动解封重测）")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!fetching) {
                    Text(
                        text = if (stats.overThreshold > 0) {
                            "有 ${stats.overThreshold} 个节点能用但延迟超过 ${Constants.TG_LATENCY_LIMIT_MS}ms（宁缺毋滥，不展示）。免费池每轮都在变，稍后下拉再试。"
                        } else {
                            "本轮免费池没有节点能通过 Telegram 两关实测。免费池质量波动大，稍后下拉再试。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Text(
                    text = "点右下角「刷新」开始抓取并实测代理（TCP 预筛 → TG 两关 → <${Constants.TG_LATENCY_LIMIT_MS}ms 门槛），全程约 2~7 分钟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun EmptyStatePreview() {
    TgProxyTheme {
        Column(modifier = Modifier.padding(12.dp)) {
            EmptyStateCard(stats = Stats(tested = 0), fetching = false)
            Spacer(modifier = Modifier.height(12.dp))
            EmptyStateCard(
                stats = Stats(
                    available = 0,
                    tgFailed = 38,
                    overThreshold = 6,
                    dropped = 1100,
                    excluded = 130,
                    tested = 1250,
                ),
                fetching = false,
            )
        }
    }
}
