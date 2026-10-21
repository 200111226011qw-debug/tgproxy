package com.tgproxy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tgproxy.Constants
import com.tgproxy.model.Settings
import com.tgproxy.model.ThemeMode
import com.tgproxy.model.normalizeIntervalMinutes
import com.tgproxy.model.normalizeProxyListUrl
import com.tgproxy.model.normalizeThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 设置存储接口（等价桌面版 config.json 读写的安卓子集；面向接口便于测试）。
 */
interface SettingsStore {
    /** 设置流：DataStore 变更即推；暴露前经 normalize 校正。 */
    val settings: Flow<Settings>

    /** 读取当前设置（首帧）。 */
    suspend fun current(): Settings

    /** 写入间隔（分钟），写入前 normalize。 */
    suspend fun setIntervalMinutes(value: Int)

    /** 写入数据源地址，写入前 normalize。 */
    suspend fun setProxyListUrl(value: String)

    /** 写入主题模式。 */
    suspend fun setThemeMode(mode: ThemeMode)
}

/** DataStore 单例委托（进程内唯一实例）。 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tgproxy_settings",
)

/**
 * DataStore Preferences 实现的 [SettingsStore]（本文件是数据层唯一允许依赖 Android 的类）。
 *
 * 键：interval_minutes / proxy_list_url / theme_mode；损坏或越界一律回退默认值
 * （等价桌面版 load_config + normalize_config）。
 */
class SettingsRepository(context: Context) : SettingsStore {

    private val store = context.dataStore

    override val settings: Flow<Settings> = store.data
        .map { prefs ->
            Settings(
                intervalMinutes = normalizeIntervalMinutes(
                    prefs[KEY_INTERVAL] ?: Constants.DEFAULT_INTERVAL_MINUTES,
                ),
                proxyListUrl = normalizeProxyListUrl(
                    prefs[KEY_URL] ?: Constants.DEFAULT_PROXY_LIST_URL,
                ),
                themeMode = normalizeThemeMode(prefs[KEY_THEME]),
            )
        }
        .catch { emit(Settings()) }

    override suspend fun current(): Settings = settings.first()

    override suspend fun setIntervalMinutes(value: Int) {
        store.edit { prefs -> prefs[KEY_INTERVAL] = normalizeIntervalMinutes(value) }
    }

    override suspend fun setProxyListUrl(value: String) {
        store.edit { prefs -> prefs[KEY_URL] = normalizeProxyListUrl(value) }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { prefs -> prefs[KEY_THEME] = mode.name }
    }

    private companion object {
        val KEY_INTERVAL = intPreferencesKey("interval_minutes")
        val KEY_URL = stringPreferencesKey("proxy_list_url")
        val KEY_THEME = stringPreferencesKey("theme_mode")
    }
}
