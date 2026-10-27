package com.tgproxy.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 应用根：**唯一允许碰 ViewModel 的 Composable**。
 *
 * 职责就两件 —— 把 ViewModel 的状态收集成值、把动作包成回调，然后交给
 * [MainScreen]。界面层因此不再依赖 ViewModel 类型，`@Preview` 里可以用
 * 假数据把整套骨架渲染出来。
 *
 * 注意这里**不收集** `progress`：它每测一个节点变一次，在这里 `collectAsState`
 * 会让 AppRoot 逐次重组，连带 MainScreen 与 `LazyColumn` 的 content lambda 一起
 * 重跑。只把 StateFlow 原样透传下去，读点留在 Hero 那张卡的 `item {}` 里。
 */
@Composable
fun AppRoot(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    TgProxyTheme(themeMode = settings.themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            MainScreen(
                uiState = uiState,
                settings = settings,
                progressFlow = viewModel.progress,
                onRefresh = { viewModel.refresh() },
                onSaveInterval = { viewModel.saveIntervalInput(it) },
                onThemeModeChange = { viewModel.setThemeMode(it) },
                onKnownGoodChange = { viewModel.setKnownGoodEnabled(it) },
            )
        }
    }
}
