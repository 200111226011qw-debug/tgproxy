package com.tgproxy

import com.tgproxy.data.MtProtoHandshake
import com.tgproxy.data.MtProtoProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * obfuscated2 协议核心的对拍测试。
 *
 * **黄金向量来自桌面版 Python 实现**（`mtproto_handshake.py`，其自身对齐
 * mtproxychecker 的 protocol.py）：同一份 init / secret / nonce 下，四种密钥、
 * 加密后的 init 尾、加密后的请求帧、服务端应回的响应字节全部是固定值。
 *
 * 这是两端唯一可靠的一致性证据 —— 只做「自己加密自己解密」的往返测试，
 * 两个方向同时错（例如反向切片写成顺序）也会全绿，而对端会直接断链。
 */
class MtProtoHandshakeTest {

    private val secret = unhex("0123456789abcdef0123456789abcdef")

    /** 桌面版脚本里用的确定随机块：random[i] = (i*7+3) & 0xFF。 */
    private val random64 = ByteArray(64) { i -> ((i * 7 + 3) and 0xFF).toByte() }

    private val initPlain = unhex(
        "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b22" +
            "2930373e454c535a61686f767d84dddddddd02000000",
    )

    @Test
    fun `派生密钥与桌面版逐字节一致`() {
        val keys = MtProtoHandshake.deriveKeys(initPlain, secret)
        assertEquals("59e433cc0a8cc5c652740f253c73cdfcada959522294e969895a52cd513936e1", hex(keys.encKey))
        assertEquals("1b222930373e454c535a61686f767d84", hex(keys.encIv))
        assertEquals("fc67ee398be3b264e8d2c4db7863cc7bbff64508f58d1f84c5b93e45430b7ee9", hex(keys.decKey))
        assertEquals("a49d968f88817a736c655e575049423b", hex(keys.decIv))
    }

    @Test
    fun `握手头只加密末 8 字节但状态按 64 字节推进`() {
        val built = MtProtoHandshake.buildInit(random64, secret, dcId = 2)

        // 前 56 字节明文原样发出：中间设备看到的是「看起来随机的普通流量」
        assertArrayEquals(initPlain.copyOfRange(0, 56), built.init.copyOfRange(0, 56))
        // 末 8 字节是 dddddddd + dc + 000000 的密文
        assertEquals("c85fc1da04a1758a", hex(built.init.copyOfRange(56, 64)))
    }

    @Test
    fun `req_pq_multi 报文与桌面版一致`() {
        val nonce = unhex("00112233445566778899aabbccddeeff")
        val packet = MtProtoHandshake.buildReqPqMulti(nonce, 0x0123456789ABCDE0L)
        assertEquals(
            "0000000000000000e0cdab896745230114000000f18e7ebe00112233445566778899aabbccddeeff",
            hex(packet),
        )
    }

    @Test
    fun `加密后的请求帧与桌面版一致（含随机填充）`() {
        val built = MtProtoHandshake.buildInit(random64, secret, dcId = 2)
        val nonce = unhex("00112233445566778899aabbccddeeff")
        val packet = MtProtoHandshake.buildReqPqMulti(nonce, 0x0123456789ABCDE0L)
        val framed = MtProtoHandshake.frameSecure(packet, byteArrayOf(0x02, 0x00))
        assertEquals(
            "ec650d6124749b0d4b16680b350044d1645a9fbd99d3bef8580560d99daabb0ff17d50334a11883aa3f909788f64",
            hex(built.enc.update(framed)),
        )
    }

    @Test
    fun `能解开桌面版生成的服务端响应并校验 nonce`() {
        val built = MtProtoHandshake.buildInit(random64, secret, dcId = 2)
        val nonce = unhex("00112233445566778899aabbccddeeff")
        // 桌面版用它自己的 dec 流加密出的响应（等价真实服务端回包）
        // 注意方向：obfuscated2 里只有「客户端→服务端」的流要推进 64 字节（init 头），
        // 服务端→客户端的流从 0 开始 —— 桌面版 read_frame 直接用未推进的 dec，这里同口径。
        val wire = unhex(
            "0fa99683179c157327e17c7bced529833860b94e3bce72a112d698e61a0eab7dd50bc82a2b678a33" +
                "77a94ff790a0ef144807b2b3b1f4713d7a5123f3b5d3bbff68bd7a5c2dae04d2fcfc42a330fbb1c6",
        )
        val decrypted = built.dec.update(wire)
        // read_frame 的语义：4 字节长度前缀先被解出来，剩下的才是「整包」
        val frame = decrypted.copyOfRange(4, decrypted.size)
        val serverNonce = MtProtoHandshake.parseResPq(frame, nonce)
        assertEquals("11111111111111111111111111111111", hex(serverNonce))
    }

    // -----------------------------------------------------------------------
    // resPQ 校验：每一条都是「假节点/劫持」会踩到的形态

    @Test
    fun `nonce 不回显的响应必须判失败`() {
        val frame = respFrame(nonce = ByteArray(16) { 0x5A }, constructor = MtProtoHandshake.RES_PQ)
        val error = assertThrows(MtProtoProtocolException::class.java) {
            MtProtoHandshake.parseResPq(frame, ByteArray(16) { 0x11 })
        }
        assertTrue(error.message!!.contains("nonce"))
    }

