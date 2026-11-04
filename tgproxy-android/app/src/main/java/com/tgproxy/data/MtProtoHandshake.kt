package com.tgproxy.data

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64

/**
 * MTProto obfuscated2 协议核心（与桌面版 `mtproto_handshake.py` 字节级一致）。
 *
 * 为什么需要它：安卓版的 TG 实测第一关只是「经代理 CONNECT 到 DC 的 TCP 往返」，
 * 端口能连上就算过。但「端口开着」不等于「对端是 Telegram 出口」—— 透明代理、
 * 劫持设备、随便一个 acceptance server 都能让 TCP 握手成功。obfuscated2 + req_pq_multi
 * 要求对端**回一个带我们随机 nonce 的 resPQ**，这才证明隧道尽头真的有 Telegram。
 *
 * 密码学边界（与桌面版同样刻意）：AES-256-CTR 与 SHA-256 只调用平台实现，
 * 不手写。`AES/CTR/NoPadding` 的 128 位大端计数器与 OpenSSL（Python `cryptography`）
 * 一致，因此两端的密钥流可以逐字节对拍。
 *
 * 本类为纯 Kotlin 对象（无 Android 依赖），可在 JVM 单测中直接断言字节。
 */
object MtProtoHandshake {

    /** 内层传输模式：`dddddddd` = secure（随机填充），与桌面版 INNER_MODE 一致。 */
    const val PROTO_SECURE = 0xDD

    /** `req_pq_multi` constructor。 */
    const val REQ_PQ_MULTI = 0xBE7E8EF1.toInt()

    /** `resPQ` constructor。 */
    const val RES_PQ = 0x05162463

    /** 单帧上限（桌面版 MAX_FRAME = 2 MiB）：超过即判定为对端在灌垃圾。 */
    const val MAX_FRAME = 2 * 1024 * 1024

    /** 握手头长度。 */
    const val INIT_LEN = 64

    /** secret 固定长度。 */
    const val SECRET_LEN = 16

    /** resPQ 里我们需要的部分：constructor(4) + nonce(16)。 */
    private const val MIN_BODY = 36

    // -----------------------------------------------------------------------
    // 密码学适配层

