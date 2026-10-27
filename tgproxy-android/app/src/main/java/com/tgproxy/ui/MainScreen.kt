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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.components.ErrorBanner
import com.tgproxy.ui.components.SettingsSheetContent
import com.tgproxy.ui.components.formatCountdown
import com.tgproxy.ui.screens.DashboardScreen
import com.tgproxy.ui.screens.LogsScreen
import com.tgproxy.ui.screens.NodesScreen
import com.tgproxy.ui.screens.bestLatencyOf
import kotlinx.coroutines.launch

/**
 * 主屏幕外壳：顶栏 + 三页签 + 悬浮刷新按钮 + 设置弹层。
 *
 * 结构：
 * - [TopAppBar] 标题 / 更新时间与倒计时 / 设置入口
 * - 内容区上沿的错误条（跨页签常驻：抓取失败时不管在哪个页签都该看得见）
 * - [HorizontalPager] 承载 [DashboardScreen] / [NodesScreen] / [LogsScreen]
 * - [PrimaryTabRow] 底部页签，点击走 `animateScrollToPage`（与滑动共用同一状态）
 * - 悬浮刷新按钮：刷新中转灰并显示转圈，点击不再触发（互斥本来就由 ViewModel 保证，
 *   这里只是别让用户以为点了没反应）
 *
 * 页签状态只由 `pagerState` 一处持有 —— 它是 `rememberSaveable` 支撑的，
 * 进程被回收后能回到原页。不要再另开一个 `rememberSaveable { selectedTab }`：
 * 那样会有两个真相源，其中一个永远没人写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
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
            RefreshFab(
                fetching = uiState.fetching,
                onRefresh = { viewModel.refresh() },
            )
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
                        countdownSec = uiState.countdownSec,
                        countdownStarted = uiState.countdownStarted,
                        progressFlow = viewModel.progress,
                        listState = dashboardListState,
                    )

                    MainTab.NODES -> NodesScreen(
                        proxies = uiState.proxies,
                        stats = uiState.stats,
                        fetching = uiState.fetching,
                        onRefresh = { viewModel.refresh() },
                        onMessage = showMessage,
                        listState = nodesListState,
                    )

                    MainTab.LOGS -> LogsScreen()
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
                onSaveInterval = { viewModel.saveIntervalInput(intervalText) },
                onKnownGoodChange = { viewModel.setKnownGoodEnabled(it) },
                onThemeModeClick = { viewModel.setThemeMode(nextThemeMode(settings.themeMode)) },
                settingsMessage = uiState.settingsMessage,
                settingsMessageIsError = uiState.settingsMessageIsError,
            )
        }
    }
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
