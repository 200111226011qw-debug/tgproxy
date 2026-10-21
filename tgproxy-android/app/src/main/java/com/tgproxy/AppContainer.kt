package com.tgproxy

import android.content.Context
import com.tgproxy.data.ProxyFetcher
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.SettingsRepository
import com.tgproxy.data.SettingsStore

/**
 * 手工 DI 容器：集中持有平台层依赖（OkHttp / DataStore），向 ViewModel 提供生产实现。
 *
 * 采用 applicationContext 持有，避免 Activity 上下文泄漏。
 */
class AppContainer(context: Context) {

    private val applicationContext: Context = context.applicationContext

    /** 设置存储（DataStore 实现）。 */
    val settingsStore: SettingsStore by lazy { SettingsRepository(applicationContext) }

    /** 代理仓储（OkHttp 抓取 + 真实 TCP 测试的生产组合）。 */
    val proxyRepository: ProxyRepository by lazy {
        ProxyRepository(source = ProxyFetcher())
    }
}
