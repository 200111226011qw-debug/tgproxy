package com.tgproxy.data

import com.tgproxy.Constants
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * MTProto 节点实测结论码。
 *
 * 与桌面版 `CheckResult` 的错误码一一对应：`ok` 只有一种，失败要**分得开**，
 * 否则界面只能说「失败」，用户无法判断是该换节点还是自己填错了 secret。
 */
enum class MtProtoCode {
    /** 握手成功：对端回了带我们 nonce 的 resPQ。 */
    OK,

    /** 链接里没有 secret：无法验证（保留「未验证」，不假装失败）。 */
    SECRET_EMPTY,

    /** secret 格式非法（长度/字符集不对）。 */
    SECRET_BAD,

    /** `ee`（FakeTLS）secret：本端未实现，明确报「不支持」而不是按 raw 硬连。 */
    SECRET_UNSUPPORTED,

    /** TCP 连接被拒/不可达。 */
    CONNECT_FAIL,

    /** 连接或读取超时。 */
    TIMEOUT,

    /** 传输层异常（RST、DNS 等）。 */
    NETWORK,

    /** 对端回了字节，但不是合法 MTProto（假节点、劫持、secret 不匹配）。 */
    PROTOCOL,
}

/** 单节点实测结果（[latencyMs] 仅在 [MtProtoCode.OK] 时有值）。 */
data class MtProtoResult(
    val code: MtProtoCode,
    val latencyMs: Int? = null,
    val detail: String = "",
) {
    val ok: Boolean get() = code == MtProtoCode.OK
}

/**
 * MTProto 实测调用面（仓储注入点：JVM 单测以确定性假件替换真实网络）。
 *
 * 之所以不复用 [TelegramGateCaller]：那条路径是「SOCKS5 隧道 + 两关」，
 * 而 MTProto 节点本身就是代理，直连它并用 obfuscated2 说话即可 —— 语义不同，
 * 混在一个接口里会让两种节点的失败原因无法区分。
 */
fun interface MtProtoCaller {
    fun probe(host: String, port: Int, secret: String?, timeoutMs: Int): MtProtoResult

    /** 默认预算（与桌面版单节点握手预算一致）。 */
    fun probe(host: String, port: Int, secret: String?): MtProtoResult =
        probe(host, port, secret, Constants.TG_TEST_TIMEOUT_MS)
}

/**
 * MTProto（MTProxy）真校验：obfuscated2 握手 → `req_pq_multi` → 校验 `resPQ`。
 *
 * 与安卓版既有的 SOCKS5 两关互补：
 * - SOCKS5 节点由 [TelegramGate] 经隧道 CONNECT 验证；
 * - MTProto 节点由本类直连验证（它自己就是代理，没有外层隧道）。
 *
 * 比对目标不是「端口通」，而是「对端用我们派生的密钥流回了带我们 nonce 的 resPQ」——
 * 假节点无法伪造后者。时钟可注入（纳秒基准，与 System.nanoTime 一致）。
 */
