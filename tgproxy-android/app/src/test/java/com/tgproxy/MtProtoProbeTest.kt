package com.tgproxy

import com.tgproxy.data.MtProtoCode
import com.tgproxy.data.MtProtoHandshake
import com.tgproxy.data.MtProtoProbe
import com.tgproxy.data.ProxySocketProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MTProto 探针的行为测试。
 *
 * 假服务端**真的按协议回包**：它从客户端写入的字节里解出 init、用同一套
 * obfuscated2 约定派生密钥、解开请求帧、取出 nonce、再回一个加密 resPQ。
 * 这样测的是「整条链路是否自洽」，而不是「函数被调用了几次」。
 *
 * 字节级正确性另由 [MtProtoHandshakeTest] 的桌面版黄金向量保证 —— 本类不重复。
 */
class MtProtoProbeTest {

    private val secret = MtProtoHandshakeTest.unhex("0123456789abcdef0123456789abcdef")
    private val secretText = "0123456789abcdef0123456789abcdef"

    // -----------------------------------------------------------------------
    // 成功路径

    @Test
    fun `真握手成功时返回 OK 与 RTT`() {
        val server = FakeServer(secret)
        val probe = probe(SocketProviderReturning(server))

        val result = probe.probe("1.2.3.4", 443, secretText, timeoutMs = 2_000)

        assertEquals(result.detail, MtProtoCode.OK, result.code)
        assertNotNull(result.latencyMs)
        assertTrue(result.latencyMs!! > 0)
        assertTrue(result.detail.contains("server_nonce"))
    }

