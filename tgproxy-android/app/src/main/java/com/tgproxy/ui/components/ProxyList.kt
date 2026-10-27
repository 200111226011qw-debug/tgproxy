package com.tgproxy.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.model.TgVerdict
import com.tgproxy.ui.theme.MonoLinkStyle
import com.tgproxy.ui.theme.TgBlue
import com.tgproxy.ui.theme.TgProxyTheme

/** 「使用」按钮渐变（TG 蓝 → 青，与 Hero 大卡同源）。 */
private val UseButtonGradient = Brush.linearGradient(listOf(TgBlue, Color(0xFF00C2E0)))

/**
 * 代理卡片行（v2.1.0 列表优先 + 仪表盘视觉）。
 *
 * 结构：左侧 4dp 延迟色条（扫视即知状态）→ 主体（host:port 等宽粗体 +
 * 类型 · 地区 · ISP 元信息）→ 延迟胶囊 + 渐变「使用」主按钮（tg:// 深链，
 * 48dp 触控）+ 圆底复制按钮。
 *
 * - 整卡点击 = 使用（唤起 Telegram「添加代理」）；长按 = 复制/分享菜单；
 * - known_good 先行展示的旧条目（未复测）降透明度 + 延迟胶囊显示「未测」；
 * - 「使用」按钮与整卡点击同入口；复制按钮点击不透传到卡片（自带拦截）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProxyRowCard(
    entry: ProxyEntry,
    copied: Boolean,
    onUse: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // known_good 启动展示的旧条目未经过本轮 TG 实测（PASS 之外的都降透明度）
    val unverified = entry.tgVerdict != TgVerdict.PASS
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (unverified) 0.6f else 1f),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onUse, onLongClick = onLongClick)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧延迟色条（<1000 绿 / <3000 橙 / 其余红 / 未测 灰）
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(latencyColor(entry.latencyMs)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${entry.host}:${entry.port}",
                        style = MonoLinkStyle.copy(
                            fontSize = MaterialTheme.typography.titleMedium.fontSize,
                        ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (unverified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "未复测",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = rowMetaText(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LatencyPill(latencyMs = entry.latencyMs)
            // 「使用」主按钮：TG 蓝 → 青渐变填充，48dp 触控区
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(width = 68.dp, height = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(UseButtonGradient)
                    .clickable(onClick = onUse),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (copied) "已复制" else "使用",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            // 圆底复制按钮（次要操作，40dp）
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onCopy),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制链接",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 元信息行文本：类型 · 地区 · ISP。 */
internal fun rowMetaText(entry: ProxyEntry): String {
    val parts = mutableListOf(entry.type.name)
    if (entry.location.isNotEmpty()) parts.add(entry.location)
    if (entry.isp.isNotEmpty()) parts.add(entry.isp)
    return parts.joinToString(" · ")
}

/** 延迟胶囊：着色档位与 [latencyColor] 一致；无延迟显示「未测」。数字等宽增强可信度。 */
@Composable
fun LatencyPill(latencyMs: Int?, modifier: Modifier = Modifier) {
    val color = latencyColor(latencyMs)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Text(
            text = latencyMs?.let { "$it ms" } ?: "未测",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 长按菜单（复制/分享）。由列表方持有状态并展示。 */
@Composable
fun ProxyActionDialog(
    entry: ProxyEntry?,
    onDismiss: () -> Unit,
    onCopy: (ProxyEntry) -> Unit,
    onShare: (ProxyEntry) -> Unit,
) {
    if (entry == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = entry.link,
                style = MonoLinkStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onCopy(entry) }) { Text("复制链接") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(); onShare(entry) }) { Text("分享链接") }
        },
    )
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun ProxyRowCardPreview() {
    TgProxyTheme {
        Column(modifier = Modifier.padding(12.dp)) {
            ProxyRowCard(
                entry = ProxyEntry(
                    link = "socks5://1.2.3.4:1080",
                    host = "1.2.3.4",
                    port = 1080,
                    type = ProxyType.SOCKS5,
                    latencyMs = 387,
                    status = ProxyStatus.AVAILABLE,
                    tgVerdict = TgVerdict.PASS,
                    location = "香港",
                    isp = "HGC",
                ),
                copied = false,
                onUse = {},
                onLongClick = {},
                onCopy = {},
                onShare = {},
            )
            Spacer(modifier = Modifier.height(8.dp))
            ProxyRowCard(
                entry = ProxyEntry(
                    link = "socks5://5.6.7.8:1080",
                    host = "5.6.7.8",
                    port = 1080,
                    type = ProxyType.SOCKS5,
                    latencyMs = null,
                    status = ProxyStatus.UNAVAILABLE,
                    tgVerdict = TgVerdict.UNVERIFIED,
                    location = "日本",
                ),
                copied = true,
                onUse = {},
                onLongClick = {},
                onCopy = {},
                onShare = {},
            )
        }
    }
}
