package com.tgproxy.data

import com.tgproxy.Constants
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 建立到代理的 TCP 连接（测试注入点：FakeSocket 脚本化握手字节流）。
 * 超时/拒绝抛 [IOException]，语义与生产 `Socket().connect(addr, timeoutMs)` 一致。
 */
fun interface ProxySocketProvider {
    fun connect(host: String, port: Int, timeoutMs: Int): Socket
}

/**
 * 在已建好的 SOCKS5 隧道上包一层 TLS 并返回可用通道（测试注入点）。
 * 生产实现：SSLSocketFactory.createSocket(sock, host, port, autoClose=true) + 握手。
 * 失败抛 [IOException]。
 */
fun interface TlsWrapper {
    fun wrap(socket: Socket, host: String, timeoutMs: Int): TlsChannel
}

/** TLS 通道抽象（SSLSocket 的最小面：读写 + 关闭）。 */
interface TlsChannel {
    val input: InputStream
    val output: OutputStream
    fun close()
}

/**
 * TG 实测调用面（仓储注入点：JVM 单测以确定性假件替换真实网络行为）。
 * 返回经代理到 TG 数据中心的往返毫秒；任一关失败为 null。
 */
fun interface TelegramGateCaller {
    fun test(host: String, port: Int): Int?

    /**
     * 带延迟门槛的入口（剪枝用）。
     *
     * 默认实现忽略门槛、退化为 [test]，这样既有测试假件不用改；但**生产实现
     * 必须覆写**，否则剪枝不生效（[DefaultTelegramGateCaller] 已覆写）。
     */
    fun test(host: String, port: Int, maxLatencyMs: Int?): Int? = test(host, port)
}

/** 生产实现：共享无状态 [TelegramGate]（Socket 独立创建，线程安全）。 */
val DefaultTelegramGateCaller: TelegramGateCaller = object : TelegramGateCaller {
    override fun test(host: String, port: Int): Int? =
        SharedTelegramGate.test(host, port)

    override fun test(host: String, port: Int, maxLatencyMs: Int?): Int? =
        SharedTelegramGate.test(host, port, maxLatencyMs = maxLatencyMs)
}

private val SharedTelegramGate = TelegramGate()

/**
 * TG 实测（与桌面版 test_telegram_via_socks5 两关串行逐项对齐）：
 *
 * 第一关：经代理 SOCKS5 CONNECT Telegram 数据中心 IP，往返毫秒即节点延迟；
 * 第二关：再经代理 CONNECT desktop.telegram.org，TLS 证书校验 + HTTP 状态行，
 * 验证全链路可用、无劫持。任一关失败返回 null；两关共享单节点总预算。
 *
 * 本类为纯阻塞实现（Socket IO），调用方负责放入 Dispatchers.IO；
 * 时钟可注入（[clock] 返回纳秒，与 System.nanoTime 同基准）。
 */