    /**
     * AES-256-CTR 流（状态随 update 前进）。
     *
     * 与 `Cipher.update` 一一对应：调用方必须**按协议顺序**喂完整字节，
     * 漏喂或多喂都会让后续密钥流错位 —— obfuscated2 的 init 头正好是
     * 「发出 8 字节、却要推进 64 字节」的典型陷阱。
     */
    class CtrStream(key: ByteArray, iv: ByteArray) {
        private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv),
            )
        }

        fun update(data: ByteArray): ByteArray = cipher.update(data) ?: ByteArray(0)
    }

    /** 四个方向密钥（obfuscated2 的对称约定）。 */
    data class Keys(
        val encKey: ByteArray,
        val encIv: ByteArray,
        val decKey: ByteArray,
        val decIv: ByteArray,
    ) {
        // data class 的 equals 对 ByteArray 是引用比较，测试里会踩；显式覆盖。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Keys) return false
            return encKey.contentEquals(other.encKey) &&
                encIv.contentEquals(other.encIv) &&
                decKey.contentEquals(other.decKey) &&
                decIv.contentEquals(other.decIv)
        }

        override fun hashCode(): Int {
            var result = encKey.contentHashCode()
            result = 31 * result + encIv.contentHashCode()
            result = 31 * result + decKey.contentHashCode()
            result = 31 * result + decIv.contentHashCode()
            return result
        }
    }

    /** 握手头 + 两个已初始化且已推进 64 字节的流。 */
    class BuiltInit(
        /** 待发送的 64 字节（前 56 字节明文，后 8 字节已加密）。 */
        val init: ByteArray,
        val enc: CtrStream,
        val dec: CtrStream,
    )

    /**
     * 从 64 字节 init 派生四个密钥。
     *
     * 解密侧刻意用**反向切片**（`init[55..24]` 倒序 / `init[23..8]` 倒序）：
     * 这是 obfuscated2 的对称约定，不是笔误。用顺序切片两端都能跑，
     * 但对端（真实 Telegram）会认为密钥不匹配而直接断链。
     */
    fun deriveKeys(init: ByteArray, secret: ByteArray): Keys {
        require(init.size == INIT_LEN) { "init 必须 64 字节，得到 ${init.size}" }
        require(secret.size == SECRET_LEN) { "secret 必须 16 字节，得到 ${secret.size}" }

        val encKey = sha256(init.copyOfRange(8, 40) + secret)
        val encIv = init.copyOfRange(40, 56)
        val decKey = sha256(reversed(init, 55, 24) + secret)
        val decIv = reversed(init, 23, 8)
        return Keys(encKey, encIv, decKey, decIv)
    }

    /**
     * 构造 obfuscated2 头。
     *
     * [random64] 由调用方提供（生产用 SecureRandom），以便测试给出确定字节。
     * 只有 `init[56..64]` 加密后发出，但 cipher 状态要按**完整 64 字节**推进，
     * 否则后续帧的密钥流会错位 56 字节 —— 这个 bug 在自测里表现为
     * 「对端一直不回包」，很难看出来。
     */
    fun buildInit(random64: ByteArray, secret: ByteArray, dcId: Int): BuiltInit {
        require(random64.size == INIT_LEN) { "random64 必须 64 字节，得到 ${random64.size}" }
        val init = random64.copyOf()
        init[56] = PROTO_SECURE.toByte()
        init[57] = PROTO_SECURE.toByte()
        init[58] = PROTO_SECURE.toByte()
        init[59] = PROTO_SECURE.toByte()
        init[60] = (dcId and 0xFF).toByte()
        init[61] = 0
        init[62] = 0
        init[63] = 0

        val keys = deriveKeys(init, secret)
        val enc = CtrStream(keys.encKey, keys.encIv)
        val dec = CtrStream(keys.decKey, keys.decIv)

        val stream = enc.update(init.copyOf())
        val wire = init.copyOf()
        for (i in 56 until INIT_LEN) wire[i] = stream[i]
        return BuiltInit(wire, enc, dec)
    }

    /**
     * 未鉴权 `req_pq_multi`：`auth_key_id=0 + msg_id + msg_len + constructor + nonce`。
     *
     * auth_key_id 为 0 是「未加密消息」的标记，服务端据此走 DH 协商分支。
     */
    fun buildReqPqMulti(nonce: ByteArray, msgId: Long): ByteArray {
        require(nonce.size == 16) { "nonce 必须 16 字节，得到 ${nonce.size}" }
        val payload = le32(REQ_PQ_MULTI) + nonce
        return ByteArray(8) + le64(msgId) + le32(payload.size) + payload
    }

    /** secure 模式封帧：`len(4, 含填充) + payload + padding`。 */
    fun frameSecure(payload: ByteArray, padding: ByteArray): ByteArray =
        le32(payload.size + padding.size) + payload + padding

    /**
     * 解析 `resPQ`，返回 server_nonce。
     *
     * 判定顺序与桌面版一致，且**任何一步不合规都抛**：假节点最典型的回包是
     * 「连接立刻关闭」或「回一堆随机字节」，宽松解析会把它们当成成功。
     *
     * @param frame 已解密的整帧（含 8 字节 auth_key_id / 8 字节 msg_id / 4 字节长度）
     */
    @Throws(MtProtoProtocolException::class)
    fun parseResPq(frame: ByteArray, expectedNonce: ByteArray): ByteArray {
        if (frame.size < 40) {
            throw MtProtoProtocolException("resPQ 过短: ${frame.size} 字节")
        }
        for (i in 0 until 8) {
            if (frame[i] != 0.toByte()) {
                throw MtProtoProtocolException("回包不是未加密 MTProto 包（auth_key_id 非 0）")
            }
        }
        val msgLen = le32At(frame, 16)
        if (msgLen <= 0 || msgLen > frame.size - 20) {
            throw MtProtoProtocolException("MTProto 消息长度非法: $msgLen（帧长 ${frame.size}）")
        }
        if (msgLen < MIN_BODY) {
            throw MtProtoProtocolException("MTProto body 过短: $msgLen 字节")
        }
        val body = frame.copyOfRange(20, 20 + msgLen)
        val constructor = le32At(body, 0)
        if (constructor != RES_PQ) {
            throw MtProtoProtocolException("constructor 不是 resPQ: 0x%08x".format(constructor))
        }
        val echoed = body.copyOfRange(4, 20)
        if (!echoed.contentEquals(expectedNonce)) {
            throw MtProtoProtocolException("resPQ 未回显 nonce（疑似假节点或 secret 不匹配）")
        }
        return body.copyOfRange(20, 36)
    }

    // -----------------------------------------------------------------------
    // secret 解析

    /** secret 解析失败的原因（决定「未验证」还是「配置错误」）。 */
    enum class SecretError { EMPTY, BAD, UNSUPPORTED_FAKETLS }

    class SecretException(val error: SecretError, message: String) : Exception(message)

    /**
     * 解析 `tg://proxy?secret=` 的取值：raw 16 字节 / `dd` + 32 hex / base64。
     *
     * `ee`（FakeTLS）**明确不支持**：桌面版同样留到 Phase 1b。这里宁可报
     * 「未验证」也不拿错算法去连 —— 用错算法只会得到假的失败结论。
     * 规则与桌面版 `parse_secret` 逐条对齐（含 `ee` 优先于 raw hex 的顺序）。
     */
    @Throws(SecretException::class)
    fun parseSecret(text: String?): ByteArray {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            throw SecretException(SecretError.EMPTY, "secret 为空")
        }
        val lower = trimmed.lowercase()
        val hex = hexOrNull(lower)
        if (hex != null) {
            // hex 分支：`ee` / `dd` 前缀优先于「当成裸 16 字节」—— 与桌面版顺序一致。
            if (lower.startsWith("ee")) {
                throw SecretException(SecretError.UNSUPPORTED_FAKETLS, "ee/FakeTLS secret 暂不支持")
            }
            if (lower.startsWith("dd")) {
                if (lower.length < 34) {
                    throw SecretException(SecretError.BAD, "dd secret 长度不足 34")
                }
                return hex.copyOfRange(1, 17)
            }
            if (hex.size > SECRET_LEN) return hex.copyOfRange(0, SECRET_LEN)
            if (hex.size != SECRET_LEN) {
                throw SecretException(SecretError.BAD, "secret 解码为 ${hex.size} 字节，期望 16")
            }
            return hex
        }

        // hex 解析失败 → 按 base64 兜底（部分分享链接把 secret 编码成 base64）
        val raw = decodeBase64Lenient(trimmed)
            ?: throw SecretException(SecretError.BAD, "secret 既不是合法 hex 也不是 base64")
        if (raw.size == SECRET_LEN) return raw
        if (raw.size >= SECRET_LEN + 1 && raw[0] == 0xEE.toByte()) {
            throw SecretException(SecretError.UNSUPPORTED_FAKETLS, "ee/FakeTLS secret 暂不支持")
        }
        if (raw.size >= SECRET_LEN + 1 && raw[0] == 0xDD.toByte()) {
            return raw.copyOfRange(1, SECRET_LEN + 1)
        }
        if (raw.size > SECRET_LEN) return raw.copyOfRange(0, SECRET_LEN)
        throw SecretException(SecretError.BAD, "base64 secret 解码为 ${raw.size} 字节，期望至少 16")
    }

    /**
     * 从分享链接里取 `secret` 参数（`tg://proxy?...` / `https://t.me/proxy?...`）。
     *
     * 只做取值，不做校验：非法内容留给 [parseSecret] 报具体原因。
     */
    fun secretFromLink(link: String): String? {
        val queryStart = link.indexOf('?')
        if (queryStart < 0) return null
        for (pair in link.substring(queryStart + 1).split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) != "secret") continue
            return pair.substring(eq + 1)
        }
        return null
    }

    /** hex 解析：奇数长度或含非 hex 字符返回 null（等价 Python `bytes.fromhex` 的失败语义）。 */
    private fun hexOrNull(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(text[i * 2]) ?: return null
            val lo = hexDigit(text[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(ch: Char): Int? = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> null
    }

    // -----------------------------------------------------------------------
    // 字节序与小工具（MTProto 全程小端）

    fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    fun le64(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr (8 * index)) and 0xFF).toByte()
    }

    fun le32At(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** 取 `data[from]` 到 `data[to]`（含两端）的倒序切片。 */
    private fun reversed(data: ByteArray, from: Int, to: Int): ByteArray {
        val size = from - to + 1
        return ByteArray(size) { index -> data[from - index] }
    }
}

/** 协议层异常（承载可直接展示给用户的判定理由）。 */
class MtProtoProtocolException(message: String) : Exception(message)

/**
 * base64 解码（供 `parseSecret` 的兜底分支使用）。
 *
 * 平台的 `java.util.Base64` 要 API 26，而工程 minSdk 24；`android.util.Base64`
 * 在 JVM 单测里是空壳。okio 随 OkHttp 已在依赖里，行为在两种环境下一致。
 */
internal fun decodeBase64Lenient(text: String): ByteArray? {
    val normalized = text.replace('-', '+').replace('_', '/')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return padded.decodeBase64()?.toByteArray()
}