class MtProtoProbe(
    private val socketProvider: ProxySocketProvider = ProxySocketProvider { host, port, timeoutMs ->
        Socket().also { it.connect(InetSocketAddress(host, port), timeoutMs) }
    },
    private val clock: () -> Long = System::nanoTime,
    /** 随机源（生产 SecureRandom；测试给确定字节，让断言可以精确到字节）。 */
    private val random: (Int) -> ByteArray = { size ->
        ByteArray(size).also { SecureRandom().nextBytes(it) }
    },
    /** 请求头里声明的 DC id（149.154.167.51 是 DC2；直连 DC 不依赖它路由，但求与桌面版一致）。 */
    private val dcId: Int = DEFAULT_DC_ID,
) : MtProtoCaller {

    override fun probe(host: String, port: Int, secret: String?, timeoutMs: Int): MtProtoResult {
        val key = try {
            MtProtoHandshake.parseSecret(secret)
        } catch (e: MtProtoHandshake.SecretException) {
            return when (e.error) {
                MtProtoHandshake.SecretError.EMPTY -> MtProtoResult(MtProtoCode.SECRET_EMPTY, detail = e.message ?: "")
                MtProtoHandshake.SecretError.BAD -> MtProtoResult(MtProtoCode.SECRET_BAD, detail = e.message ?: "")
                MtProtoHandshake.SecretError.UNSUPPORTED_FAKETLS ->
                    MtProtoResult(MtProtoCode.SECRET_UNSUPPORTED, detail = e.message ?: "")
            }
        }

        val started = clock()
        val deadline = started + timeoutMs * 1_000_000L

        val init = try {
            MtProtoHandshake.buildInit(randomInit(), key, dcId)
        } catch (e: Exception) {
            // 平台缺 AES 实现（理论上不会，但吞掉会变成「所有节点都失败」的假象）
            return MtProtoResult(MtProtoCode.NETWORK, detail = "初始化加密失败: ${e.message}")
        }
        val nonce = random(16)
        val request = MtProtoHandshake.buildReqPqMulti(nonce, messageId())

        val sock = try {
            socketProvider.connect(host, port, timeoutMs)
        } catch (e: SocketTimeoutException) {
            return MtProtoResult(MtProtoCode.TIMEOUT, detail = "连接超时: ${e.message}")
        } catch (e: IOException) {
            return MtProtoResult(MtProtoCode.CONNECT_FAIL, detail = "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return MtProtoResult(MtProtoCode.CONNECT_FAIL, detail = e.message ?: "地址非法")
        }

        try {
            val out: OutputStream = sock.getOutputStream()
            out.write(init.init)
            // secure 模式的填充长度是随机的（0~3 字节）：固定填充本身就是可识别特征
            val padLen = (random(1)[0].toInt() and 0xFF) % 4
            val padding = if (padLen == 0) ByteArray(0) else random(padLen)
            // 只有帧本身加密；长度前缀也在帧里（secure 模式的 len 同样走密文）
            out.write(init.enc.update(MtProtoHandshake.frameSecure(request, padding)))
            out.flush()

            val frame = readFrame(sock, init.dec, deadline)
            val serverNonce = MtProtoHandshake.parseResPq(frame, nonce)
            return MtProtoResult(
                MtProtoCode.OK,
                latencyMs = ((clock() - started) / 1_000_000L).toInt(),
                detail = "server_nonce=${serverNonce.toHex()}",
            )
        } catch (e: SocketTimeoutException) {
            return MtProtoResult(MtProtoCode.TIMEOUT, detail = "读取响应超时")
        } catch (e: MtProtoProtocolException) {
            return MtProtoResult(MtProtoCode.PROTOCOL, detail = e.message ?: "协议错误")
        } catch (e: IOException) {
            return MtProtoResult(MtProtoCode.NETWORK, detail = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    /** 读一整帧：先解 4 字节长度，再解该长度的负载。 */
    @Throws(IOException::class, MtProtoProtocolException::class)
    private fun readFrame(sock: Socket, dec: MtProtoHandshake.CtrStream, deadline: Long): ByteArray {
        val lengthBytes = dec.update(recvExact(sock, 4, deadline))
        var frameLen = MtProtoHandshake.le32At(lengthBytes, 0).toLong() and 0xFFFFFFFFL
        if (frameLen > 0x80000000L) frameLen -= 0x80000000L
        if (frameLen <= 0 || frameLen > MtProtoHandshake.MAX_FRAME) {
            throw MtProtoProtocolException(
                "帧长度非法: $frameLen（解密后长度字节 ${lengthBytes.toHex()}）",
            )
        }
        return dec.update(recvExact(sock, frameLen.toInt(), deadline))
    }

    /** 精确读 n 字节；每次读前按剩余预算收紧 soTimeout（桌面版 SocketTransport 同款）。 */
    @Throws(IOException::class)
    private fun recvExact(sock: Socket, n: Int, deadline: Long): ByteArray {
        val out = ByteArray(n)
        var off = 0
        val input: InputStream = sock.getInputStream()
        while (off < n) {
            val remainingMs = (deadline - clock()) / 1_000_000L
            if (remainingMs <= 0) throw SocketTimeoutException("握手预算耗尽")
            sock.soTimeout = remainingMs.toInt()
            val read = input.read(out, off, n - off)
            if (read < 0) throw IOException("连接被对端关闭")
            off += read
        }
        return out
    }

    /**
     * 生成合法 obfuscated2 随机头（桌面版同款拒绝采样）。
     *
     * 前 4 字节若是 `GET `/`POST`/`HEAD`/`OPTI` 或保留协议字，中间设备会把它当
     * HTTP / 已知协议解析并改写，握手还没开始就被破坏。
     */
    private fun randomInit(): ByteArray {
        repeat(MAX_INIT_ATTEMPTS) {
            val candidate = random(MtProtoHandshake.INIT_LEN)
            if (candidate[0] == 0xEF.toByte()) return@repeat
            if (candidate.copyOfRange(0, 4).contentEquals(FORBIDDEN_FIRST4[0]) ||
                candidate.copyOfRange(0, 4).contentEquals(FORBIDDEN_FIRST4[1]) ||
                candidate.copyOfRange(0, 4).contentEquals(FORBIDDEN_FIRST4[2]) ||
                candidate.copyOfRange(0, 4).contentEquals(FORBIDDEN_FIRST4[3]) ||
                candidate.copyOfRange(0, 4).contentEquals(FORBIDDEN_ZERO) ||
                candidate.copyOfRange(0, 4).contentEquals(FORBIDDEN_ABRIDGED) ||
                candidate.copyOfRange(0, 4).contentEquals(FORBIDDEN_INTERMEDIATE) ||
                candidate.copyOfRange(0, 4).contentEquals(FORBIDDEN_SECURE)
            ) {
                return@repeat
            }
            if (candidate.copyOfRange(4, 8).contentEquals(FORBIDDEN_ZERO)) return@repeat
            return candidate
        }
        throw IllegalStateException("连续 $MAX_INIT_ATTEMPTS 次都未生成合法 obfuscated2 头")
    }

    /** MTProto msg_id：秒级时间戳左移 32 位、低 2 位清零（与桌面版 `int(t*2**32) & ~3` 同义）。 */
    private fun messageId(): Long {
        val seconds = System.currentTimeMillis() / 1000.0
        return (seconds * 4_294_967_296.0).toLong() and -4L
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        /** 桌面版 DEFAULT_DC。 */
        const val DEFAULT_DC_ID = 2

        /** 生成合法随机头的最大尝试次数（桌面版同样 64 次封顶）。 */
        const val MAX_INIT_ATTEMPTS = 64

        private val FORBIDDEN_ZERO = byteArrayOf(0, 0, 0, 0)
        private val FORBIDDEN_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
        private val FORBIDDEN_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
        private val FORBIDDEN_SECURE = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())
        private val FORBIDDEN_FIRST4 = arrayOf(
            "GET ".toByteArray(Charsets.US_ASCII),
            "POST".toByteArray(Charsets.US_ASCII),
            "HEAD".toByteArray(Charsets.US_ASCII),
            "OPTI".toByteArray(Charsets.US_ASCII),
        )
    }
}

/** 生产实现：无状态探针（Socket 独立创建，线程安全）。 */
val DefaultMtProtoCaller: MtProtoCaller = MtProtoProbe()
