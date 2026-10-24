package com.tgproxy.data

import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import java.net.URLDecoder

/**
 * 代理列表文本解析器：桌面版 `parse_proxy_text` 的逐行为等价移植（纯 Kotlin，零 Android 依赖）。
 *
 * 行为保真要点（ARCHITECTURE.md §1.4 / F3~F11）：
 * - IPv4 严格校验：4 段、每段 1~3 位纯数字、0~255（含 X 掩码一律丢弃）
 * - 端口 1~65535（1~5 位纯数字）
 * - host:port 去重：socks5 与 MTProto 共用 seen 集合；非 Telegram 类型按 host:port 单独去重计 excluded
 * - 仅保留 socks5 与 MTProto（tg://proxy 前缀，或主机为 t.me / www.t.me 且路径以 proxy 开头）
 * - 非法行（无 scheme://、掩码、坏端口、缺 secret、无端口）计 dropped
 * - 元数据提取：入库时间正则、[标签] 剔除、地区/ISP 按 `—` 多形态分隔
 */
class ProxyParser {

    /** 解析结果三元组（与桌面版返回值一致）。 */
    data class ParseResult(
        val entries: List<ProxyEntry>,
        val excludedCount: Int,
        val droppedCount: Int,
    )

    /** MTProto 链接中提取出的关键信息。 */
    private data class MtprotoInfo(val link: String, val host: String, val port: Int)

    /** 行尾元数据。 */
    private data class Meta(val addedAt: String, val location: String, val isp: String)

    /**
     * 解析代理列表文本。
     *
     * @param text 数据源全文；null 按空文本处理
     * @return (entries, excludedCount, droppedCount)
     */
    fun parse(text: String?): ParseResult {
        val entries = mutableListOf<ProxyEntry>()
        val seenKept = HashSet<Pair<String, Int>>()
        val excludedSeen = HashSet<Pair<String, Int>>()
        var droppedCount = 0

        for (rawLine in (text ?: "").lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val match = LINE_RE.matchEntire(line)
            if (match == null) {
                // 无 scheme:// 的裸 host:port 行（TheSpeedX / monosans 等列表格式）按 SOCKS5 处理
                val bare = BARE_RE.matchEntire(line)
                if (bare == null) {
                    droppedCount++
                    continue
                }
                val bareHost = bare.groupValues[1]
                val barePort = bare.groupValues[2]
                if (parseIpv4(bareHost) == null || parsePort(barePort) == null) {
                    droppedCount++
                    continue
                }
                val key = bareHost to barePort.toInt()
                if (key in seenKept) continue
                seenKept.add(key)
                val bareMeta = parseMeta(bare.groupValues[3])
                entries.add(
                    ProxyEntry(
                        link = "socks5://$bareHost:$barePort",
                        host = bareHost,
                        port = barePort.toInt(),
                        type = ProxyType.SOCKS5,
                        latencyMs = null,
                        status = ProxyStatus.UNAVAILABLE,
                        location = bareMeta.location,
                        isp = bareMeta.isp,
                        addedAt = bareMeta.addedAt,
                    ),
                )
                continue
            }
            val scheme = match.groupValues[1].lowercase()
            val rest = match.groupValues[2]
            val meta = parseMeta(match.groupValues[3])

            // MTProto 分支：tg://proxy?... 或 http(s)://(www.)t.me/proxy?...
            if (isMtprotoUrl(scheme, rest)) {
                val parsed = parseMtproto(scheme, rest)
                if (parsed == null) {
                    droppedCount++
                    continue
                }
                val key = parsed.host to parsed.port
                if (key in seenKept) continue
                seenKept.add(key)
                entries.add(
                    ProxyEntry(
                        link = parsed.link,
                        host = parsed.host,
                        port = parsed.port,
                        type = ProxyType.MTPROTO,
                        latencyMs = null,
                        status = ProxyStatus.UNAVAILABLE,
                        location = meta.location,
                        isp = meta.isp,
                        addedAt = meta.addedAt,
                    ),
                )
                continue
            }

            // 普通分支：按 scheme://authority 切 host:port
            val authority = rest.substringBefore("/")
            val split = splitHostPort(authority)
            val hostText = split?.first
            val portText = split?.second
            val hostOk = if (!hostText.isNullOrEmpty()) parseIpv4(hostText) else null
            val port = if (!portText.isNullOrEmpty()) parsePort(portText) else null

            if (scheme != "socks5") {
                // Telegram 不可用类型（http/https/ss/vmess/vless/trojan 等）计入已排除（按 host:port 去重）
                if (hostOk != null && port != null) {
                    excludedSeen.add(hostOk to port)
                } else {
                    droppedCount++
                }
                continue
            }

            if (hostOk == null || port == null) {
                // 含掩码字符 X 等非法 IPv4 / 坏端口，直接丢弃
                droppedCount++
                continue
            }
            val key = hostOk to port
            if (key in seenKept) continue
            seenKept.add(key)
            entries.add(
                ProxyEntry(
                    link = "socks5://$hostOk:$port",
                    host = hostOk,
                    port = port,
                    type = ProxyType.SOCKS5,
                    latencyMs = null,
                    status = ProxyStatus.UNAVAILABLE,
                    location = meta.location,
                    isp = meta.isp,
                    addedAt = meta.addedAt,
                ),
            )
        }
        return ParseResult(entries, excludedSeen.size, droppedCount)
    }

