package com.tgproxy.data

import com.tgproxy.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
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
 * - raw.githubusercontent.com 直连失败时与桌面版一致地回退 jsDelivr 镜像
 */
class ProxyFetcher(
    private val client: Call.Factory = defaultClient(),
    private val minBytes: Int = Constants.FETCH_MIN_BYTES,
) : ProxySource {

    override suspend fun fetch(url: String): String {
        var lastError: IOException? = null
        for (candidate in listOf(url) + mirrorUrls(url)) {
            try {
                return fetchOnce(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("抓取失败：$url")
    }

    private suspend fun fetchOnce(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Constants.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: ""
            validateBody(body)
            body
        }
    }

    /**
     * 单个候选响应的完整性下限（等价桌面版 evaluate_response 的 EMPTY / UNUSABLE 两关）。
     *
     * 空体 / 纯注释在「端口通、返回 2xx」时也会发生（CDN 缓存穿透、上游截断），
     * 不判掉的话，一轮抓取会把「0 个节点」当成功吞下。
     */
    private fun validateBody(body: String) {
        if (body.length < minBytes) {
            throw IOException("响应仅 ${body.length} 字节（< $minBytes），按截断处理")
        }
        if (!hasUsableLine(body)) {
            throw IOException("响应无有效代理行")
        }
    }

    companion object {
        /** 默认客户端：connect/read 超时均 15s。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Constants.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(Constants.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        /** raw.githubusercontent.com 地址转 jsDelivr 镜像候选（等价桌面版 mirror_urls）。 */
        fun mirrorUrls(url: String): List<String> {
            val match = RAW_URL_RE.matchEntire(url) ?: return emptyList()
            val (user, repo, branch, path) = match.destructured
            return Constants.MIRROR_HOSTS.map { host ->
                "https://$host/gh/$user/$repo@$branch/$path"
            }
        }

        /** 是否含至少一条非空、非注释行（等价桌面版 default_validator）。 */
        fun hasUsableLine(text: String): Boolean {
            for (line in text.lineSequence()) {
                val stripped = line.trim()
                if (stripped.isNotEmpty() && !stripped.startsWith("#")) return true
            }
            return false
        }

        private val RAW_URL_RE = Regex(
            "^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$",
        )
    }
}
