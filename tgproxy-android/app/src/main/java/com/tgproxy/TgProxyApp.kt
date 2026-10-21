package com.tgproxy

import android.app.Application

/**
 * Application 入口：初始化 [AppContainer]（手工 DI）。
 */
class TgProxyApp : Application() {

    /** 全局依赖容器。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
