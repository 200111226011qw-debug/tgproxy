package com.tgproxy

import android.content.Context
import com.tgproxy.data.KnownGoodStore
import com.tgproxy.data.ProxyFetcher
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.SettingsRepository
import com.tgproxy.data.SettingsStore
import java.io.File

/**
 * 手工 DI 容器：集中持有平台层依赖（OkHttp / DataStore / known_good 文件），向 ViewModel 提供生产实现。
 *
 * 采用 applicationContext 持有，避免 Activity 上下文泄漏。
 */
class AppContainer(context: Context) {

    private val applicationContext: Context = context.applicationContext

    /** 设置存储（DataStore 实现）。 */
    val settingsStore: SettingsStore by lazy { SettingsRepository(applicationContext) }

    /** known_good 沉淀文件（应用私有目录，等价桌面版 known_good.json）。 */
    val knownGoodStore: KnownGoodStore by lazy {
        KnownGoodStore(File(applicationContext.filesDir, "known_good.json"))
    }

    /** 代理仓储（OkHttp 抓取 + 真实 TCP 预筛 + TG 实测两关的生产组合）。 */
    val proxyRepository: ProxyRepository by lazy {
        ProxyRepository(
            source = ProxyFetcher(),
            knownGood = knownGoodStore,
        )
    }
}
