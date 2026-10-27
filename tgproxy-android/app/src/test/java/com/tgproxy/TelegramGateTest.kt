package com.tgproxy

import com.tgproxy.data.defaultTelegramGateCaller
import com.tgproxy.data.ProxySocketProvider
import com.tgproxy.data.TelegramGate
import com.tgproxy.data.TlsChannel
import com.tgproxy.data.TlsWrapper
import com.tgproxy.model.TgGateStage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TelegramGate 对拍桌面版 _socks5_connect / test_telegram_via_socks5
 * （字节序逐项一致；假套接字 + 假时钟，不碰网络）。
 */
class TelegramGateTest {

    /** 可编程假 Socket：预置下行字节，收集上行字节。 */
    private class FakeSocket(
        downstream: ByteArray,
    ) : Socket() {
        val upstream = ByteArrayInputStream(downstream)
        val sink = ByteArrayOutputStream()
        var closed = false

        override fun getInputStream(): InputStream = upstream
        override fun getOutputStream(): OutputStream = sink
        override fun close() {
            closed = true
        }

        override fun setSoTimeout(timeout: Int) {
            // 测试不模拟超时中断
        }
    }

    /** 每次读取自动前进 [stepMs] 的时钟：让同步假调用也产生耗时。 */    private fun advancingClock(stepMs: Long): () -> Long {
        var nowNs = 1_000_000L
        return {
            val value = nowNs
            nowNs += stepMs * 1_000_000L
            value
        }
    }

    /** 标准成功闸门脚本：无认证握手 → CONNECT 成功（ATYP=1）。 */
    private fun okScript(): ByteArray {
        val gate1 = byteArrayOf(0x05, 0x00) +
            byteArrayOf(0x05, 0x00, 0x00, 0x01) + ByteArray(4) + byteArrayOf(0x01, 0xBB.toByte())
        return gate1
    }

    /** 第二关 TLS 假通道：回放 HTTP 状态行，收集请求字节。 */
    private class FakeTlsChannel(statusLine: String) : TlsChannel {
        override val input = ByteArrayInputStream(statusLine.toByteArray(Charsets.US_ASCII))
        override val output = ByteArrayOutputStream()
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `第一关 IPv4 目标握手字节序与桌面版一致且返回往返毫秒`() {
        val sockets = mutableListOf<FakeSocket>()
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ ->
                FakeSocket(okScript()).also { sockets.add(it) }
            },
            tlsWrapper = TlsWrapper { _, _, _ -> throw IOException("unexpected") },
            clock = advancingClock(stepMs = 5),
        )

        // 时钟每次读取 +5ms：连接+握手+CONNECT 期间被读取多次，耗时 > 0
        val dcMs = gate.testSocks5TelegramDc("1.2.3.4", 1080, 8_000)

