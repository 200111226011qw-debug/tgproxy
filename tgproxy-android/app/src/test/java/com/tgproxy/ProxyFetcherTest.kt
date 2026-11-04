package com.tgproxy

import com.tgproxy.data.ProxyFetcher
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓取层完整性下限的测试（竞速的前置，先于竞速单独落地）。
 *
 * 只测「单个响应能不能用」，不测竞速 —— 竞速在 MirrorRaceTest 里，避免
 * 「空体被拒」和「谁先到谁赢」混在一个测试里分不清是谁的锅。
 */
class ProxyFetcherTest {

    // -----------------------------------------------------------------------
    // 纯函数：hasUsableLine（等价桌面 default_validator）

    @Test
    fun `空文本与纯注释都判不可用`() {
        assertFalse(ProxyFetcher.hasUsableLine(""))
        assertFalse(ProxyFetcher.hasUsableLine("\n\n  \n"))
        assertFalse(ProxyFetcher.hasUsableLine("# 只有注释\n# 还是注释\n"))
    }

    @Test
    fun `含一条非注释行即可用`() {
        assertTrue(ProxyFetcher.hasUsableLine("socks5://1.2.3.4:1080"))
        assertTrue(ProxyFetcher.hasUsableLine("# 注释\nsocks5://1.2.3.4:1080\n"))
        assertTrue(ProxyFetcher.hasUsableLine("   \n 1.2.3.4:1080 \n"))
    }

    // -----------------------------------------------------------------------
    // 接线：fetch 拒绝空体 / 纯注释体（用假 Call.Factory，无网络）

    /** 对任意 URL 都返回同一个 body 的假 client。 */
    private class FixedBodyFactory(private val body: String) : Call.Factory {
        override fun newCall(request: Request): Call = object : Call {
            override fun request(): Request = request

            override fun execute(): Response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/plain".toMediaType()))
                .build()

            override fun enqueue(responseCallback: Callback): Unit =
                throw UnsupportedOperationException("提交1只用 execute")

            override fun cancel(): Unit = Unit
            override fun isCanceled(): Boolean = false
            override fun isExecuted(): Boolean = false
            override fun clone(): Call = this
            override fun timeout(): Timeout = Timeout()
        }
    }

    @Test
    fun `200 空体被拒（min_bytes）`() {
        val fetcher = ProxyFetcher(client = FixedBodyFactory(""), minBytes = 64)
        val error = assertThrows(IOException::class.java) {
            runBlocking { fetcher.fetch("https://example.com/list.txt") }
        }
        assertTrue("信息里要带原因", error.message!!.contains("截断"))
    }

    @Test
    fun `200 纯注释体被拒（validator）`() {
        val body = "# 注释\n# 没有任何代理行\n".repeat(10) // 足够长，越过 min_bytes
        val fetcher = ProxyFetcher(client = FixedBodyFactory(body), minBytes = 64)
        val error = assertThrows(IOException::class.java) {
            runBlocking { fetcher.fetch("https://example.com/list.txt") }
        }
        assertTrue(error.message!!.contains("无有效代理行"))
    }

    @Test
    fun `有效列表原样返回`() {
        val body = "socks5://1.2.3.4:1080\nsocks5://5.6.7.8:1080\nsocks5://9.9.9.9:1080\nsocks5://10.0.0.1:1080\n"
        val fetcher = ProxyFetcher(client = FixedBodyFactory(body), minBytes = 64)
        val result = runBlocking { fetcher.fetch("https://example.com/list.txt") }
        assertEquals(body, result)
    }
}
