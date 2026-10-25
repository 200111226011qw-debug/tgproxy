package com.tgproxy.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.RefreshProgress
import com.tgproxy.model.RefreshStage
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 刷新进度卡（v2.0 新增：刷新不再是无进度转圈）。
 *
 * 显示当前阶段（抓取数据源 / TCP 预筛 / TG 实测）+ done/total 计数 + 进度条；
 * total>0 时进度条有确定值，否则为不确定动画。
 */
@Composable
fun ProgressCard(progress: RefreshProgress, modifier: Modifier = Modifier) {
    val fraction = if (progress.total > 0) {
        (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
    } else {
        null
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = progress.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                if (progress.total > 0) {
                    Text(
                        text = "${progress.done} / ${progress.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.padding(top = 6.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun ProgressCardPreview() {
    TgProxyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(12.dp)) {
                ProgressCard(RefreshProgress(RefreshStage.FETCH, 2, 5))
                Spacer(modifier = Modifier.padding(6.dp))
                ProgressCard(RefreshProgress(RefreshStage.TCP_TEST, 1200, 3000))
                Spacer(modifier = Modifier.padding(6.dp))
                ProgressCard(RefreshProgress(RefreshStage.TG_TEST, 18, 40))
            }
        }
    }
}
