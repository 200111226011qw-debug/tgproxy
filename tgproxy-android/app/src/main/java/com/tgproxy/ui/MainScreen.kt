package com.tgproxy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgproxy.model.Countdown
import com.tgproxy.model.ProxyUiState
import com.tgproxy.model.RefreshProgress
import com.tgproxy.model.RefreshStage
import com.tgproxy.model.Settings
import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.components.ErrorBanner
import com.tgproxy.ui.components.SettingsSheetContent
import com.tgproxy.ui.components.formatCountdown
import com.tgproxy.ui.preview.PreviewCountdown
import com.tgproxy.ui.preview.PreviewUiState
import com.tgproxy.ui.screens.DashboardScreen
import com.tgproxy.ui.screens.LogsScreen
import com.tgproxy.ui.screens.NodesScreen
import com.tgproxy.ui.screens.bestLatencyOf
import com.tgproxy.ui.theme.TgProxyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 主屏幕外壳：顶栏 + 三页签 + 悬浮刷新按钮 + 设置弹层。
 *
 * **不持有 ViewModel**：状态由参数进来，动作由回调出去（State Hoisting）。
 * 好处不只是「符合规范」——`@Preview` 里造不出 ViewModel，参数化之后整套
 * 骨架能在 Android Studio 里直接渲染，布局调整不必再靠 dp 推算。
 * 桥接在 [AppRoot]。
 *
 * @param progressFlow 刷新进度流。**必须传流，不能传值**：进度每测一个节点
 *   变一次，若在 [AppRoot] 里收集成值再传下来，AppRoot 会逐次重组，把
 *   `LazyColumn` 的 content lambda 也带着重跑 —— 那正是上一个提交消掉的东西。
 *   传流之后读点钉在 Hero 那个 `item {}` 内部，只有那张卡重组。
 * @param onThemeModeChange 主题改为指定值（循环规则留在本文件，属于「点一下
 *   发生什么」的 UI 语义；持久化才是 ViewModel 的事）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: ProxyUiState,
    settings: Settings,
    progressFlow: StateFlow<RefreshProgress?>,
    countdownFlow: StateFlow<Countdown>,
    onRefresh: () -> Unit,
    onSaveInterval: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onKnownGoodChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var intervalText by remember(settings.intervalMinutes) {
        mutableStateOf(settings.intervalMinutes.toString())
    }
    var showSettings by remember { mutableStateOf(false) }

    val tabs = MainTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    // 在 pager 的 content lambda 外面算：lambda 在滑动过程中会随 pager 状态反复
    // 执行，2000 条过滤 + minOf 每帧跑一遍没必要。按列表实例缓存，换列表才算。
    val bestLatencyMs = remember(uiState.proxies) { bestLatencyOf(uiState.proxies) }

    // 两个页的滚动位置提升到这里，而不是依赖 pager 内部是否给了 SaveableStateHolder：
    // MainScreen 在切页时始终在组合里，状态一定活得住。`rememberLazyListState` 本身
    // 走 rememberSaveable，进程被回收后也能恢复。
    val dashboardListState = rememberLazyListState()
    val nodesListState = rememberLazyListState()
    val logsListState = rememberLazyListState()

    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
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
                        // 倒计时在子组件内部订阅：放在 MainScreen 顶层读的话，
                        // 每秒一次的新值会把整页（含 pager 与列表）带着重组
                        TopBarSubtitle(
                            fetching = uiState.fetching,
                            updatedAt = uiState.updatedAt,
                            countdownFlow = countdownFlow,
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
        bottomBar = {
            // navigationBarsPadding 不能省：targetSdk 35 在 Android 15 上强制
            // edge-to-edge，而 PrimaryTabRow 不像 TopAppBar 那样自带窗口内边距，
            // 不加的话页签文字会压在手势条 / 三键导航下面。
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            if (pagerState.currentPage != index) {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                        },
                        text = { Text(tab.title) },
                    )
                }
            }
        },
        floatingActionButton = {
            RefreshFab(fetching = uiState.fetching, onRefresh = onRefresh)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误条放在 pager 外面：跨页签常驻，不随页面滚走
            val error = uiState.error
            if (error != null) {
                ErrorBanner(
                    message = error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (tabs[page]) {
                    MainTab.DASHBOARD -> DashboardScreen(
                        fetching = uiState.fetching,
                        stats = uiState.stats,
                        tgStats = uiState.tgStats,
                        durations = uiState.durations,
                        lastDurationMs = uiState.lastDurationMs,
                        bestLatencyMs = bestLatencyMs,
                        progressFlow = progressFlow,
                        countdownFlow = countdownFlow,
                        listState = dashboardListState,
                    )

                    MainTab.NODES -> NodesScreen(
                        proxies = uiState.proxies,
                        stats = uiState.stats,
                        fetching = uiState.fetching,
                        onRefresh = onRefresh,
                        onMessage = showMessage,
                        listState = nodesListState,
                    )

                    MainTab.LOGS -> LogsScreen(
                        logs = uiState.logs,
                        listState = logsListState,
                    )
                }
            }
        }
    }

    // 设置底部弹层
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsSheetContent(
                settings = settings,
                intervalText = intervalText,
                onIntervalTextChange = { intervalText = it },
                onSaveInterval = { onSaveInterval(intervalText) },
                onKnownGoodChange = onKnownGoodChange,
                onThemeModeClick = { onThemeModeChange(nextThemeMode(settings.themeMode)) },
                settingsMessage = uiState.settingsMessage,
                settingsMessageIsError = uiState.settingsMessageIsError,
            )
        }
    }
}

