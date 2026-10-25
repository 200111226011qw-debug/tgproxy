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
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.ui.theme.MonoLinkStyle
import com.tgproxy.ui.theme.Primary
import com.tgproxy.ui.theme.SuccessGreen
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 代理列表区（对应桌面版 table-wrapper）：下拉刷新 + LazyColumn 卡片行 + 空态 + loading 遮罩。
 *
 * - 刷新中显示遮罩「正在抓取并测试代理…」（与桌面版 loading-overlay 一致）；
 * - 下拉刷新与按钮刷新共用 ViewModel 的互斥（Mutex.tryLock），刷新中再次触发直接忽略；
 * - 行点击 SOCKS5 复制 / MTProto 跳转（由调用方处理）；长按弹出「复制/分享」菜单；
 * - 不可用行整体降透明度 0.6（桌面版灰徽章的等价表达）。
 *
 * @param proxies 已排序列表
 * @param fetching 是否刷新中
 * @param copiedLink 当前处于「已复制」图标态的链接（1.5s 后由调用方清除）
 * @param onRefresh 下拉刷新回调
 * @param onRowClick 行点击回调
 * @param onCopy 长按菜单「复制链接」回调
 * @param onShare 长按菜单「分享链接」回调
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
        Box(modifier = Modifier.fillMaxSize()) {
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
                        )
                    }
                }
            }
            if (fetching) {
                LoadingOverlay()
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
 * 代理卡片行（对应桌面版表格 7 列的手机化重构，PRD 5.1）。
 *
 * 行结构：徽章 + 链接（等宽）→ 延迟（着色）→ 地区 · ISP → 入库时间。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProxyRow(
    entry: ProxyEntry,
    copied: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = entry.status == ProxyStatus.UNAVAILABLE
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (unavailable) 0.6f else 1f),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // 第一行：状态徽章 + 类型徽章 + 链接（等宽字体，可复制）
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status = entry.status)
                Spacer(modifier = Modifier.width(6.dp))
                TypeBadge(type = entry.type)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = entry.link,
                    style = MonoLinkStyle,
                    color = if (copied) SuccessGreen else Primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (copied) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // 第二行：延迟（<200 绿 / <600 橙 / ≥600 红 / 失败 灰）
            LatencyText(latencyMs = entry.latencyMs)

            // 第三行：地区 · ISP（均有值才展示，避免空行占位）
            val meta = when {
                entry.location.isNotEmpty() && entry.isp.isNotEmpty() ->
                    "${entry.location} · ${entry.isp}"
                entry.location.isNotEmpty() -> entry.location
                entry.isp.isNotEmpty() -> entry.isp
                else -> ""
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // 第四行：入库时间（次要色）
            if (entry.addedAt.isNotEmpty()) {
                Text(
                    text = "入库时间：${entry.addedAt}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 空态文案（与桌面版一致）。 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "暂无数据，请点击「刷新」或等待自动更新",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** loading 遮罩：「正在抓取并测试代理…」（与桌面版 loading-overlay 一致）。 */
@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "正在抓取并测试代理…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
                        latencyMs = 120,
                        status = ProxyStatus.AVAILABLE,
                        location = "荷兰 北荷兰省 阿姆斯特丹",
                        isp = "DigitalOcean",
                        addedAt = "10-21 22:58",
                    ),
                    ProxyEntry(
                        link = "tg://proxy?server=5.6.7.8&port=443&secret=abcdef",
                        host = "5.6.7.8",
                        port = 443,
                        type = ProxyType.MTPROTO,
                        latencyMs = null,
                        status = ProxyStatus.UNAVAILABLE,
                        location = "日本",
                        isp = "IIJ",
                        addedAt = "10-21 23:10",
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

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun ProxyRowPreview() {
    TgProxyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.padding(16.dp)) {
                ProxyRow(
                    entry = ProxyEntry(
                        link = "socks5://1.2.3.4:1080",
                        host = "1.2.3.4",
                        port = 1080,
                        type = ProxyType.SOCKS5,
                        latencyMs = 350,
                        status = ProxyStatus.AVAILABLE,
                        location = "香港",
                        isp = "HGC",
                        addedAt = "10-21 22:58",
                    ),
                    copied = true,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }
    }
}
