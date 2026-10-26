package com.tgproxy.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgproxy.model.Settings
import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.theme.SuccessGreen
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 设置面板内容（v2.1.0 收进底部弹层，列表让位首屏）：
 * 自动更新间隔 + known_good 沉淀开关 + 主题循环 + 数据源说明。
 */
@Composable
fun SettingsSheetContent(
    settings: Settings,
    intervalText: String,
    onIntervalTextChange: (String) -> Unit,
    onSaveInterval: () -> Unit,
    onKnownGoodChange: (Boolean) -> Unit,
    onThemeModeClick: () -> Unit,
    settingsMessage: String?,
    settingsMessageIsError: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // 自动更新间隔
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "自动更新间隔（分钟）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            Button(onClick = onSaveInterval, shape = RoundedCornerShape(10.dp)) {
                Text(text = "保存")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // known_good 沉淀开关
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "known_good 沉淀",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "上轮验证通过的节点优先复测，启动即展示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = settings.knownGoodEnabled,
                onCheckedChange = onKnownGoodChange,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 主题切换
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "外观",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "深色 / 浅色 / 跟随系统（点按循环）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onThemeModeClick) {
                Text(text = themeModeLabel(settings.themeMode))
            }
        }

        // 保存/开关结果提示（成功绿 / 失败红，不自动清除）
        if (settingsMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
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

        Spacer(modifier = Modifier.height(12.dp))

        // 数据源说明
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Text(
                text = "数据源：内置 5 个免费源（hideip.me / proxyscrape / monosans / TheSpeedX / watchttvv），" +
                    "直连失败自动走 jsDelivr 镜像；单源 >1000 条、合并 >3000 条随机抽样。\n" +
                    "测试口径：TCP 预筛 → TG 两关实测 → 仅保留延迟 ≤1000ms。\n" +
                    "加速：连续 3 轮不通的节点自动拉黑 48 小时，跳过测试节省筛选时间。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** 主题切换按钮文案。 */
internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "主题：自动"
    ThemeMode.LIGHT -> "主题：浅色"
    ThemeMode.DARK -> "主题：深色"
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun SettingsSheetPreview() {
    TgProxyTheme {
        SettingsSheetContent(
            settings = Settings(),
            intervalText = "10",
            onIntervalTextChange = {},
            onSaveInterval = {},
            onKnownGoodChange = {},
            onThemeModeClick = {},
            settingsMessage = null,
            settingsMessageIsError = false,
        )
    }
}
