package com.tgproxy

import com.tgproxy.data.LatencyTester
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.ProxySource
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.Settings
import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.MainViewModel
import com.tgproxy.ui.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * MainViewModel 行为对拍（ARCHITECTURE.md §7 T03 / PRD §7-3~7-6）：
 * 启动刷新 / 互斥 / 失败保留 / 间隔重置 / 到期刷新 / 补刷（虚拟时间）。
 */
class MainViewModelTest {

    /** 与 runTest 共享同一调度器，保证 advanceTimeBy 能驱动 viewModelScope。 */
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    // ------------------------------------------------------------------
    // 假件
    // ------------------------------------------------------------------

    /** 可控假数据源：支持按序返回文本、抛错、以及在 gate 上挂起。 */
    private class FakeSource : ProxySource {
        val calls = AtomicInteger(0)
        var nextText: String? = null
        var nextError: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetch(url: String): String {
            calls.incrementAndGet()
            gate?.await()
            nextError?.let { throw it }
            return nextText ?: ""
        }
    }

    /** 确定性假测试器：偶数下标可用（100ms），奇数下标不可用；保序。 */
    private class FakeTester : LatencyTester {
        override suspend fun testAll(entries: List<ProxyEntry>): List<ProxyEntry> =
            entries.mapIndexed { index, entry ->
                if (index % 2 == 0) {
                    entry.copy(latencyMs = 100, status = ProxyStatus.AVAILABLE)
                } else {
                    entry.copy(latencyMs = null, status = ProxyStatus.UNAVAILABLE)
                }
            }
    }

    /** 内存假设置存储：MutableStateFlow 发射，记录写入值。 */
    private class FakeSettingsStore : com.tgproxy.data.SettingsStore {
        private val state = MutableStateFlow(Settings())
        override val settings: Flow<Settings> = state.asStateFlow()
        var savedInterval: Int? = null
        var savedTheme: ThemeMode? = null

        override suspend fun current(): Settings = state.value

        override suspend fun setIntervalMinutes(value: Int) {
            savedInterval = value
            state.value = state.value.copy(intervalMinutes = value)
        }

        override suspend fun setProxyListUrl(value: String) {
            state.value = state.value.copy(proxyListUrl = value)
        }

        override suspend fun setThemeMode(mode: ThemeMode) {
            savedTheme = mode
            state.value = state.value.copy(themeMode = mode)
        }
    }

    /** 假时钟：测试代码手动推进虚拟纳秒。 */
    private class FakeTimeSource : TimeSource {
        var nowNs: Long = 0L
        override fun nowNanos(): Long = nowNs
    }

    /** 将 viewModelScope 的 Main 调度器替换为测试调度器。 */
    class MainDispatcherRule(private val dispatcher: TestDispatcher) : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `初始化后自动触发一轮刷新，fetching 状态机为 false 到 true 再到 false`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val source = FakeSource().apply {
                this.gate = gate
                nextText = "socks5://1.2.3.4:1080"
            }
            val viewModel = MainViewModel(
                repository = ProxyRepository(source, FakeTester()),
                settingsStore = FakeSettingsStore(),
                timeSource = FakeTimeSource(),
            )
            // 初始未刷新
            assertFalse(viewModel.uiState.value.fetching)