    /**
     * 严格校验 IPv4：4 段、每段 1~3 位纯数字且 ≤255；合法返回原文，否则 null（等价 parse_ipv4）。
     */
    internal fun parseIpv4(text: String?): String? {
        if (text == null) return null
        val parts = text.split(".")
        if (parts.size != 4) return null
        for (part in parts) {
            if (!IPV4_SEGMENT_RE.matches(part)) return null
            val value = part.toIntOrNull() ?: return null
            if (value > 255) return null
        }
        return text
    }

    /**
     * 校验端口：1~5 位纯数字且 1~65535；合法返回整数值，否则 null（等价 parse_port）。
     */
    internal fun parsePort(text: String?): Int? {
        if (text == null || !PORT_RE.matches(text)) return null
        val port = text.toIntOrNull() ?: return null
        return if (port in 1..65535) port else null
    }

    /**
     * 从 authority 段按最后一个 `:` 切出 host 与端口文本；无 `:` 返回 null（等价 _split_host_port）。
     */
    private fun splitHostPort(authority: String): Pair<String, String>? {
        val index = authority.lastIndexOf(':')
        if (index < 0) return null
        return authority.substring(0, index).trim() to authority.substring(index + 1).trim()
    }

    /**
     * 解析行尾元数据：入库时间 / 地区 / ISP（剔除 [标签]）（等价 _parse_meta）。
     */
    private fun parseMeta(metaText: String): Meta {
        var meta = metaText
        var addedAt = ""
        val match = ADDED_AT_RE.find(meta)
        if (match != null) {
            addedAt = collapseWhitespace(match.groupValues[1])
            meta = meta.substring(0, match.range.first) + " " +
                meta.substring(match.range.last + 1)
        }
        meta = TAG_RE.replace(meta, " ")
        var location = meta
        var isp = ""
        for (sep in META_SEPARATORS) {
            if (meta.contains(sep)) {
                location = meta.substringBefore(sep)
                isp = meta.substringAfter(sep)
                break
            }
        }
        return Meta(addedAt, collapseWhitespace(location), collapseWhitespace(isp))
    }

    /**
     * 识别 MTProto 分享链接：tg://proxy?... 或主机为 t.me / www.t.me 的 http(s)://t.me/proxy?...
     * （等价 _is_mtproto_url）。
     */
    private fun isMtprotoUrl(scheme: String, rest: String): Boolean {
        val lowered = rest.lowercase()
        if (scheme == "tg") return lowered.startsWith("proxy")
        if (scheme == "http" || scheme == "https") {
            val authority = rest.substringBefore("/").lowercase()
            val path = if (rest.contains("/")) lowered.substringAfter("/") else ""
            return (authority == "t.me" || authority == "www.t.me") && path.startsWith("proxy")
        }
        return false
    }

