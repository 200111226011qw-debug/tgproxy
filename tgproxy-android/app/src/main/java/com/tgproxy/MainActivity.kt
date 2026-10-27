package com.tgproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.tgproxy.ui.AppRoot
import com.tgproxy.ui.MainViewModel

/**
 * 单 Activity 入口（对应桌面版 app.py main() 的启动职责）。
 *
 * - `by viewModels` + [MainViewModel.factory] 注入生产依赖；
 * - `setContent` 挂载 [AppRoot] —— 状态收集与回调桥接都在那里，界面层
 *   （`MainScreen` 及其下）不再直接依赖 ViewModel，因此可以被 `@Preview` 渲染；
 * - onResume → [MainViewModel.onResumeSync]（同步状态 + 超龄补刷，替代桌面版 visibilitychange 轮询）；
 * - onPause → [MainViewModel.onPause]（暂停倒计时 tick 省电）。
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy {
        (application as TgProxyApp).container
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(
            repository = container.proxyRepository,
            settingsStore = container.settingsStore,
            roundLogger = container.roundLogger,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot(viewModel = viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumeSync()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }
}