    @Test
    fun `发出的握手头是合法 obfuscated2 且 dc 与协议字正确`() {
        val server = FakeServer(secret)
        probe(SocketProviderReturning(server)).probe("1.2.3.4", 443, secretText, 2_000)

        val sent = server.sink.toByteArray()
        assertTrue("至少要先发 64 字节头", sent.size > 64)
        val first4 = sent.copyOfRange(0, 4)
        // 首 4 字节不能是 HTTP 动词或已知协议字（否则中间设备会改写）
        assertFalse(first4.contentEquals("GET ".toByteArray()))
        assertFalse(first4.contentEquals("POST".toByteArray()))
        assertFalse(first4.contentEquals(byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())))
        // 末 8 字节的明文是 dddddddd + dc + 000000（服务端已解密校验）
        assertEquals(MtProtoHandshake.PROTO_SECURE, server.observedProto.toInt() and 0xFF)
        assertEquals(2, server.observedDc)
        assertNotNull(server.observedNonce)
    }

    // -----------------------------------------------------------------------
    // 假节点 / 劫持形态

    @Test
    fun `对端回错 nonce 判 PROTOCOL`() {
        val server = FakeServer(secret, nonceOverride = ByteArray(16) { 0x7E })
        val result = probe(SocketProviderReturning(server)).probe("1.2.3.4", 443, secretText, 2_000)
        assertEquals(MtProtoCode.PROTOCOL, result.code)
        assertNull(result.latencyMs)
    }

    @Test
    fun `constructor 不对判 PROTOCOL`() {
        val server = FakeServer(secret, constructor = 0x0BADF00D)
        val result = probe(SocketProviderReturning(server)).probe("1.2.3.4", 443, secretText, 2_000)
        assertEquals(MtProtoCode.PROTOCOL, result.code)
    }

    @Test
    fun `对端立刻关闭连接判 NETWORK`() {
        val server = FakeServer(secret, closeImmediately = true)
        val result = probe(SocketProviderReturning(server)).probe("1.2.3.4", 443, secretText, 2_000)
        assertEquals(MtProtoCode.NETWORK, result.code)
    }

    @Test
    fun `读取超时判 TIMEOUT`() {
        val server = FakeServer(secret, readTimeout = true)
        val result = probe(SocketProviderReturning(server)).probe("1.2.3.4", 443, secretText, 2_000)
        assertEquals(MtProtoCode.TIMEOUT, result.code)
    }

    @Test
    fun `连接失败判 CONNECT_FAIL`() {
        val probe = probe(ProxySocketProvider { _, _, _ -> throw IOException("connection refused") })
        val result = probe.probe("1.2.3.4", 443, secretText, 2_000)
        assertEquals(MtProtoCode.CONNECT_FAIL, result.code)
    }

    @Test
    fun `帧长度非法判 PROTOCOL 而不是傻读 2MB`() {
        val server = FakeServer(secret, frameLengthOverride = 0x7FFFFFFF)
        val result = probe(SocketProviderReturning(server)).probe("1.2.3.4", 443, secretText, 2_000)
        assertEquals(MtProtoCode.PROTOCOL, result.code)
    }

    // -----------------------------------------------------------------------
    // secret 层面：无法判定 ≠ 失败

    @Test
    fun `链接里没有 secret 时不发起连接且判 SECRET_EMPTY`() {
        var connected = false
        val probe = probe(
            ProxySocketProvider { _, _, _ ->
                connected = true
                throw IOException("不该被调用")
            },
        )
        val result = probe.probe("1.2.3.4", 443, null, 2_000)
        assertEquals(MtProtoCode.SECRET_EMPTY, result.code)
        assertFalse("缺 secret 时不该浪费一次连接", connected)
    }

    @Test
    fun `ee secret 判 SECRET_UNSUPPORTED`() {
        val result = probe(SocketProviderReturning(FakeServer(secret)))
            .probe("1.2.3.4", 443, "ee" + "11".repeat(16), 2_000)
        assertEquals(MtProtoCode.SECRET_UNSUPPORTED, result.code)
    }

    @Test
    fun `格式非法的 secret 判 SECRET_BAD`() {
        val result = probe(SocketProviderReturning(FakeServer(secret)))
            .probe("1.2.3.4", 443, "0123", 2_000)
        assertEquals(MtProtoCode.SECRET_BAD, result.code)
    }

    // -----------------------------------------------------------------------

    /** 每次 clock() 前进 5ms 的时钟：让 RTT 有确定的正值。 */
    private fun probe(provider: ProxySocketProvider): MtProtoProbe {
        var nanos = 1_000_000_000L
        return MtProtoProbe(
            socketProvider = provider,
            clock = {
                val now = nanos
                nanos += 5_000_000L
                now
            },
            random = { size -> ByteArray(size) { i -> ((i * 7 + 3) and 0xFF).toByte() } },
        )
    }

    private class SocketProviderReturning(private val socket: Socket) : ProxySocketProvider {
        var calls = 0
        override fun connect(host: String, port: Int, timeoutMs: Int): Socket {
            calls++
            return socket
        }
    }

    /**
     * 按协议回包的假服务端。
     *
     * 客户端是同步写入后再读，所以第一次 read 时 sink 里已经是完整的
     * 「init + 请求帧」——可以就地解出 nonce 再算响应，不需要真线程。
     */
    private class FakeServer(
        val secret: ByteArray,
        private val nonceOverride: ByteArray? = null,
        private val constructor: Int = MtProtoHandshake.RES_PQ,
        private val closeImmediately: Boolean = false,
        private val readTimeout: Boolean = false,
        private val frameLengthOverride: Int? = null,
    ) : Socket() {
        val sink = ByteArrayOutputStream()
        var observedProto: Byte = 0
        var observedDc: Int = -1
        var observedNonce: ByteArray? = null

        private var response: ByteArray? = null

        override fun getOutputStream(): OutputStream = sink

        private var cachedStream: InputStream? = null

        override fun getInputStream(): InputStream {
            cachedStream?.let { return it }
            val created = createStream()
            cachedStream = created
            return created
        }

        private fun createStream(): InputStream {
            if (readTimeout) {
                return object : InputStream() {
                    override fun read(): Int = throw SocketTimeoutException("fake timeout")
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        throw SocketTimeoutException("fake timeout")
                }
            }
            if (closeImmediately) return ByteArrayInputStream(ByteArray(0))
            val payload = response ?: buildResponse().also { response = it }
            return ByteArrayInputStream(payload)
        }

        override fun setSoTimeout(timeout: Int) {
            // 假套接字不模拟超时中断
        }

        override fun close() = Unit

        fun responseBytes(): ByteArray = response ?: ByteArray(0)

        private fun buildResponse(): ByteArray {
            val data = sink.toByteArray()
            check(data.size >= 64) { "客户端还没写完握手头" }
            val init = data.copyOfRange(0, 64)
            val keys = MtProtoHandshake.deriveKeys(init, secret)
            val serverEnc = MtProtoHandshake.CtrStream(keys.encKey, keys.encIv)
            // 前 56 字节明文 + 末 8 字节密文，XOR 一遍得到完整明文头，
            // 顺带把 enc 流推进到 64 字节（与客户端一致）
            val plainInit = serverEnc.update(init)
            observedProto = plainInit[56]
            observedDc = plainInit[60].toInt() and 0xFF

            // 解开客户端帧 → 取 nonce（客户端用 enc 流加密，服务端用同一把钥匙解）
            val plainFrame = serverEnc.update(data.copyOfRange(64, data.size))
            val clientFrameLen = MtProtoHandshake.le32At(plainFrame, 0)
            val packet = plainFrame.copyOfRange(4, 4 + clientFrameLen)
            observedNonce = packet.copyOfRange(24, 40)

            val nonce = nonceOverride ?: observedNonce!!
            val body = MtProtoHandshake.le32(constructor) + nonce +
                ByteArray(16) { 0x11 } + ByteArray(16) { 0x22 } + ByteArray(4)
            val reply = packetFrame(body)
            val lengthPrefix = MtProtoHandshake.le32(
                frameLengthOverride ?: (reply.size - 4),
            )
            // 发方向用反向密钥流，且从 0 开始（服务端从不发 init 头）
            val serverOut = MtProtoHandshake.CtrStream(keys.decKey, keys.decIv)
            return serverOut.update(lengthPrefix + reply.copyOfRange(4, reply.size))
        }

        private fun packetFrame(body: ByteArray): ByteArray {
            val packet = ByteArray(8) + MtProtoHandshake.le64(0x1122334455667788L) +
                MtProtoHandshake.le32(body.size) + body
            return MtProtoHandshake.le32(packet.size) + packet
        }
    }
}
