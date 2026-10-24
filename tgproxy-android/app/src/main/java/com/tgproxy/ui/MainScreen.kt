package com.tgproxy.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgproxy.Constants
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyType
import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.components.ControlBar
import com.tgproxy.ui.components.ErrorBanner
import com.tgproxy.ui.components.ProxyListSection
import com.tgproxy.ui.components.StatsGrid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「已复制」图标态闪现记录（id 保证相同链接重复复制也能重置 1.5s 计时）。 */
private data class CopyFlash(val link: String, val id: Long)

/**
 * 主屏幕（单屏 Scaffold，对应桌面版 index.html 整体结构）。
 *
 * 自上而下：头部（标题/副标题/更新时间/主题切换）→ 统计卡区 → 错误条 → 控制区 →
 * 代理列表（下拉刷新，weight 1f）→ 页脚（数据来源）。
 */
@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var intervalText by remember(settings.intervalMinutes) {
        mutableStateOf(settings.intervalMinutes.toString())
    }
    var copyFlash by remember { mutableStateOf<CopyFlash?>(null) }

    // 复制图标态 1.5s 后自动清除（等价桌面版 1500ms）
    LaunchedEffect(copyFlash) {
        if (copyFlash != null) {
            delay(Constants.COPY_FEEDBACK_MS)
            copyFlash = null
        }
    }

    // 复制链接（可选 Snackbar 反馈）
    val copyLink: (ProxyEntry, Boolean) -> Unit = { entry, notify ->
        clipboard.setText(AnnotatedString(entry.link))
        copyFlash = CopyFlash(entry.link, (copyFlash?.id ?: 0L) + 1L)
        if (notify) {
            scope.launch { snackbarHostState.showSnackbar("已复制") }
        }
    }

    // 行点击：SOCKS5 复制；MTProto 跳转 Telegram（未安装则降级复制 + Snackbar）
    val onRowClick: (ProxyEntry) -> Unit = { entry ->
        if (entry.type == ProxyType.MTPROTO) {
            try {
                context.startActivity(buildTelegramViewIntent(entry.link))
            } catch (e: ActivityNotFoundException) {
                copyLink(entry, false)
                scope.launch { snackbarHostState.showSnackbar("未安装 Telegram，已复制链接") }
            }
        } else {
            copyLink(entry, true)
        }
    }

    // 分享：系统分享面板
    val onShare: (ProxyEntry) -> Unit = { entry ->
        try {
            context.startActivity(buildShareIntent(entry.link))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar("分享不可用") }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 头部：标题 + 副标题 + 更新时间 + 主题切换
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Telegram 代理管理",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "自动抓取 · 连通性测试 · 仅保留 Telegram 可用代理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            viewModel.setThemeMode(nextThemeMode(settings.themeMode))
                        },
                    ) {
                        Text(
                            text = themeModeLabel(settings.themeMode),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    text = "更新时间：${uiState.updatedAt ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 10.dp),
                )

                // 统计卡区
                StatsGrid(
                    stats = uiState.stats,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                // 错误提示条（条件显示）
                val error = uiState.error
                if (error != null) {
                    ErrorBanner(
                        message = error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // 控制区
                ControlBar(
                    fetching = uiState.fetching,
                    intervalText = intervalText,
                    onIntervalTextChange = { intervalText = it },
                    onRefresh = { viewModel.refresh() },
                    onSaveInterval = { viewModel.saveIntervalInput(intervalText) },
                    settingsMessage = uiState.settingsMessage,
                    settingsMessageIsError = uiState.settingsMessageIsError,
                    countdownSec = uiState.countdownSec,
                    countdownStarted = uiState.countdownStarted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 代理列表区（剩余空间 + 下拉刷新）
                ProxyListSection(
                    proxies = uiState.proxies,
                    fetching = uiState.fetching,
                    copiedLink = copyFlash?.link,
                    onRefresh = { viewModel.refresh() },
                    onRowClick = onRowClick,
                    onCopy = { entry -> copyLink(entry, true) },
                    onShare = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                // 页脚：数据来源
                Text(
                    text = "数据源：内置 ${Constants.DEFAULT_PROXY_LIST_URLS.size} 个（直连失败自动走 jsDelivr 镜像）；自定义：${settings.proxyListUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** 主题模式循环：SYSTEM → LIGHT → DARK → SYSTEM。 */
internal fun nextThemeMode(mode: ThemeMode): ThemeMode = when (mode) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}

/** 主题切换按钮文案。 */
internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "主题：自动"
    ThemeMode.LIGHT -> "主题：浅色"
    ThemeMode.DARK -> "主题：深色"
}