        assertNotNull(dcMs)
        assertTrue("dcMs 应为正数（假时钟步进）", (dcMs ?: -1) > 0)
        // 客户端上行：方法协商 05 01 00 + CONNECT（ATYP=1 + 149.154.167.51:443）
        val expected = byteArrayOf(0x05, 0x01, 0x00) +
            byteArrayOf(0x05, 0x01, 0x00, 0x01) +
            byteArrayOf(149.toByte(), 154.toByte(), 167.toByte(), 51) +
            byteArrayOf(0x01, 0xBB.toByte())
        assertArrayEquals(expected, sockets[0].sink.toByteArray())
        assertTrue(sockets[0].closed)
    }

    @Test
    fun `第二关域名字标走 ATYP=3 且校验 HTTP 状态行`() {
        val sockets = mutableListOf<FakeSocket>()
        val tlsChannels = mutableListOf<FakeTlsChannel>()
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ ->
                FakeSocket(okScript()).also { sockets.add(it) }
            },
            tlsWrapper = TlsWrapper { _, host, _ ->
                assertEquals("desktop.telegram.org", host)
                FakeTlsChannel("HTTP/1.1 200 OK\r\n").also { tlsChannels.add(it) }
            },
            clock = advancingClock(stepMs = 5),
        )

        val webMs = gate.testTelegramWebViaSocks5("1.2.3.4", 1080, 8_000)

        assertNotNull(webMs)
        assertTrue("webMs 应为正数（假时钟步进）", (webMs ?: -1) > 0)
        // CONNECT 目标为域名：05 01 00 03 + len + domain + port(443)
        val domain = "desktop.telegram.org".toByteArray(Charsets.US_ASCII)
        val expected = byteArrayOf(0x05, 0x01, 0x00) +
            byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()) + domain +
            byteArrayOf(0x01, 0xBB.toByte())
        assertArrayEquals(expected, sockets[0].sink.toByteArray())
        // TLS 层发出真实 GET 请求（Host/UA/Connection）
        val request = tlsChannels[0].output.toString(Charsets.US_ASCII)
        assertTrue(request.startsWith("GET / HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: desktop.telegram.org\r\n"))
        assertTrue(request.contains("Connection: close\r\n"))
        assertTrue(tlsChannels[0].closed)
    }

    @Test
    fun `两关通过时 test 返回第一关毫秒`() {
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ -> FakeTlsChannel("HTTP/1.1 200 OK\r\n") },
            clock = advancingClock(stepMs = 5),
        )

        val result = gate.test("1.2.3.4", 1080, 8_000, 20_000)

        assertNotNull(result)
        assertTrue("test 返回第一关毫秒（正数）", (result ?: -1) > 0)
    }

    @Test
    fun `握手被拒（方法字节非 00）返回 null`() {
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ ->
                FakeSocket(byteArrayOf(0x05, 0x01))
            },
            tlsWrapper = TlsWrapper { _, _, _ -> throw IOException("unexpected") },
            clock = { 0L },
        )

        assertNull(gate.testSocks5TelegramDc("1.2.3.4", 1080, 8_000))
    }

    @Test
    fun `CONNECT 返回非零错误码返回 null`() {
        val script = byteArrayOf(0x05, 0x00) +
            byteArrayOf(0x05, 0x01, 0x00, 0x01) + ByteArray(4) + byteArrayOf(0x01, 0xBB.toByte())
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(script) },
            tlsWrapper = TlsWrapper { _, _, _ -> throw IOException("unexpected") },
            clock = { 0L },
        )

        assertNull(gate.testSocks5TelegramDc("1.2.3.4", 1080, 8_000))
    }

    @Test
    fun `下行 EOF 中断握手返回 null`() {
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(ByteArray(0)) },
            tlsWrapper = TlsWrapper { _, _, _ -> throw IOException("unexpected") },
            clock = { 0L },
        )

        assertNull(gate.testSocks5TelegramDc("1.2.3.4", 1080, 8_000))
    }

    @Test
    fun `第二关响应非 HTTP 状态行返回 null`() {
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ -> FakeTlsChannel("XYZ garbage") },
            clock = { 0L },
        )

        assertNull(gate.testTelegramWebViaSocks5("1.2.3.4", 1080, 8_000))
    }

    @Test
    fun `连接代理失败返回 null`() {
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> throw IOException("refused") },
            tlsWrapper = TlsWrapper { _, _, _ -> throw IOException("unexpected") },
            clock = { 0L },
        )

        assertNull(gate.testSocks5TelegramDc("1.2.3.4", 1080, 8_000))
    }

    @Test
    fun `第一关通过但剩余预算不足 1 秒时不再发起第二关`() {
        var tlsCalls = 0
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ ->
                tlsCalls++
                throw IOException("should not be called")
            },
            // 每次读时钟 +50ms：第一关耗时即 50ms
            clock = advancingClock(stepMs = 50),
        )

        // 第一关 50ms + deadline 1000ms → 剩余 950ms ≤ 1000ms 门槛 → 放弃第二关
        assertNull(gate.test("1.2.3.4", 1080, 8_000, 1_000))
        assertEquals(0, tlsCalls)
    }

    // ========================================================================
    // 剪枝（2026-10-28）：dcMs 超门槛时跳过第二关
    // ========================================================================

    @Test
    fun `剪枝判定边界：等于门槛不剪、超过才剪、不传门槛不剪`() {
        assertFalse(TelegramGate.shouldPrune(1_000, 1_000))   // 等于门槛 → 保留
        assertTrue(TelegramGate.shouldPrune(1_001, 1_000))
        assertTrue(TelegramGate.shouldPrune(60_000, 1_000))
        assertFalse(TelegramGate.shouldPrune(5_000, null))     // 不传门槛 → 旧行为
        assertFalse(TelegramGate.shouldPrune(0, 1_000))
    }

    @Test
    fun `第一关超门槛时跳过第二关并返回 dcMs`() {
        var tlsCalls = 0
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ ->
                tlsCalls++
                throw IOException("剪枝后不该走到第二关")
            },
            // 每次读时钟 +1500ms：第一关往返必然远超 100ms 门槛
            clock = advancingClock(stepMs = 1_500),
        )

        val result = gate.test("1.2.3.4", 1080, maxLatencyMs = 100)

        // 返回 dcMs 而不是 null：上层据此归入「超门槛」，不误增连败
        assertNotNull(result)
        assertTrue(result!! > 100)
        assertEquals("剪枝后不该调用第二关", 0, tlsCalls)
    }

    @Test
    fun `第一关未超门槛时正常跑第二关`() {
        var tlsCalls = 0
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ ->
                tlsCalls++
                FakeTlsChannel("HTTP/1.1 200 OK\r\n")
            },
            clock = advancingClock(stepMs = 1),
        )

        val result = gate.test("1.2.3.4", 1080, maxLatencyMs = 60_000)

        assertNotNull(result)
        assertEquals("未超门槛必须继续跑第二关", 1, tlsCalls)
    }

    @Test
    fun `不传门槛时保持旧行为（始终跑第二关）`() {
        var tlsCalls = 0
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ ->
                tlsCalls++
                FakeTlsChannel("HTTP/1.1 200 OK\r\n")
            },
            clock = advancingClock(stepMs = 1_500),   // 第一关很慢
        )

        val result = gate.test("1.2.3.4", 1080)      // 不传 maxLatencyMs

        assertNotNull(result)
        assertEquals("不传门槛不该剪枝", 1, tlsCalls)
    }

    // ========================================================================
    // 落点分类（两关漏斗的四个桶）
    // ========================================================================

    @Test
    fun `落点分类：第一关连不上为 DC_FAIL`() {
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> throw IOException("refused") },
            tlsWrapper = TlsWrapper { _, _, _ -> throw IOException("unexpected") },
        )
        assertEquals(TgGateStage.DC_FAIL, gate.testDetailed("1.2.3.4", 1080, 1_000).stage)
    }

    @Test
    fun `落点分类：第一关超门槛为 LATENCY_REJECTED 且不跑第二关`() {
        var tlsCalls = 0
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ ->
                tlsCalls++
                throw IOException("剪枝后不该走到第二关")
            },
            clock = advancingClock(stepMs = 1_500),
        )

        val outcome = gate.testDetailed("1.2.3.4", 1080, maxLatencyMs = 100)

        assertEquals(TgGateStage.LATENCY_REJECTED, outcome.stage)
        assertNotNull(outcome.latencyMs)
        assertEquals("剪枝后不该调用第二关", 0, tlsCalls)
    }

    @Test
    fun `落点分类：第二关失败为 WEB_FAIL`() {
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ -> FakeTlsChannel("XYZ garbage") },
            clock = advancingClock(stepMs = 1),
        )
        assertEquals(TgGateStage.WEB_FAIL, gate.testDetailed("1.2.3.4", 1080, 60_000).stage)
    }

    @Test
    fun `落点分类：两关全过为 OK`() {
        val gate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ -> FakeTlsChannel("HTTP/1.1 200 OK\r\n") },
            clock = advancingClock(stepMs = 1),
        )
        val outcome = gate.testDetailed("1.2.3.4", 1080, 60_000)
        assertEquals(TgGateStage.OK, outcome.stage)
        assertNotNull(outcome.latencyMs)
    }

    @Test
    fun `生产包装器把落点透传（不吞掉阶段信息）`() {
        // 接口的 testDetailed 有默认粗分类（非空→OK、null→DC_FAIL）。若包装器
        // 忘了覆写，「站点失败」会被错算成「连接失败」，漏斗就撒谎了。
        // 用可注入的假闸门把这条接错抓出来：第二关失败必须是 WEB_FAIL。
        val fakeGate = TelegramGate(
            socketProvider = ProxySocketProvider { _, _, _ -> FakeSocket(okScript()) },
            tlsWrapper = TlsWrapper { _, _, _ -> FakeTlsChannel("XYZ garbage") },
            clock = advancingClock(stepMs = 1),
        )
        val caller = defaultTelegramGateCaller(fakeGate)

        val outcome = caller.testDetailed("1.2.3.4", 1080, 60_000)

        assertEquals(
            "包装器没覆写 testDetailed，落点被粗分类吞掉了",
            TgGateStage.WEB_FAIL, outcome.stage,
        )
    }
}