/**
 * 顶栏副标题：更新时间 + 距下次自动刷新的倒计时。
 *
 * 单独抽出来只为一件事 —— 把 `countdownFlow` 的**读点关在这里**。在 [MainScreen]
 * 顶层读的话，每秒一次的新值会让整个 Scaffold（含 pager 与 2000 条节点列表）
 * 跟着重组；关在这里，一秒只重画这一行文字。
 */
@Composable
private fun TopBarSubtitle(
    fetching: Boolean,
    updatedAt: String?,
    countdownFlow: StateFlow<Countdown>,
) {
    val countdown by countdownFlow.collectAsStateWithLifecycle()
    Text(
        text = if (fetching) {
            "正在刷新…"
        } else {
            "更新 ${updatedAt ?: "--"} · ${formatCountdown(countdown.sec)} 后自动更新"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 悬浮刷新按钮（三态的第一态：空闲可点 / 刷新中置灰转圈）。
 *
 * 「取消」不做：那需要轮级事务（见 ARCHITECTURE 里对取消的结论），先把文案与
 * 可点性做对，等事务落地再把「正在刷新」换成「取消」。
 */
@Composable
private fun RefreshFab(fetching: Boolean, onRefresh: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = { if (!fetching) onRefresh() },
        containerColor = if (fetching) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
        contentColor = if (fetching) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        icon = {
            if (fetching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                )
            }
        },
        text = { Text(if (fetching) "正在刷新" else "刷新") },
    )
}

/** 主题模式循环：SYSTEM → LIGHT → DARK → SYSTEM。 */
internal fun nextThemeMode(mode: ThemeMode): ThemeMode = when (mode) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}

// ---------------------------------------------------------------------------
// 预览
//
// 参数提升之后这些才写得出来 —— 之前 MainScreen 要一个 MainViewModel，
// @Preview 里造不出来，整套骨架只能靠装 APK 看。
// ---------------------------------------------------------------------------

@Preview(name = "主界面 · 浅色", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun MainScreenPreviewLight() {
    TgProxyTheme(themeMode = ThemeMode.LIGHT) {
        MainScreen(
            uiState = PreviewUiState,
            settings = Settings(),
            progressFlow = remember { MutableStateFlow(null) },
            countdownFlow = remember { MutableStateFlow(PreviewCountdown) },
            onRefresh = {},
            onSaveInterval = {},
            onThemeModeChange = {},
            onKnownGoodChange = {},
        )
    }
}

@Preview(name = "主界面 · 深色", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun MainScreenPreviewDark() {
    TgProxyTheme(themeMode = ThemeMode.DARK) {
        MainScreen(
            uiState = PreviewUiState,
            settings = Settings(themeMode = ThemeMode.DARK),
            progressFlow = remember { MutableStateFlow(null) },
            countdownFlow = remember { MutableStateFlow(PreviewCountdown) },
            onRefresh = {},
            onSaveInterval = {},
            onThemeModeChange = {},
            onKnownGoodChange = {},
        )
    }
}

/** 错误条：验证它占位时会不会把 pager 挤歪、不出现时会不会留白。 */
@Preview(name = "主界面 · 有错误条", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun MainScreenPreviewError() {
    TgProxyTheme(themeMode = ThemeMode.LIGHT) {
        MainScreen(
            uiState = PreviewUiState.copy(
                error = "抓取代理列表失败：全部 8 个数据源抓取失败",
            ),
            settings = Settings(),
            progressFlow = remember { MutableStateFlow(null) },
            countdownFlow = remember { MutableStateFlow(PreviewCountdown) },
            onRefresh = {},
            onSaveInterval = {},
            onThemeModeChange = {},
            onKnownGoodChange = {},
        )
    }
}

/** 刷新中：FAB 置灰转圈 + Hero 卡显示阶段与 done/total。 */
@Preview(name = "主界面 · 正在刷新", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun MainScreenPreviewFetching() {
    TgProxyTheme(themeMode = ThemeMode.LIGHT) {
        MainScreen(
            uiState = PreviewUiState.copy(fetching = true, updatedAt = null),
            settings = Settings(),
            progressFlow = remember {
                MutableStateFlow(RefreshProgress(RefreshStage.TG_TEST, 18, 40))
            },
            countdownFlow = remember { MutableStateFlow(PreviewCountdown) },
            onRefresh = {},
            onSaveInterval = {},
            onThemeModeChange = {},
            onKnownGoodChange = {},
        )
    }
}
