package com.tgproxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgproxy.ui.theme.SuccessGreen
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 倒计时文案格式（ARCHITECTURE.md §3.5 / §8）：
 * `<0` → `--`；`>=60` → `X 分 Y 秒`；否则 `Y 秒`。
 */
fun formatCountdown(totalSec: Int): String = when {
    totalSec < 0 -> "--"
    totalSec >= 60 -> "${totalSec / 60} 分 ${totalSec % 60} 秒"
    else -> "$totalSec 秒"
}

/**
 * 控制区（对应桌面版 .toolbar）：刷新按钮（loading 态）+ 间隔输入/保存 + 保存结果提示 + 倒计时。
 *
 * @param fetching 刷新中（按钮禁用 + 文案「刷新中…」+ 按钮内 CircularProgressIndicator）
 * @param intervalText 间隔输入框当前文本
 * @param onIntervalTextChange 输入框变更回调
 * @param onRefresh 刷新回调
 * @param onSaveInterval 保存回调
 * @param settingsMessage 保存结果提示（null 不显示）
 * @param settingsMessageIsError 保存提示是否为错误态（红/绿）
 * @param countdownSec 距下次自动刷新秒数
 * @param countdownStarted false 时倒计时显示「--」
 */
@Composable
fun ControlBar(
    fetching: Boolean,
    intervalText: String,
    onIntervalTextChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSaveInterval: () -> Unit,
    settingsMessage: String?,
    settingsMessageIsError: Boolean,
    countdownSec: Int,
    countdownStarted: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一行：刷新按钮 + 下次自动更新倒计时
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRefresh, enabled = !fetching) {
                    if (fetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "刷新中…")
                    } else {
                        Text(text = "刷新")
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "下次自动更新：" +
                        if (countdownStarted) formatCountdown(countdownSec) else "--",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 第二行：间隔输入 + 保存
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "自动更新间隔（分钟）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = onIntervalTextChange,
                    modifier = Modifier.width(88.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onSaveInterval) {
                    Text(text = "保存")
                }
            }

            // 第三行：保存结果提示（成功绿 / 失败红，不自动清除）
            if (settingsMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = settingsMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (settingsMessageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        SuccessGreen
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun ControlBarPreview() {
    TgProxyTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ControlBar(
                fetching = false,
                intervalText = "10",
                onIntervalTextChange = {},
                onRefresh = {},
                onSaveInterval = {},
                settingsMessage = "已保存，立即生效",
                settingsMessageIsError = false,
                countdownSec = 605,
                countdownStarted = true,
            )
            ControlBar(
                fetching = true,
                intervalText = "5",
                onIntervalTextChange = {},
                onRefresh = {},
                onSaveInterval = {},
                settingsMessage = "请输入 1~1440 之间的整数",
                settingsMessageIsError = true,
                countdownSec = 42,
                countdownStarted = true,
            )
            ControlBar(
                fetching = false,
                intervalText = "10",
                onIntervalTextChange = {},
                onRefresh = {},
                onSaveInterval = {},
                settingsMessage = null,
                settingsMessageIsError = false,
                countdownSec = 0,
                countdownStarted = false,
            )
        }
    }
}
