package com.tgproxy

import com.tgproxy.data.KnownGoodStore
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyType
import com.tgproxy.model.TgVerdict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KnownGoodStore 行为对拍桌面版 known_good.json（读/写/开关语义）。
 */
class KnownGoodStoreTest {

    private fun tempFile(name: String = "known_good.json"): File {
        val dir = File.createTempFile("kgtest", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        return File(dir, name)
    }

    private fun entry(host: String, port: Int = 1080): ProxyEntry = ProxyEntry(
        link = "socks5://$host:$port",
        host = host,
        port = port,
        type = ProxyType.SOCKS5,
        latencyMs = 120,
        status = com.tgproxy.model.ProxyStatus.AVAILABLE,
        tgVerdict = TgVerdict.PASS,
        location = "香港",
        isp = "HGC",
        addedAt = "10-26 12:00",
    )

    @Test
    fun `保存后读取往返一致且延迟状态不落盘`() {
        val file = tempFile()
        val store = KnownGoodStore(file)

        store.save(listOf(entry("1.2.3.4"), entry("5.6.7.8", 443)))
        val loaded = store.load()

        assertEquals(2, loaded.size)
        assertEquals("socks5://1.2.3.4:1080", loaded[0].link)
        assertEquals("香港", loaded[0].location)
        assertEquals(ProxyType.SOCKS5, loaded[0].type)
        // 延迟/验证状态不落盘：下轮一律重测
        assertEquals(null, loaded[0].latencyMs)
        assertEquals(TgVerdict.UNVERIFIED, loaded[0].tgVerdict)
    }

    @Test
    fun `开关关闭时不读不写`() {
        val file = tempFile()
        val store = KnownGoodStore(file).apply { enabled = false }

        store.save(listOf(entry("1.2.3.4")))
        assertFalse(file.exists()) // 不写

        file.writeText(
            """[{"link":"socks5://4.3.2.1:1080","host":"4.3.2.1","port":1080,"type":"SOCKS5"}]""",
        )
        assertTrue(store.load().isEmpty()) // 不读
    }

    @Test
    fun `文件缺失或损坏返回空表`() {
        val missing = KnownGoodStore(tempFile()) // 文件不存在
        assertTrue(missing.load().isEmpty())

        val file = tempFile()
        file.writeText("not json {{{")
        assertTrue(KnownGoodStore(file).load().isEmpty())

        file.writeText("""{"not":"a list"}""")
        assertTrue(KnownGoodStore(file).load().isEmpty())
    }

    @Test
    fun `坏条目跳过好条目保留`() {
        val file = tempFile()
        file.writeText(
            """
            [
              {"link":"socks5://1.2.3.4:1080","host":"1.2.3.4","port":1080,"type":"SOCKS5"},
              {"link":"","host":"","port":0,"type":"SOCKS5"},
              {"link":"socks5://5.6.7.8:99999","host":"5.6.7.8","port":99999,"type":"SOCKS5"},
              {"link":"socks5://x","host":"x","port":1,"type":"UNKNOWN_TYPE"},
              "not an object"
            ]
            """.trimIndent(),
        )

        val loaded = KnownGoodStore(file).load()

        assertEquals(1, loaded.size)
        assertEquals("socks5://1.2.3.4:1080", loaded[0].link)
    }

    @Test
    fun `none 工厂恒空读恒空写`() {
        val store = KnownGoodStore.none()
        assertTrue(store.load().isEmpty())
        store.save(listOf(entry("1.2.3.4"))) // 不抛异常
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `空列表保存不落盘`() {
        val file = tempFile()
        KnownGoodStore(file).save(emptyList())
        assertFalse(file.exists())
    }

    @Test
    fun `超量时按延迟升序保留最快的 200 条`() {
        // 回归（2026-10-28）：此前整表覆盖、无上限、无排序 —— 沉淀会无限增长，
        // 并在抽样时挤占新候选配额（samplePreferred 的 limit - keep.size 趋 0）。
        // 桌面版是「先按 tg_latency_ms 升序、再 records[:200]」，顺序决定保留谁。
        val file = tempFile()
        val store = KnownGoodStore(file)
        val entries = (0 until 300).map { i ->
            val host = "10.0.%d.%d".format(i / 256, i % 256)
            entry(host).copy(latencyMs = 1000 - i)   // i 越大延迟越小
        }

        store.save(entries)

        val loaded = store.load()
        assertEquals(Constants.KNOWN_GOOD_MAX_ENTRIES, loaded.size)
        val hosts = loaded.map { it.host }.toSet()
        assertTrue("最快的必须保留（i=299）", "10.0.1.43" in hosts)
        assertFalse("最慢的必须被截掉（i=0）", "10.0.0.0" in hosts)
    }
}
