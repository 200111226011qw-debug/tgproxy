package com.tgproxy

import com.tgproxy.data.LatencyTester
import com.tgproxy.data.ProxyRepository
import com.tgproxy.data.ProxySource
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.RefreshStage
import com.tgproxy.model.Settings
import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.MainViewModel
import androidx.lifecycle.viewModelScope
import com.tgproxy.ui.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertSame
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
        override suspend fun testAll(
            entries: List<ProxyEntry>,
            onProgress: (Int, Int) -> Unit,
        ): List<ProxyEntry> =
            entries.mapIndexed { index, entry ->
                if (index % 2 == 0) {
                    entry.copy(latencyMs = 100, status = ProxyStatus.AVAILABLE)
                } else {
                    entry.copy(latencyMs = null, status = ProxyStatus.UNAVAILABLE)
                }
            }
    }

    /** 全通过假 TG 闸门（120ms）；不碰网络。 */
    private class FakeGate : com.tgproxy.data.TelegramGateCaller {
        override fun test(host: String, port: Int): Int? = 120
    }

    /** 全不过假 TG 闸门；不碰网络。 */
    private class FailingGate : com.tgproxy.data.TelegramGateCaller {
        override fun test(host: String, port: Int): Int? = null
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

        var savedKnownGood: Boolean? = null

        override suspend fun setKnownGoodEnabled(value: Boolean) {
            savedKnownGood = value
            state.value = state.value.copy(knownGoodEnabled = value)
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
                repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
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
            viewModel.viewModelScope.cancel() // 结束测试：取消常驻收集/倒计时协程
        }

    @Test
    fun `刷新进行中再次触发刷新会被直接忽略`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply {
            this.gate = gate
            nextText = "socks5://1.2.3.4:1080"
        }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
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
        viewModel.viewModelScope.cancel() // 结束测试：取消常驻收集/倒计时协程
    }

    @Test
    fun `两关分布进入 uiState（仪表盘漏斗数据源）`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
        )
        advanceTimeBy(200)

        val state = viewModel.uiState.value
        // FakeGate 只实现了两参 test（返回 120ms 非空）→ 接口默认 testDetailed
        // 粗分类为 OK。这里验证的是「repository 的 gateStats 有没有被搬进 uiState」，
        // 搬漏了就是空对象，total 会是 0。
        assertTrue("两关分布没进 uiState：${state.tgStats}", state.tgStats.total >= 1)
        assertEquals("两关分布分桶错误：${state.tgStats}", 1, state.tgStats.ok)
        // durations 与 tgStats 在同一条 copy 里赋值；虚拟时间下 totalMs 可能为 0，
        // 只断言非负 —— 「有没有赋值」由上面 tgStats 那条覆盖
        assertTrue(state.durations.totalMs >= 0)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `抓取失败保留旧列表并展示错误，下一轮成功后清除错误`() = runTest(testDispatcher) {
        val source = FakeSource().apply {
            nextText = "socks5://1.2.3.4:1080\nsocks5://1.2.3.5:1080"
        }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
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
        viewModel.viewModelScope.cancel() // 结束测试：取消常驻收集/倒计时协程
    }

    @Test
    fun `保存非法间隔只提示不持久化，保存合法后持久化并重置倒计时`() =
        runTest(testDispatcher) {
            val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
            val store = FakeSettingsStore()
            val viewModel = MainViewModel(
                repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
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
            assertEquals(300, viewModel.countdown.value.sec)
            assertTrue(viewModel.countdown.value.started)
            viewModel.viewModelScope.cancel() // 结束测试：取消常驻收集/倒计时协程
        }

    @Test
    fun `虚拟时间推进超过间隔后自动触发第二轮刷新`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val time = FakeTimeSource()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
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
        viewModel.viewModelScope.cancel() // 结束测试：取消常驻收集/倒计时协程
    }

    @Test
    fun `onPause 后倒计时停止，onResumeSync 在超龄时补刷一轮`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val time = FakeTimeSource()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
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
        viewModel.viewModelScope.cancel() // 结束测试：取消常驻收集/倒计时协程
    }

    @Test
    fun `onResumeSync 在未超龄时只恢复倒计时不触发刷新`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val time = FakeTimeSource()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
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
        assertTrue(viewModel.countdown.value.started)
        viewModel.viewModelScope.cancel() // 结束测试：取消常驻收集/倒计时协程
    }

    @Test
    fun `主题模式切换会持久化到设置存储`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
            settingsStore = store,
            timeSource = FakeTimeSource(),
        )
        advanceTimeBy(100)

        viewModel.setThemeMode(ThemeMode.DARK)
        advanceTimeBy(100)

        assertEquals(ThemeMode.DARK, store.savedTheme)
        assertEquals(ThemeMode.DARK, viewModel.settings.value.themeMode)
        viewModel.viewModelScope.cancel() // 结束测试：取消常驻收集/倒计时协程
    }

    @Test
    fun `首轮 0 个通过双闸且无旧列表时清空且无错误`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FailingGate(), ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
        )
        try {
            advanceTimeBy(100)
            // 首轮 0 达标且此前无列表：列表为空，不误报错误（与桌面版一致）
            assertEquals(0, viewModel.uiState.value.proxies.size)
            assertNull(viewModel.uiState.value.error)
            assertEquals(0, viewModel.uiState.value.stats.available)
            assertEquals(1, viewModel.uiState.value.stats.tgFailed)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `已有列表时本轮 0 个通过双闸保留旧列表并提示门槛`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        // 闸门只在首轮放行：第二次调用起全不过
        val gate = object : com.tgproxy.data.TelegramGateCaller {
            var calls = 0
            override fun test(host: String, port: Int): Int? =
                if (calls++ == 0) 120 else null
        }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), gate, ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
        )
        try {
            advanceTimeBy(100)
            assertEquals(1, viewModel.uiState.value.proxies.size)
            assertNull(viewModel.uiState.value.error)

            // 第二轮：0 保留 → 旧列表沿用 + 门槛提示
            viewModel.refresh()
            advanceTimeBy(100)
            assertEquals(1, viewModel.uiState.value.proxies.size)
            assertEquals("socks5://1.2.3.4:1080", viewModel.uiState.value.proxies[0].link)
            assertTrue(
                viewModel.uiState.value.error!!.contains("本轮 0 个节点通过 TG 实测"),
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `known_good 开启时启动即展示沉淀列表`() = runTest(testDispatcher) {
        val dir = java.io.File.createTempFile("vmkg", "").let { it.delete(); it.mkdirs(); it }
        val file = java.io.File(dir, "known_good.json")
        file.writeText(
            """[{"link":"socks5://4.3.2.1:1080","host":"4.3.2.1","port":1080,"type":"SOCKS5"}]""",
        )
        val store = com.tgproxy.data.KnownGoodStore(file)
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply {
            this.gate = gate
            nextText = "socks5://1.2.3.4:1080"
        }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), knownGood = store, ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
        )
        try {
            advanceTimeBy(100)
            // 刷新仍挂起（gate 未放行），但 known_good 列表已先行展示
            assertEquals(1, viewModel.uiState.value.proxies.size)
            assertEquals("socks5://4.3.2.1:1080", viewModel.uiState.value.proxies[0].link)

            gate.complete(Unit)
            advanceTimeBy(100)
            // 刷新完成后被本轮结果（1 条 TCP 通过 + 闸门通过）替换
            assertEquals("socks5://1.2.3.4:1080", viewModel.uiState.value.proxies[0].link)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 轮次记录
    // ------------------------------------------------------------------

    @Test
    fun `每轮刷新都记一条，成功与失败都记`() = runTest(testDispatcher) {
        val dir = java.io.File.createTempFile("rounds", "").let { it.delete(); it.mkdirs(); it }
        // io 用测试调度器：否则 append 会跳到真实 IO 线程，虚拟时间推不动它，
        // 断言只能靠 sleep 碰运气
        val logger = com.tgproxy.data.RoundLogger(
            java.io.File(dir, "rounds.jsonl"),
            io = testDispatcher,
        )
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
            roundLogger = logger,
        )
        try {
            advanceTimeBy(200)
            assertEquals(1, viewModel.uiState.value.logs.size)
            val ok = viewModel.uiState.value.logs.single()
            assertTrue("成功轮应记 success=true", ok.success)
            assertNull(ok.error)
            assertEquals(1L, ok.seq)
            // 日志里的统计要与本轮 uiState 一致（同一份数据，不许各算一遍）
            assertEquals(viewModel.uiState.value.stats.tested, ok.stats.tested)
            assertEquals(viewModel.uiState.value.tgStats, ok.tgStats)

            // 失败一轮：也要留痕，且不填统计（阶段没跑完，填 0 会被读成「测了 0 个」）
            source.nextError = IOException("boom")
            viewModel.refresh()
            advanceTimeBy(200)
            assertEquals(2, viewModel.uiState.value.logs.size)
            val failed = viewModel.uiState.value.logs.last()
            assertFalse("失败轮应记 success=false", failed.success)
            assertTrue("失败原因要落进日志：${failed.error}", failed.error!!.contains("boom"))
            assertEquals(0, failed.stats.tested)
            assertEquals(2L, failed.seq)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `冷启动读取历史轮次`() = runTest(testDispatcher) {
        val dir = java.io.File.createTempFile("rounds", "").let { it.delete(); it.mkdirs(); it }
        val file = java.io.File(dir, "rounds.jsonl")
        // 预置两条历史（直接走 logger 写，保证格式与生产一致）
        com.tgproxy.data.RoundLogger(file, io = testDispatcher).apply {
            append(com.tgproxy.model.RoundRecord(timestamp = "2026-10-28 01:00:00", success = true, seq = 0))
            append(com.tgproxy.model.RoundRecord(timestamp = "2026-10-28 02:00:00", success = false, seq = 0))
        }

        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply {
            this.gate = gate
            nextText = "socks5://1.2.3.4:1080"
        }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
            roundLogger = com.tgproxy.data.RoundLogger(file, io = testDispatcher),
        )
        try {
            advanceTimeBy(100)
            // 首轮还挂在 gate 上，历史已经先进来了
            assertEquals(2, viewModel.uiState.value.logs.size)
            assertEquals("2026-10-28 02:00:00", viewModel.uiState.value.logs.last().timestamp)
            gate.complete(Unit)
            advanceTimeBy(200)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `倒计时每秒跳动不复制 uiState`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        // 倒计时读的是 FakeTimeSource，不随虚拟时间走 —— 必须手动推时钟，
        // 否则 delay 会按时醒来但剩余秒数一直不变（第一版就是这么写错的，
        // 被下面的阳性对照当场抓住）
        val time = FakeTimeSource()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = time,
        )
        try {
            advanceTimeBy(200) // 跑完一轮，状态稳定
            val snapshot = viewModel.uiState.value
            val secBefore = viewModel.countdown.value.sec
            assertTrue("倒计时应当已经在跑", viewModel.countdown.value.started)

            time.nowNs += 2_000_000_000L // 真实过去 2 秒
            advanceTimeBy(1_100)         // 让下一次 tick 跑到

            // 阳性对照：必须证明倒计时真的跳了，否则下面的「引用没变」是恒真
            val secAfter = viewModel.countdown.value.sec
            assertTrue(
                "倒计时没有推进（$secBefore -> $secAfter），断言会变成恒真",
                secAfter < secBefore,
            )
            // 引用相等 = 期间一次 copy 都没发生
            assertSame(
                "倒计时跳动复制了 uiState，整页（含节点列表）会被带着重组",
                snapshot,
                viewModel.uiState.value,
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `known_good 开关切换会持久化并立即生效`() = runTest(testDispatcher) {
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, FakeTester(), FakeGate(), ioDispatcher = testDispatcher),
            settingsStore = store,
            timeSource = FakeTimeSource(),
        )
        advanceTimeBy(100)

        try {
            viewModel.setKnownGoodEnabled(false)
            advanceTimeBy(100)
            assertEquals(false, store.savedKnownGood)
            assertEquals(false, viewModel.settings.value.knownGoodEnabled)

            viewModel.setKnownGoodEnabled(true)
            advanceTimeBy(100)
            assertEquals(true, store.savedKnownGood)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 进度通道拆分（progress 从 uiState 移出，独立 StateFlow）
    // ------------------------------------------------------------------

    @Test
    fun `进度高频更新不复制 uiState，整页不会被带着重组`() = runTest(testDispatcher) {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        // 计数器是这条用例的**阳性对照**：必须证明进度真的跳了 50 次，
        // 否则「uiState 没变」可能只是因为压根没触发进度（恒真）
        val emissions = AtomicInteger(0)
        val tester = object : LatencyTester {
            override suspend fun testAll(
                entries: List<ProxyEntry>,
                onProgress: (Int, Int) -> Unit,
            ): List<ProxyEntry> {
                onProgress(1, 100)
                emissions.incrementAndGet()
                firstGate.await() // 停在这里，让测试取 uiState 快照
                repeat(50) { i ->
                    onProgress(i + 2, 100)
                    emissions.incrementAndGet()
                }
                secondGate.await()
                return entries.map { it.copy(latencyMs = 100, status = ProxyStatus.AVAILABLE) }
            }
        }
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, tester, FakeGate(), ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
        )
        try {
            advanceTimeBy(100)
            // 停掉倒计时：它每秒也会 copy 一次 uiState，会污染下面的一致性断言
            viewModel.onPause()

            val snapshot = viewModel.uiState.value
            assertTrue("刷新应当已经开始", snapshot.fetching)
            assertEquals(
                "进度没到 TCP 阶段：${viewModel.progress.value}",
                RefreshStage.TCP_TEST,
                viewModel.progress.value?.stage,
            )
            assertEquals(1, emissions.get())

            // 放行 → 连发 50 次进度
            firstGate.complete(Unit)
            advanceTimeBy(100)

            assertEquals("进度没有继续推进，下面的断言会变成恒真", 51, emissions.get())
            assertEquals("进度通道没接上", 51, viewModel.progress.value?.done)
            // 引用相等 = 期间一次 copy 都没发生（值相等不够，值本来就没变）
            assertSame(
                "进度更新复制了 uiState，节点列表/漏斗/耗时卡会被带着重组",
                snapshot,
                viewModel.uiState.value,
            )

            secondGate.complete(Unit)
            advanceTimeBy(100)
            assertNull("刷新结束后进度应清空", viewModel.progress.value)
            assertFalse(viewModel.uiState.value.fetching)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `刷新失败时进度通道也要清空`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val tester = object : LatencyTester {
            override suspend fun testAll(
                entries: List<ProxyEntry>,
                onProgress: (Int, Int) -> Unit,
            ): List<ProxyEntry> {
                onProgress(1, entries.size)
                gate.await()
                throw IOException("tester boom")
            }
        }
        val source = FakeSource().apply { nextText = "socks5://1.2.3.4:1080" }
        val viewModel = MainViewModel(
            repository = ProxyRepository(source, tester, FakeGate(), ioDispatcher = testDispatcher),
            settingsStore = FakeSettingsStore(),
            timeSource = FakeTimeSource(),
        )
        try {
            advanceTimeBy(100)
            viewModel.onPause()
            // 失败前先确认进度确实上报过（否则下面的「清空」断言恒真）
            assertEquals(
                RefreshStage.TCP_TEST,
                viewModel.progress.value?.stage,
            )

            gate.complete(Unit)
            advanceTimeBy(100)

            assertNull("异常路径没清空进度，下一轮开始会闪一下旧阶段", viewModel.progress.value)
            assertFalse(viewModel.uiState.value.fetching)
            assertTrue(
                "失败应写进 error：${viewModel.uiState.value.error}",
                viewModel.uiState.value.error?.contains("tester boom") == true,
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
