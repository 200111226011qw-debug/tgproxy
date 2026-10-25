package com.tgproxy

import com.tgproxy.data.ProxyParser
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProxyParser 对拍桌面版 parse_proxy_text（PRD §7-1 / ARCHITECTURE.md §7 T03）。
 *
 * 用例与架构文档列出的 16 个对拍点一一对应。
 */
class ProxyParserTest {

    private val parser = ProxyParser()

    @Test
    fun `socks5 完整行元数据提取：入库时间、标签剔除、地区与 ISP`() {
        val text = "socks5://1.2.3.4:1080 入库时间：10-21 22:58 [热门] " +
            "荷兰 北荷兰省 阿姆斯特丹 — DigitalOcean"
        val result = parser.parse(text)

        assertEquals(0, result.excludedCount)
        assertEquals(0, result.droppedCount)
        assertEquals(1, result.entries.size)

        val entry = result.entries[0]
        assertEquals("socks5://1.2.3.4:1080", entry.link)
        assertEquals("1.2.3.4", entry.host)
        assertEquals(1080, entry.port)
        assertEquals(ProxyType.SOCKS5, entry.type)
        assertEquals(ProxyStatus.UNAVAILABLE, entry.status)
        assertNull(entry.latencyMs)
        assertEquals("10-21 22:58", entry.addedAt)
        assertEquals("荷兰 北荷兰省 阿姆斯特丹", entry.location)
        assertEquals("DigitalOcean", entry.isp)
    }