            // 推进到抓取被调用并挂起
            advanceTimeBy(100)
            assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())
            assertTrue(viewModel.uiState.value.fetching)

            // 放行后完成
            gate.complete(Unit)
            advanceTimeBy(100)
            assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())
            assertFalse(viewModel.uiState.value.fetching)
            assertEquals(1, viewModel.uiState.value.proxies.size)
            assertEquals("socks5://1.2.3.4:1080", viewModel.uiState.value.proxies[0].link)
        }

    @Test
    fun `刷新进行中再次触发刷新会被直接忽略`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply {
            this.gate = gate
            nextText = "socks5://1.2.3.4:1080"
        }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester()),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
        )
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())

        // 刷新中重复触发（手动/下拉等价）：不得触发第二轮抓取
        viewModel.refresh()
        viewModel.refresh()
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())
        assertTrue(viewModel.uiState.value.fetching)

        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())
        assertFalse(viewModel.uiState.value.fetching)
    }

    @Test
    fun `抓取失败保留旧列表并展示错误，下一轮成功后清除错误`() = runTest(testDispatcher) {
        val source = FakeSource().apply {
            nextText = "socks5://1.2.3.4:1080\nsocks5://1.2.3.5:1080"
        }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester()),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
        )
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())
        // FakeTester：偶数下标可用 → 2 条中 1 条保留（不通已剔除）
        assertEquals(1, viewModel.uiState.value.proxies.size)
        assertNull(viewModel.uiState.value.error)
        val updatedAt = viewModel.uiState.value.updatedAt
        val available = viewModel.uiState.value.stats.available

        // 失败一轮：旧列表/统计/更新时间保留，仅 error 更新
        source.nextError = IOException("boom")
        viewModel.refresh()
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size * 2, source.calls.get())
        assertEquals(1, viewModel.uiState.value.proxies.size)
        assertEquals(available, viewModel.uiState.value.stats.available)
        assertEquals(updatedAt, viewModel.uiState.value.updatedAt)
        assertEquals("抓取代理列表失败：boom", viewModel.uiState.value.error)

        // 成功一轮：错误清除，列表更新
        source.nextError = null
        source.nextText = "socks5://9.9.9.9:1080"
        viewModel.refresh()
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size * 3, source.calls.get())
        assertEquals(1, viewModel.uiState.value.proxies.size)
        assertEquals("socks5://9.9.9.9:1080", viewModel.uiState.value.proxies[0].link)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `保存非法间隔只提示不持久化，保存合法后持久化并重置倒计时`() =
        runTest(testDispatcher) {
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val store = FakeSettingsStore()
            val viewModel = MainViewModel(
                repository = ProxyRepository(source, FakeTester()),
                settingsStore = store,
                timeSource = FakeTimeSource(),
            )
            advanceTimeBy(100)

            // 非法输入：非整数 / 越界
            viewModel.saveIntervalInput("abc")
            advanceTimeBy(100)
            assertEquals("请输入 1~1440 之间的整数", viewModel.uiState.value.settingsMessage)
            assertTrue(viewModel.uiState.value.settingsMessageIsError)
            assertNull(store.savedInterval)

            viewModel.saveIntervalInput("1441")
            advanceTimeBy(100)
            assertTrue(viewModel.uiState.value.settingsMessageIsError)
            assertNull(store.savedInterval)

            // 合法输入：持久化 + 成功提示
            viewModel.saveIntervalInput("5")
            advanceTimeBy(100)
            assertEquals(5, store.savedInterval)
            assertEquals("已保存，立即生效", viewModel.uiState.value.settingsMessage)
            assertFalse(viewModel.uiState.value.settingsMessageIsError)

            // 倒计时按新间隔（5 分钟 = 300 秒）重新计时
            assertEquals(300, viewModel.uiState.value.countdownSec)
            assertTrue(viewModel.uiState.value.countdownStarted)
        }

    @Test
    fun `虚拟时间推进超过间隔后自动触发第二轮刷新`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val time = FakeTimeSource()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester()),
            settingsStore = FakeSettingsStore(),
            timeSource = time,
        )
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())

        // 越过默认间隔 10 分钟后再推进一个 tick
        time.nowNs += 601_000_000_000L
        advanceTimeBy(1_100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size * 2, source.calls.get())
        assertFalse(viewModel.uiState.value.fetching)
    }

    @Test
    fun `onPause 后倒计时停止，onResumeSync 在超龄时补刷一轮`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val time = FakeTimeSource()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester()),
            settingsStore = FakeSettingsStore(),
            timeSource = time,
        )
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())

        // 退后台：tick 停止；时间越过间隔也不刷新
        viewModel.onPause()
        time.nowNs += 601_000_000_000L
        advanceTimeBy(5_000)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())

        // 回前台：超龄 → 补刷一轮
        viewModel.onResumeSync()
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size * 2, source.calls.get())
        assertFalse(viewModel.uiState.value.fetching)
    }

    @Test
    fun `onResumeSync 在未超龄时只恢复倒计时不触发刷新`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val time = FakeTimeSource()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester()),
            settingsStore = FakeSettingsStore(),
            timeSource = time,
        )
        advanceTimeBy(100)
        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())

        viewModel.onPause()
        time.nowNs += 60_000_000_000L // 仅过去 1 分钟，未超 10 分钟间隔
        viewModel.onResumeSync()
        advanceTimeBy(100)

        assertEquals(Constants.DEFAULT_PROXY_LIST_URLS.size, source.calls.get())
        assertTrue(viewModel.uiState.value.countdownStarted)
    }

    @Test
    fun `主题模式切换会持久化到设置存储`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester()),
            settingsStore = store,
            timeSource = FakeTimeSource(),
        )
        advanceTimeBy(100)

        viewModel.setThemeMode(ThemeMode.DARK)
        advanceTimeBy(100)

        assertEquals(ThemeMode.DARK, store.savedTheme)
        assertEquals(ThemeMode.DARK, viewModel.settings.value.themeMode)
    }
}
