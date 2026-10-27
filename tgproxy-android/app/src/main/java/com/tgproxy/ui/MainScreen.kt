package com.tgproxy.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgproxy.Constants
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ThemeMode
import com.tgproxy.model.TgVerdict
import com.tgproxy.ui.components.EmptyStateCard
import com.tgproxy.ui.components.ErrorBanner
import com.tgproxy.ui.components.HeroStatusCard
import com.tgproxy.ui.components.MetricGrid
import com.tgproxy.ui.components.ProxyActionDialog
import com.tgproxy.ui.components.ProxyRowCard
import com.tgproxy.ui.components.SettingsSheetContent
import com.tgproxy.ui.components.StageTimeline
import com.tgproxy.ui.components.TgGateSummary
import com.tgproxy.ui.components.formatCountdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「已复制」图标态闪现记录（id 保证相同链接重复复制也能重置 1.5s 计时）。 */
private data class CopyFlash(val link: String, val id: Long)

/**
 * 主屏幕（v2.1.0 列表优先布局，参考 v2rayNG 的「顶栏 + 卡片列表 + FAB」心智模型）：
 *
 * 首屏 = 节点列表（下拉刷新）；精简顶栏（标题 + 更新时间/倒计时 + 设置入口）；
 * 刷新按钮为右下角 FAB（刷新中转圈）；状态条/错误条/空态作为列表首项随内容滚动；
 * 设置（间隔 / known_good / 主题 / 数据源说明）收进底部弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var showSettings by remember { mutableStateOf(false) }
    var menuEntry by remember { mutableStateOf<ProxyEntry?>(null) }

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

    // 使用节点：唤起 Telegram 直接弹出「添加代理」窗口（不经过网页；未安装则降级复制）
    val onUse: (ProxyEntry) -> Unit = { entry ->
        try {
            context.startActivity(buildTelegramViewIntent(toTelegramProxyUrl(entry)))
        } catch (e: ActivityNotFoundException) {
            copyLink(entry, false)
            scope.launch { snackbarHostState.showSnackbar("未安装 Telegram，已复制链接") }
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "TG 代理",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (uiState.fetching) {
                                "正在刷新…"
                            } else {
                                "更新 ${uiState.updatedAt ?: "--"} · ${formatCountdown(uiState.countdownSec)} 后自动更新"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.refresh() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                if (uiState.fetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(14.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            PullToRefreshBox(
                isRefreshing = uiState.fetching,
                onRefresh = { viewModel.refresh() },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 仪表盘 Hero 大卡 + 四段统计卡（刷新中 = 阶段进度；空闲 = 大数字 + 摘要）
                    item {
                        val bestLatencyMs = uiState.proxies
                            .filter { it.tgVerdict == TgVerdict.PASS && it.latencyMs != null }
                            .minOfOrNull { it.latencyMs!! }
                        HeroStatusCard(
                            fetching = uiState.fetching,
                            progress = uiState.progress,
                            lastDurationMs = uiState.lastDurationMs,
                            stats = uiState.stats,
                            bestLatencyMs = bestLatencyMs,
                            countdownSec = uiState.countdownSec,
                            countdownStarted = uiState.countdownStarted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // 自适应指标网格（可用数 / 超门槛 / DC 失败 / 站点失败 / TCP 不通 / 已拉黑）
                    // 水平内边距由 contentPadding 统一给，此处不再叠加
                    item {
                        MetricGrid(
                            stats = uiState.stats,
                            tgStats = uiState.tgStats,
                        )
                    }
                    // 漏斗解释：把「测了 N 个却只剩 M 个」说清楚
                    // 槽位按 hasData 判定：无数据时整个 item 不发射，避免 spacedBy 留出空档
                    if (uiState.tgStats.hasData) {
                        item {
                            TgGateSummary(tgStats = uiState.tgStats)
                        }
                    }
                    // 上轮分阶段耗时（本轮进行中没有实时值）
                    if (uiState.durations.hasData) {
                        item {
                            StageTimeline(durations = uiState.durations)
                        }
                    }

                    // 错误提示条（条件显示）
                    val error = uiState.error
                    if (error != null) {
                        item {
                            ErrorBanner(message = error)
                        }
                    }

                    if (uiState.proxies.isEmpty()) {
                        item {
                            EmptyStateCard(
                                stats = uiState.stats,
                                fetching = uiState.fetching,
                            )
                        }
                    } else {
                        items(
                            items = uiState.proxies,
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

                    // 页脚
                    item {
                        Text(
                            text = "TG 两关实测 · 仅保留 ≤${Constants.TG_LATENCY_LIMIT_MS}ms · 点「使用」一键连 Telegram",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 88.dp),
                        )
                    }
                }
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

    // 设置底部弹层
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsSheetContent(
                settings = settings,
                intervalText = intervalText,
                onIntervalTextChange = { intervalText = it },
                onSaveInterval = { viewModel.saveIntervalInput(intervalText) },
                onKnownGoodChange = { viewModel.setKnownGoodEnabled(it) },
                onThemeModeClick = { viewModel.setThemeMode(nextThemeMode(settings.themeMode)) },
                settingsMessage = uiState.settingsMessage,
                settingsMessageIsError = uiState.settingsMessageIsError,
            )
        }
    }
}

/** 主题模式循环：SYSTEM → LIGHT → DARK → SYSTEM。 */
internal fun nextThemeMode(mode: ThemeMode): ThemeMode = when (mode) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}
