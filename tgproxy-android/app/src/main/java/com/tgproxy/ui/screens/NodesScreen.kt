package com.tgproxy.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tgproxy.Constants
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.Stats
import com.tgproxy.ui.buildShareIntent
import com.tgproxy.ui.buildTelegramViewIntent
import com.tgproxy.ui.components.EmptyStateCard
import com.tgproxy.ui.components.ProxyActionDialog
import com.tgproxy.ui.components.ProxyRowCard
import com.tgproxy.ui.toTelegramProxyUrl
import kotlinx.coroutines.delay

/** 「已复制」图标态闪现记录（id 保证相同链接重复复制也能重置计时）。 */
private data class CopyFlash(val link: String, val id: Long)

/**
 * 节点列表页：下拉刷新 + 节点卡片列表 + 空态解释 + 长按菜单。
 *
 * 复制/唤起/分享这些动作都是纯 UI 行为，就地实现；只有需要跨页提示的文案
 * 通过 [onMessage] 抛给外层的 Snackbar —— 列表页不持有 Scaffold。
 *
 * @param onMessage 需要弹 Snackbar 的提示文案
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    proxies: List<ProxyEntry>,
    stats: Stats,
    fetching: Boolean,
    onRefresh: () -> Unit,
    onMessage: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copyFlash by remember { mutableStateOf<CopyFlash?>(null) }
    var menuEntry by remember { mutableStateOf<ProxyEntry?>(null) }

    // 复制图标态 1.5s 后自动清除（等价桌面版 1500ms）
    LaunchedEffect(copyFlash) {
        if (copyFlash != null) {
            delay(Constants.COPY_FEEDBACK_MS)
            copyFlash = null
        }
    }

    val copyLink: (ProxyEntry, Boolean) -> Unit = { entry, notify ->
        clipboard.setText(AnnotatedString(entry.link))
        copyFlash = CopyFlash(entry.link, (copyFlash?.id ?: 0L) + 1L)
        if (notify) onMessage("已复制")
    }

    // 使用节点：唤起 Telegram 直接弹出「添加代理」窗口（不经过网页；未安装则降级复制）
    val onUse: (ProxyEntry) -> Unit = { entry ->
        try {
            context.startActivity(buildTelegramViewIntent(toTelegramProxyUrl(entry)))
        } catch (e: ActivityNotFoundException) {
            copyLink(entry, false)
            onMessage("未安装 Telegram，已复制链接")
        }
    }

    val onShare: (ProxyEntry) -> Unit = { entry ->
        try {
            context.startActivity(buildShareIntent(entry.link))
        } catch (e: ActivityNotFoundException) {
            onMessage("分享不可用")
        }
    }

    PullToRefreshBox(
        modifier = modifier.fillMaxSize(),
        isRefreshing = fetching,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 底部留出 FAB 的高度，否则最后一条会被悬浮按钮压住
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (proxies.isEmpty()) {
                item {
                    EmptyStateCard(stats = stats, fetching = fetching)
                }
            } else {
                items(
                    items = proxies,
                    key = { it.link },
                    contentType = { it.type },
                ) { entry ->
                    ProxyRowCard(
                        entry = entry,
                        copied = copyFlash?.link == entry.link,
                        onUse = { onUse(entry) },
                        onLongClick = { menuEntry = entry },
                        onCopy = { copyLink(entry, true) },
                        onShare = { onShare(entry) },
                    )
                }
            }

            item {
                Text(
                    text = "TG 两关实测 · 仅保留 ≤${Constants.TG_LATENCY_LIMIT_MS}ms · 点「使用」一键连 Telegram",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    // 长按菜单
    ProxyActionDialog(
        entry = menuEntry,
        onDismiss = { menuEntry = null },
        onCopy = { copyLink(it, true) },
        onShare = onShare,
    )
}