    @Test
    fun `constructor 不是 resPQ 的响应必须判失败`() {
        val nonce = ByteArray(16) { 0x11 }
        val frame = respFrame(nonce = nonce, constructor = 0x12345678)
        val error = assertThrows(MtProtoProtocolException::class.java) {
            MtProtoHandshake.parseResPq(frame, nonce)
        }
        assertTrue(error.message!!.contains("resPQ"))
    }

    @Test
    fun `auth_key_id 非零（已加密包）必须判失败`() {
        val nonce = ByteArray(16) { 0x11 }
        val frame = respFrame(nonce, MtProtoHandshake.RES_PQ)
        frame[0] = 0x01
        assertThrows(MtProtoProtocolException::class.java) {
            MtProtoHandshake.parseResPq(frame, nonce)
        }
    }

    @Test
    fun `消息长度越界必须判失败`() {
        val nonce = ByteArray(16) { 0x11 }
        val frame = respFrame(nonce, MtProtoHandshake.RES_PQ)
        // 把 msg_len 改成远超实际帧长
        val oversize = MtProtoHandshake.le32(9_999)
        oversize.copyInto(frame, 16)
        assertThrows(MtProtoProtocolException::class.java) {
            MtProtoHandshake.parseResPq(frame, nonce)
        }
    }

    @Test
    fun `过短的帧必须判失败`() {
        assertThrows(MtProtoProtocolException::class.java) {
            MtProtoHandshake.parseResPq(ByteArray(20), ByteArray(16) { 0x11 })
        }
    }

    // -----------------------------------------------------------------------
    // secret 解析：与桌面版 parse_secret 的分支顺序对齐

    @Test
    fun `裸 32 位 hex 解析为 16 字节`() {
        assertArrayEquals(secret, MtProtoHandshake.parseSecret("0123456789ABCDEF0123456789abcdef"))
    }

    @Test
    fun `dd 前缀取其后 16 字节`() {
        val parsed = MtProtoHandshake.parseSecret("dd0123456789abcdef0123456789abcdef")
        assertArrayEquals(secret, parsed)
    }

    @Test
    fun `超长 hex 截断为前 16 字节`() {
        val parsed = MtProtoHandshake.parseSecret("0123456789abcdef0123456789abcdefdeadbeef")
        assertArrayEquals(secret, parsed)
    }

    @Test
    fun `ee 前缀明确报不支持而不是当作 raw 硬连`() {
        val error = assertThrows(MtProtoHandshake.SecretException::class.java) {
            MtProtoHandshake.parseSecret("ee0123456789abcdef0123456789abcdef")
        }
        assertEquals(MtProtoHandshake.SecretError.UNSUPPORTED_FAKETLS, error.error)
    }

    @Test
    fun `空 secret 与非法 secret 分开报错`() {
        assertEquals(
            MtProtoHandshake.SecretError.EMPTY,
            assertThrows(MtProtoHandshake.SecretException::class.java) {
                MtProtoHandshake.parseSecret("   ")
            }.error,
        )
        assertEquals(
            MtProtoHandshake.SecretError.BAD,
            assertThrows(MtProtoHandshake.SecretException::class.java) {
                MtProtoHandshake.parseSecret("0123")
            }.error,
        )
    }

    @Test
    fun `base64 形式也能解析`() {
        // MDEyMzQ1Njc4OWFiY2RlZg== = ASCI "0123456789abcdef"
        assertArrayEquals(
            "0123456789abcdef".toByteArray(),
            MtProtoHandshake.parseSecret("MDEyMzQ1Njc4OWFiY2RlZg=="),
        )
        // 3QABAgMEBQYHCAkKCwwNDg8= = 0xDD + 00..0f
        assertArrayEquals(
            ByteArray(16) { it.toByte() },
            MtProtoHandshake.parseSecret("3QABAgMEBQYHCAkKCwwNDg8="),
        )
    }

    @Test
    fun `从分享链接取 secret`() {
        assertEquals(
            "dd0123",
            MtProtoHandshake.secretFromLink("tg://proxy?server=1.2.3.4&port=443&secret=dd0123"),
        )
        assertEquals(
            "dd0456",
            MtProtoHandshake.secretFromLink("https://t.me/proxy?server=1.2.3.4&port=443&secret=dd0456"),
        )
        assertNull(MtProtoHandshake.secretFromLink("tg://proxy?server=1.2.3.4&port=443"))
        assertNull(MtProtoHandshake.secretFromLink("socks5://1.2.3.4:1080"))
    }

    // -----------------------------------------------------------------------

    private fun respFrame(nonce: ByteArray, constructor: Int): ByteArray {
        val body = MtProtoHandshake.le32(constructor) + nonce +
            ByteArray(16) { 0x22 } + ByteArray(16) { 0x33 } + ByteArray(4)
        return ByteArray(8) + MtProtoHandshake.le64(0x1122334455667788L) +
            MtProtoHandshake.le32(body.size) + body
    }

    companion object {
        /** hex 文本 → 字节（测试向量用）。 */
        fun unhex(text: String): ByteArray {
            require(text.length % 2 == 0) { "hex 长度必须为偶数" }
            return ByteArray(text.length / 2) { i ->
                text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /** 字节 → 小写 hex（断言消息里可直接对拍）。 */
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

