package com.tgproxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 日志页 —— **当前是占位页，没有任何真实数据源**。
 *
 * 这一页现在只能说「还没有」。之所以先占位而不是干脆不放进 Tab：
 * 三页签结构一旦定下来，后面补日志不需要再动导航层。
 *
 * 但要清楚代价：一个永远显示「尚未接入」的页签，和桌面端那些「点了没反应」
 * 的僵尸功能是同一种东西，用户点两次就不会再点。所以这里把话说清楚 ——
 * 现在该看的信息在仪表盘，别让人以为是自己没找到入口。
 *
 * 真正要做日志，前置是「轮次记录」落地（每轮一行：时间、结果、各阶段耗时、
 * 失败原因），桌面端的 `rounds.jsonl` 就是这个形态。在那之前这一页没有内容可画。
 */
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
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
            text = "日志尚未接入",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "本轮的失败原因与分阶段耗时在「仪表盘」页。\n" +
                "按轮次留存的运行日志需要先做轮次记录，尚未开始。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