    @Test
    fun `tg proxy 链接保留且 link 原样重建`() {
        val link = "tg://proxy?server=5.6.7.8&port=443&secret=abcdef123456"
        val result = parser.parse(link)

        assertEquals(1, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(0, result.droppedCount)

        val entry = result.entries[0]
        assertEquals(link, entry.link)
        assertEquals("5.6.7.8", entry.host)
        assertEquals(443, entry.port)
        assertEquals(ProxyType.MTPROTO, entry.type)
    }

    @Test
    fun `https t me 与 www t me 的 proxy 链接识别为 MTProto`() {
        val text = "https://t.me/proxy?server=1.1.1.1&port=443&secret=aa\n" +
            "https://www.t.me/proxy?server=2.2.2.2&port=443&secret=bb"
        val result = parser.parse(text)

        assertEquals(2, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(0, result.droppedCount)
        assertTrue(result.entries.all { it.type == ProxyType.MTPROTO })
        assertEquals("1.1.1.1", result.entries[0].host)
        assertEquals("2.2.2.2", result.entries[1].host)
    }

    @Test
    fun `缺 secret 或 secret 为空的 MTProto 行被丢弃`() {
        val text = "tg://proxy?server=5.6.7.8&port=443\n" +
            "tg://proxy?server=5.6.7.9&port=443&secret="
        val result = parser.parse(text)

        assertEquals(0, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(2, result.droppedCount)
    }

    @Test
    fun `MTProto 的 server 非法或 port 越界被丢弃`() {
        val text = "tg://proxy?server=1.2.3&port=443&secret=x\n" +
            "tg://proxy?server=1.2.3.4&port=99999&secret=x\n" +
            "tg://proxy?server=1.2.3.4&port=0&secret=x"
        val result = parser.parse(text)

        assertEquals(0, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(3, result.droppedCount)
    }

    @Test
    fun `非 Telegram 类型合法地址计入已排除并按 host port 去重`() {
        val text = "http://1.1.1.1:8080\n" +
            "https://1.1.1.1:8080\n" +
            "ss://2.2.2.2:8388\n" +
            "vmess://3.3.3.3:10086\n" +
            "vless://4.4.4.4:443\n" +
            "trojan://5.5.5.5:443\n" +
            "http://1.1.1.1:8080"
        val result = parser.parse(text)

        assertEquals(0, result.entries.size)
        // http/https 同一 host:port 只计一次
        assertEquals(5, result.excludedCount)
        assertEquals(0, result.droppedCount)
    }

    @Test
    fun `非 Telegram 类型缺端口或主机非法被丢弃`() {
        val text = "http://1.2.3.4\nhttps://example.com:8080"
        val result = parser.parse(text)

        assertEquals(0, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(2, result.droppedCount)
    }

    @Test
    fun `含 X 掩码的 IPv4 被丢弃`() {
        val text = "socks5://1.2.3.X:1080\nsocks5://10.0.0.1X:1080"
        val result = parser.parse(text)

        assertEquals(0, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(2, result.droppedCount)
    }

    @Test
    fun `端口为零、越界或非数字被丢弃`() {
        val text = "socks5://1.2.3.4:0\n" +
            "socks5://1.2.3.4:99999\n" +
            "socks5://1.2.3.4:abc\n" +
            "socks5://1.2.3.4:123456"
        val result = parser.parse(text)

        assertEquals(0, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(4, result.droppedCount)
    }

    @Test
    fun `重复 host port 跨类型去重只保留一条且不计任何计数`() {
        val text = "socks5://1.2.3.4:1080\n" +
            "tg://proxy?server=1.2.3.4&port=1080&secret=abc"
        val result = parser.parse(text)

        assertEquals(1, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(0, result.droppedCount)
        assertEquals(ProxyType.SOCKS5, result.entries[0].type)
    }

    @Test
    fun `注释行与空行被跳过`() {
        val text = "# 数据源注释\n\n   \nsocks5://1.2.3.4:1080\n# 另一条注释"
        val result = parser.parse(text)

        assertEquals(1, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(0, result.droppedCount)
    }

    @Test
    fun `裸 host port 行按 SOCKS5 保留，无斜杠的 scheme 行仍被丢弃`() {
        val text = "1.2.3.4:1080\nsocks5:1.2.3.4:1080"
        val result = parser.parse(text)

        assertEquals(1, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(1, result.droppedCount)
        val entry = result.entries[0]
        assertEquals("socks5://1.2.3.4:1080", entry.link)
        assertEquals(ProxyType.SOCKS5, entry.type)
    }

    @Test
    fun `裸 host port 行支持元数据与去重`() {
        val text = "1.2.3.4:1080 日本 — IIJ\n" +
            "1.2.3.4:1080 美国 — AWS\n" +
            "5.6.7.8:9050"
        val result = parser.parse(text)

        assertEquals(2, result.entries.size)
        assertEquals(0, result.droppedCount)
        assertEquals("日本", result.entries[0].location)
        assertEquals("IIJ", result.entries[0].isp)
    }

    @Test
    fun `地区与 ISP 的多种分隔符形态都被正确切分`() {
        val text = "socks5://1.2.3.4:1080 地区A — ISP-A\n" +
            "socks5://2.3.4.5:1080 地区B —ISP-B\n" +
            "socks5://3.4.5.6:1080 地区C— ISP-C\n" +
            "socks5://4.5.6.7:1080 地区D—ISP-D\n" +
            "socks5://5.6.7.8:1080 地区E - ISP-E"
        val result = parser.parse(text)

        assertEquals(5, result.entries.size)
        val pairs = result.entries.map { it.location to it.isp }
        assertEquals(
            listOf(
                "地区A" to "ISP-A",
                "地区B" to "ISP-B",
                "地区C" to "ISP-C",
                "地区D" to "ISP-D",
                "地区E" to "ISP-E",
            ),
            pairs,
        )
    }

    @Test
    fun `无元数据行的地区 ISP 入库时间均为空串`() {
        val result = parser.parse("socks5://1.2.3.4:1080")

        assertEquals(1, result.entries.size)
        val entry = result.entries[0]
        assertEquals("", entry.location)
        assertEquals("", entry.isp)
        assertEquals("", entry.addedAt)
    }

    @Test
    fun `http t me 非 proxy 路径按普通行处理并丢弃`() {
        val result = parser.parse("http://t.me/notproxy")

        assertEquals(0, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(1, result.droppedCount)
    }

    @Test
    fun `混合大样例的保留 排除 丢弃三元组与桌面版一致`() {
        val text = """
            # 代理列表
            socks5://10.0.0.1:1080 入库时间：10-21 22:58 [新] 香港 — HGC
            socks5://10.0.0.2:1080 日本 — IIJ
            socks5://10.0.0.3:1080
            tg://proxy?server=10.0.0.4&port=443&secret=dd1234567890
            https://t.me/proxy?server=10.0.0.5&port=443&secret=ee1234567890
            http://11.0.0.1:8080
            https://11.0.0.1:8080
            ss://11.0.0.2:8388
            vmess://11.0.0.3:10086
            vless://11.0.0.4:443
            trojan://11.0.0.5:443
            socks5://12.0.0.X:1080
            socks5://12.0.0.9:0
            socks5://12.0.0.10:abc
            tg://proxy?server=12.0.0.11&port=443
            tg://proxy?server=12.0.0.12&port=443&secret=
            tg://proxy?server=13.0.0.1&port=443&secret=ff
            1.2.3.4:1080
            http://t.me/notproxy

            socks5://10.0.0.1:1080
        """.trimIndent()

        val result = parser.parse(text)

        // entries=7（3 socks5 + 3 MTProto + 裸 host:port 行按 SOCKS5 保留，末行重复去重）
        // excluded=5（http/https 去重后）；dropped=6
        // （与 docs/vectors.json 的 V13_mixed_big golden 一致，双端对拍）
        assertEquals(7, result.entries.size)
        assertEquals(5, result.excludedCount)
        assertEquals(6, result.droppedCount)
        assertEquals(
            listOf(
                "socks5://10.0.0.1:1080",
                "socks5://10.0.0.2:1080",
                "socks5://10.0.0.3:1080",
                "tg://proxy?server=10.0.0.4&port=443&secret=dd1234567890",
                "https://t.me/proxy?server=10.0.0.5&port=443&secret=ee1234567890",
                "tg://proxy?server=13.0.0.1&port=443&secret=ff",
                "socks5://1.2.3.4:1080",
            ),
            result.entries.map { it.link },
        )
    }

    @Test
    fun `null 文本按空处理`() {
        val result = parser.parse(null)

        assertEquals(0, result.entries.size)
        assertEquals(0, result.excludedCount)
        assertEquals(0, result.droppedCount)
    }
}