    /**
     * 从 MTProto 链接提取 server/port/secret；缺失或非法返回 null（等价 _parse_mtproto）。
     *
     * query 解析以 URLDecoder + 「空值视为缺失」近似桌面版 parse_qs（secret 只校验非空）。
     */
    private fun parseMtproto(scheme: String, rest: String): MtprotoInfo? {
        val params = parseQuery(extractQuery(rest))
        val server = (params["server"] ?: "").trim()
        val portText = (params["port"] ?: "").trim()
        val secret = (params["secret"] ?: "").trim()
        val host = parseIpv4(server)
        val port = parsePort(portText)
        if (host == null || port == null || secret.isEmpty()) return null
        return MtprotoInfo(link = "$scheme://$rest", host = host, port = port)
    }

    /** 取 `?` 之后、`#` 之前的 query 段（近似 urlparse 的 query 切分）。 */
    private fun extractQuery(rest: String): String {
        val queryIndex = rest.indexOf('?')
        if (queryIndex < 0) return ""
        var query = rest.substring(queryIndex + 1)
        val hashIndex = query.indexOf('#')
        if (hashIndex >= 0) query = query.substring(0, hashIndex)
        return query
    }

    /**
     * 近似 parse_qs 默认行为：按 `&` 切分、首个 `=` 切键值、空值丢弃（keep_blank_values=False）、
     * 同名键取首个值；URLDecoder 解码（`+` 视为空格），非法序列回退原文。
     */
    private fun parseQuery(query: String): Map<String, String> {
        val result = HashMap<String, String>()
        if (query.isEmpty()) return result
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val key = urlDecode(pair.substring(0, eq))
            val value = urlDecode(pair.substring(eq + 1))
            if (value.isEmpty()) continue
            if (!result.containsKey(key)) result[key] = value
        }
        return result
    }

    private fun urlDecode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (e: IllegalArgumentException) {
        text
    }

    /** 折叠连续空白为单空格（等价 Python `" ".join(s.split())`）。 */
    private fun collapseWhitespace(text: String): String =
        text.split(WHITESPACE_RE).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        /** 行结构：scheme://rest <空白> meta（等价 _LINE_RE；matchEntire 与 re.match + `.*$` 等价）。 */
        private val LINE_RE = Regex("""([A-Za-z][A-Za-z0-9+.\-]*)://(\S+)\s*(.*)""")

        /** 裸地址行：host:port <空白> meta（等价 _BARE_RE；host 合法性由 parseIpv4 复核）。 */
        private val BARE_RE = Regex("""([0-9.]+):([0-9]{1,5})\s*(.*)""")

        /** 入库时间（等价 _ADDED_AT_RE）。 */
        private val ADDED_AT_RE =
            Regex("""入库时间[：:]\s*(\d{1,2}-\d{1,2}\s+\d{1,2}:\d{2})""")

        /** [标签]（等价 _TAG_RE）。 */
        private val TAG_RE = Regex("""\[[^\]]*\]""")

        /** IPv4 单段（等价 _IPV4_SEGMENT_RE）。 */
        private val IPV4_SEGMENT_RE = Regex("""[0-9]{1,3}""")

        /** 端口（等价 _PORT_RE）。 */
        private val PORT_RE = Regex("""[0-9]{1,5}""")

        /** 连续空白（折叠用）。 */
        private val WHITESPACE_RE = Regex("""\s+""")

        /** 地区/ISP 分隔符，按优先级尝试（等价 _parse_meta 中的元组）。 */
        private val META_SEPARATORS = listOf(" — ", " —", "— ", "—", " - ")
    }
}
