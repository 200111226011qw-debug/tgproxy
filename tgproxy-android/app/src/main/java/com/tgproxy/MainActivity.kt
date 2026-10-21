package com.tgproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgproxy.ui.MainScreen
import com.tgproxy.ui.MainViewModel
import com.tgproxy.ui.theme.TgProxyTheme

/**
 * 单 Activity 入口（对应桌面版 app.py main() 的启动职责）。
 *
 * - `by viewModels` + [MainViewModel.factory] 注入生产依赖；
 * - `setContent` 挂载 [MainScreen]；
 * - onResume → [MainViewModel.onResumeSync]（同步状态 + 超龄补刷，替代桌面版 visibilitychange 轮询）；
 * - onPause → [MainViewModel.onPause]（暂停倒计时 tick 省电）。
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy {
        (application as TgProxyApp).container
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(container.proxyRepository, container.settingsStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            TgProxyTheme(themeMode = settings.themeMode) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(viewModel = viewModel)
                }
            }
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