class TelegramGate(
    private val socketProvider: ProxySocketProvider = ProxySocketProvider { host, port, timeoutMs ->
        Socket().also { it.connect(InetSocketAddress(host, port), timeoutMs) }
    },
    private val tlsWrapper: TlsWrapper = TlsWrapper { socket, host, timeoutMs ->
        JavaxTlsChannel(socket, host, timeoutMs)
    },
    private val clock: () -> Long = System::nanoTime,
) {

    /**
     * 两关串行入口（等价桌面版 test_telegram_via_socks5）。
     *
     * @param timeoutMs 单关预算
     * @param deadlineMs 单节点两关总预算
     * @param maxLatencyMs 延迟门槛；传入时启用剪枝（见 [shouldPrune]）
     * @return 第一关（经代理到 TG 数据中心）的往返毫秒；任一关失败为 null
     */
    fun test(
        host: String,
        port: Int,
        timeoutMs: Int = Constants.TG_TEST_TIMEOUT_MS,
        deadlineMs: Int = Constants.TG_TEST_DEADLINE_MS,
        maxLatencyMs: Int? = null,
    ): Int? {
        // 桌面版：stage = max(1.0, min(timeout, deadline))
        val stageMs = maxOf(1, minOf(timeoutMs, deadlineMs))
        val started = clock()
        val dcMs = testSocks5TelegramDc(host, port, stageMs,
            Constants.TELEGRAM_DC_HOST, Constants.TELEGRAM_DC_PORT)
        if (dcMs == null) return null
        // 剪枝：dcMs 就是最终的 tg_latency，超门槛的节点必然被剔除，第二关
        // （TLS+HTTP）不必再跑。返回 dcMs 而不是 null —— 让上层归入「超门槛」
        // 而不是「连接失败」，否则会误增连败、三轮后被拉黑。
        if (shouldPrune(dcMs, maxLatencyMs)) return dcMs
        val remainingMs = deadlineMs - elapsedMs(started)
        if (remainingMs <= MIN_REMAINING_MS) return null
        val webOk = testTelegramWebViaSocks5(
            host, port, minOf(timeoutMs, remainingMs.toInt()),
            Constants.TELEGRAM_TEST_HOST, Constants.TELEGRAM_TEST_PORT,
        )
        if (webOk == null) return null
        return dcMs
    }

    /**
     * 第一关：经代理 CONNECT Telegram 数据中心，返回往返毫秒；不通返回 null。
     * timeoutMs 为本关总预算：连接、握手、CONNECT 共享同一个 deadline。
     */
    fun testSocks5TelegramDc(
        host: String,
        port: Int,
        timeoutMs: Int,
        dcHost: String = Constants.TELEGRAM_DC_HOST,
        dcPort: Int = Constants.TELEGRAM_DC_PORT,
    ): Int? {
        val started = clock()
        val deadline = started + timeoutMs * 1_000_000L
        val sock = try {
            socketProvider.connect(host, port, timeoutMs)
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        try {
            socks5Connect(sock, dcHost, dcPort, deadline)
            return elapsedMs(started)
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } finally {
            closeQuietly(sock)
        }
    }

    /**
     * 第二关：经代理全链路访问 Telegram 站点（CONNECT + TLS 证书校验 + HTTP 状态行）。
     * 返回往返毫秒；不通返回 null。
     */
    fun testTelegramWebViaSocks5(
        host: String,
        port: Int,
        timeoutMs: Int,
        targetHost: String = Constants.TELEGRAM_TEST_HOST,
        targetPort: Int = Constants.TELEGRAM_TEST_PORT,
    ): Int? {
        val started = clock()
        val deadline = started + timeoutMs * 1_000_000L
        val sock = try {
            socketProvider.connect(host, port, timeoutMs)
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        try {
            socks5Connect(sock, targetHost, targetPort, deadline)
            val remainingMs = (deadline - clock()) / 1_000_000L
            if (remainingMs <= 0) return null
            val tls = try {
                tlsWrapper.wrap(sock, targetHost, remainingMs.toInt())
            } catch (e: IOException) {
                return null
            }
            try {
                tls.output.write(
                    (
                        "GET / HTTP/1.1\r\nHost: $targetHost\r\n" +
                            "User-Agent: ${Constants.USER_AGENT}\r\nConnection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                tls.output.flush()
                val status = ByteArray(64)
                var off = 0
                while (off < 1) {
                    val n = tls.input.read(status, off, status.size - off)
                    if (n < 0) break
                    off += n
                }
                if (!statusFromHttp(status, off)) return null
                return elapsedMs(started)
            } finally {
                runCatching { tls.close() }
            }
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } finally {
            closeQuietly(sock)
        }
    }

    /** 已读字节数是否构成合法 HTTP 状态行开头（桌面版 status.startswith(b"HTTP/")）。 */
    private fun statusFromHttp(buf: ByteArray, len: Int): Boolean {
        val prefix = "HTTP/"
        if (len < prefix.length) return false
        for (i in prefix.indices) {
            if (buf[i].toInt().toChar() != prefix[i]) return false
        }
        return true
    }

    private fun elapsedMs(startedNanos: Long): Int =
        ((clock() - startedNanos) / 1_000_000L).toInt()

    /**
     * 在已连接的套接字上完成 SOCKS5 无认证握手与 CONNECT，失败抛 [IOException]
     * （等价桌面版 _socks5_connect，字节序逐项一致）。
     *
     * 域名目标用 ATYP=3 由代理端解析（避免本地 DNS 污染）；IPv4 目标用 ATYP=1。
     * deadline 为 clock() 基准的绝对时限，逐步骤收紧 socket 超时。
     */
    @Throws(IOException::class)
    internal fun socks5Connect(sock: Socket, targetHost: String, targetPort: Int, deadline: Long) {
        sock.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        sock.getOutputStream().flush()
        if (!recvExact(sock, 2, deadline).contentEquals(byteArrayOf(0x05, 0x00))) {
            throw IOException("socks5 handshake rejected")
        }
        val payload = connectPayload(targetHost, targetPort)
        sock.getOutputStream().write(payload)
        sock.getOutputStream().flush()
        val reply = recvExact(sock, 4, deadline)
        if (reply[1].toInt() != 0) {
            throw IOException("socks5 connect failed: ${reply[1]}")
        }
        when (val atyp = reply[3].toInt()) {
            1 -> recvExact(sock, 6, deadline)
            3 -> {
                val one = recvExact(sock, 1, deadline)
                recvExact(sock, 1 + one[0] + 2, deadline)
            }
            4 -> recvExact(sock, 18, deadline)
            else -> throw IOException("socks5 bad atyp $atyp")
        }
    }

    /** CONNECT 请求体：IPv4 文本走 ATYP=1，其余按域名 ATYP=3（IDNA 编码）。 */
    @Throws(IOException::class)
    private fun connectPayload(targetHost: String, targetPort: Int): ByteArray {
        val ipBytes = parseIpv4OrNull(targetHost)
        val portHi = (targetPort ushr 8).toByte()
        val portLo = (targetPort and 0xFF).toByte()
        return if (ipBytes != null) {
            byteArrayOf(0x05, 0x01, 0x00, 0x01) + ipBytes + byteArrayOf(portHi, portLo)
        } else {
            val target = targetHost.toByteArray(Charsets.US_ASCII)
            if (target.isEmpty() || target.size > 255) {
                throw IOException("socks5 bad domain")
            }
            byteArrayOf(0x05, 0x01, 0x00, 0x03, target.size.toByte()) + target +
                byteArrayOf(portHi, portLo)
        }
    }

    /** 严格 IPv4 点分文本解析（等价桌面版 inet_aton 成功路径）；非 IPv4 返回 null。 */
    private fun parseIpv4OrNull(host: String): ByteArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            var value = 0
            for (ch in part) {
                if (ch !in '0'..'9') return null
                value = value * 10 + (ch - '0')
            }
            if (value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    /**
     * 精确读取 n 字节，不足/超时/EOF 抛 [IOException]（等价桌面版 _recv_exact）。
     * 每次读前按剩余预算收紧 soTimeout。
     */
    @Throws(IOException::class)
    private fun recvExact(sock: Socket, n: Int, deadline: Long): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val remainingMs = (deadline - clock()) / 1_000_000L
            if (remainingMs <= 0) throw IOException("socks5 deadline exceeded")
            sock.soTimeout = remainingMs.toInt()
            val read = sock.getInputStream().read(out, off, n - off)
            if (read < 0) throw IOException("socks5 eof")
            off += read
        }
        return out
    }

    private fun closeQuietly(sock: Socket) {
        runCatching { sock.close() }
    }

    companion object {
        /** 第二关启动所需最小剩余预算（桌面版 remaining <= 1.0 秒即放弃）。 */
        const val MIN_REMAINING_MS = 1_000

        /**
         * 是否应当剪掉第二关。
         *
         * 依据：第一关的往返（dcMs）就是最终的 `tg_latency`，门槛比较的就是它。
         * 所以 dcMs 一旦超过门槛，无论第二关结果如何，这个节点都会被剔除 ——
         * 跑第二关纯属给注定淘汰的节点做无用功。
         *
         * 边界用 `>` 而不是 `>=`：门槛判定是 `tg_latency <= 门槛` 即保留，
         * 所以 dcMs == 门槛 时仍要跑第二关。
         */
        fun shouldPrune(dcMs: Int, maxLatencyMs: Int?): Boolean =
            maxLatencyMs != null && dcMs > maxLatencyMs
    }
}

/** 生产 TLS 通道：SSLSocketFactory 包装 + 默认证书/主机名校验（等价 ssl.create_default_context）。 */
private class JavaxTlsChannel(
    socket: Socket,
    host: String,
    timeoutMs: Int,
) : TlsChannel {
    private val sslSocket: Socket

    init {
        @Suppress("USELESS_CAST")
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val raw = factory.createSocket(socket, host, socket.port, true)
        val ssl = raw as javax.net.ssl.SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        sslSocket = ssl
    }

    override val input: InputStream get() = sslSocket.getInputStream()
    override val output: OutputStream get() = sslSocket.getOutputStream()
    override fun close() {
        runCatching { sslSocket.close() }
    }
}
