package com.tgproxy.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.model.TgVerdict
import com.tgproxy.ui.theme.MonoLinkStyle
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 代理列表区（v2.0 重做）：下拉刷新 + LazyColumn 卡片行 + 空态。
 *
 * - 下拉刷新与按钮刷新共用 ViewModel 的互斥（Mutex.tryLock），刷新中再次触发直接忽略；
 * - 行点击唤起 Telegram「添加代理」（由调用方处理）；长按弹出「复制/分享」菜单；
 * - 列表只含 TG 实测通过且 ≤1000ms 的节点；known_good 启动展示的旧条目未经验证（降透明度）。
 *
 * @param proxies 已排序列表
 * @param fetching 是否刷新中（下拉指示器）
 * @param copiedLink 当前处于「已复制」图标态的链接（1.5s 后由调用方清除）
 * @param onRefresh 下拉刷新回调
 * @param onRowClick 行点击回调
 * @param onCopy 复制回调（行内按钮 + 长按菜单）
 * @param onShare 分享回调（行内按钮 + 长按菜单）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyListSection(
    proxies: List<ProxyEntry>,
    fetching: Boolean,
    copiedLink: String?,
    onRefresh: () -> Unit,
    onRowClick: (ProxyEntry) -> Unit,
    onCopy: (ProxyEntry) -> Unit,
    onShare: (ProxyEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuEntry by remember { mutableStateOf<ProxyEntry?>(null) }

    PullToRefreshBox(
        isRefreshing = fetching,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        if (proxies.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = proxies,
                    key = { it.link },
                    contentType = { it.type },
                ) { entry ->
                    ProxyRow(
                        entry = entry,
                        copied = copiedLink == entry.link,
                        onClick = { onRowClick(entry) },
                        onLongClick = { menuEntry = entry },
                        onCopy = { onCopy(entry) },
                        onShare = { onShare(entry) },
                    )
                }
            }
        }
    }

    val entry = menuEntry
    if (entry != null) {
        AlertDialog(
            onDismissRequest = { menuEntry = null },
            title = {
                Text(
                    text = entry.link,
                    style = MonoLinkStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        menuEntry = null
                        onCopy(entry)
                    },
                ) {
                    Text("复制链接")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        menuEntry = null
                        onShare(entry)
                    },
                ) {
                    Text("分享链接")
                }
            },
        )
    }
}

/**
 * 代理卡片行（v2.0 重做）。
 *
 * 行结构：左侧 host:port（等宽粗体主标题）+ 元信息行（类型 · 地区 · TG 实测），
 * 右侧延迟胶囊（着色档位见 [latencyColor]）+ 复制/分享小按钮；
 * 整卡点击唤起 Telegram，长按弹菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProxyRow(
    entry: ProxyEntry,
    copied: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // known_good 启动展示的旧条目未经过本轮 TG 实测（PASS 之外的都降透明度）
    val unverified = entry.tgVerdict != TgVerdict.PASS
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (unverified) 0.6f else 1f),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 第一行：host:port（等宽粗体）+ 复制成功的勾
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
                    if (copied) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(top = 2.dp))
                // 第二行：类型 · 地区 · 验证状态
                Text(
                    text = rowMetaText(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 右侧：延迟胶囊
            LatencyPill(latencyMs = entry.latencyMs)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "复制链接",
                modifier = Modifier
                    .size(30.dp)
                    .combinedClickable(onClick = onCopy, onLongClick = onCopy)
                    .padding(5.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = "分享链接",
                modifier = Modifier
                    .size(30.dp)
                    .combinedClickable(onClick = onShare, onLongClick = onShare)
                    .padding(5.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 元信息行文本：类型 · 地区 · ISP / 验证状态。 */
private fun rowMetaText(entry: ProxyEntry): String {
    val parts = mutableListOf(entry.type.name)
    if (entry.location.isNotEmpty()) parts.add(entry.location)
    if (entry.isp.isNotEmpty()) parts.add(entry.isp)
    return parts.joinToString(" · ")
}

/** 延迟胶囊：着色档位与 [latencyColor] 一致；无延迟显示「未测」。 */
@Composable
private fun LatencyPill(latencyMs: Int?, modifier: Modifier = Modifier) {
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 空态文案（v2.0：给出下一步动作）。 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "暂无可用节点",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.padding(top = 4.dp))
            Text(
                text = "点「刷新」抓取并实测代理（TCP 预筛 → TG 两关 → <1000ms 门槛）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun ProxyListPreview() {
    TgProxyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProxyListSection(
                proxies = listOf(
                    ProxyEntry(
                        link = "socks5://1.2.3.4:1080",
                        host = "1.2.3.4",
                        port = 1080,
                        type = ProxyType.SOCKS5,
                        latencyMs = 387,
                        status = ProxyStatus.AVAILABLE,
                        tgVerdict = TgVerdict.PASS,
                        location = "荷兰 北荷兰省 阿姆斯特丹",
                        isp = "DigitalOcean",
                    ),
                    ProxyEntry(
                        link = "socks5://5.6.7.8:1080",
                        host = "5.6.7.8",
                        port = 1080,
                        type = ProxyType.SOCKS5,
                        latencyMs = 812,
                        status = ProxyStatus.AVAILABLE,
                        tgVerdict = TgVerdict.PASS,
                        location = "日本",
                        isp = "IIJ",
                    ),
                ),
                fetching = false,
                copiedLink = null,
                onRefresh = {},
                onRowClick = {},
                onCopy = {},
                onShare = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
