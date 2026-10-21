package com.tgproxy.data

import com.tgproxy.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 数据源抓取接口（面向接口编程，便于 JVM 单测注入假件）。
 */
fun interface ProxySource {
    /** 抓取 [url] 指向的文本；失败抛异常（由上层转为 RefreshResult.Failure）。 */
    suspend fun fetch(url: String): String
}

/**
 * OkHttp 实现的 [ProxySource]（等价桌面版 fetch_proxy_text）。
 *
 * - connect / read 超时均 15s（Constants.FETCH_TIMEOUT_MS）
 * - User-Agent 与桌面版一致（Constants.USER_AGENT）
 * - 非 2xx 响应抛 IOException("HTTP {code}")
 * - 抓取在 Dispatchers.IO 执行；不实现桌面版的 SSL 免验证兜底（见 ARCHITECTURE.md §5.1）
 */
class ProxyFetcher(private val client: OkHttpClient = defaultClient()) : ProxySource {

    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Constants.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.body?.string() ?: ""
        }
    }

    companion object {
        /** 默认客户端：connect/read 超时均 15s。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Constants.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(Constants.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
