package com.tgproxy.data

import com.tgproxy.Constants
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
 * - connect / read 超时均 8s（Constants.FETCH_TIMEOUT_MS，对齐桌面镜像竞速总预算）
 * - User-Agent 与桌面版一致（Constants.USER_AGENT）
 * - 非 2xx 响应抛 IOException("HTTP {code}")
 * - 抓取在 Dispatchers.IO 执行；不实现桌面版的 SSL 免验证兜底（见 ARCHITECTURE.md §5.1）
 * - raw.githubusercontent.com 直连失败时与桌面版一致地回退 jsDelivr 镜像
 */
class ProxyFetcher(
    private val client: Call.Factory = defaultClient(),
    private val minBytes: Int = Constants.FETCH_MIN_BYTES,
    private val race: MirrorRace = MirrorRace(),
) : ProxySource {

    override suspend fun fetch(url: String): String =
        race.race(buildCandidates(url)) { candidateUrl -> fetchCandidate(candidateUrl) }

    /**
     * 单个候选的 HTTP 抓取 + 完整性校验。
     *
     * 用 enqueue（异步）而非 execute：execute 是阻塞调用，协程取消打断不了它，
     * 竞速里落败的候选靠「取消协程 → invokeOnCancellation → call.cancel()」真正断掉，
     * 否则手机会继续把整份列表下完。internal 供单测直接验证取消链路。
     */
    internal suspend fun fetchCandidate(url: String): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Constants.USER_AGENT)
            .build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            cont.resumeWith(Result.failure(IOException("HTTP ${response.code}")))
                            return
                        }
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        // 校验 + 解码只做一次，返回文本（11 MB 的 body 别解码两遍）
                        val body = validateBody(bytes)
                        cont.resume(body, null)
                    } catch (e: Exception) {
                        cont.resumeWith(Result.failure(e))
                    }
                }
            }
        })
    }

    /** 直连 + 3 个 jsDelivr 镜像候选（label 与桌面 direct / jsdelivr-{cdn,gcore,fastly} 对齐）。 */
    private fun buildCandidates(url: String): List<MirrorRace.Candidate> {
        val mirrors = mirrorUrls(url)
        if (mirrors.isEmpty()) return listOf(MirrorRace.Candidate(url, "direct"))
        val labels = Constants.MIRROR_HOSTS.map { "jsdelivr-${it.substringBefore('.')}" }
        return listOf(MirrorRace.Candidate(url, "direct")) +
            mirrors.mapIndexed { index, mirror -> MirrorRace.Candidate(mirror, labels[index]) }
    }

    /**
     * 单个候选响应的完整性下限（等价桌面版 evaluate_response 的 EMPTY / UNUSABLE 两关）。
     *
     * 空体 / 纯注释在「端口通、返回 2xx」时也会发生（CDN 缓存穿透、上游截断），
     * 不判掉的话，一轮抓取会把「0 个节点」当成功吞下。按**字节**量（桌面用 len(raw)）。
     * 返回解码后的文本：调用方要再 decode 一遍就会把 11 MB 的 body 解两次。
     */
    private fun validateBody(bytes: ByteArray): String {
        if (bytes.size < minBytes) {
            throw IOException("响应仅 ${bytes.size} 字节（< $minBytes），按截断处理")
        }
        val text = bytes.toString(Charsets.UTF_8)
        if (!hasUsableLine(text)) {
            throw IOException("响应无有效代理行")
        }
        return text
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
