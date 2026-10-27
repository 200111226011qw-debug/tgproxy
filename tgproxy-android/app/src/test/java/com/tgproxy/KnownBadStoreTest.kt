package com.tgproxy

import com.tgproxy.data.KnownBadStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KnownBadStore 单测（v2.1.0 连续失败拉黑）：
 * 连败计数、通过清零、拉黑判定、48 小时解封、落盘回读、损坏容忍、容量淘汰。
 */
class KnownBadStoreTest {

    private fun tempFile(): File = File.createTempFile("known_bad", ".json").let {
        it.delete()
        File(it.absolutePath)
    }

    @Test
    fun `连败达到三次即拉黑`() {
        val store = KnownBadStore(tempFile())
        repeat(2) {
            store.recordFailure("1.2.3.4", 1080)
            assertFalse(store.isBlacklisted("1.2.3.4", 1080))
        }
        store.recordFailure("1.2.3.4", 1080)
        assertTrue(store.isBlacklisted("1.2.3.4", 1080))
    }

    @Test
    fun `通过清零连败`() {
        val store = KnownBadStore(tempFile())
        store.recordFailure("1.2.3.4", 1080)
        store.recordFailure("1.2.3.4", 1080)
        store.recordSuccess("1.2.3.4", 1080)
        store.recordFailure("1.2.3.4", 1080)
        assertFalse(store.isBlacklisted("1.2.3.4", 1080))
    }

    @Test
    fun `落盘后新实例回读仍拉黑`() {
        val file = tempFile()
        val store = KnownBadStore(file)
        repeat(3) { store.recordFailure("5.6.7.8", 1080) }
        store.persist()

        val reloaded = KnownBadStore(file)
        reloaded.load()
        assertTrue(reloaded.isBlacklisted("5.6.7.8", 1080))
        assertFalse(reloaded.isBlacklisted("9.9.9.9", 1080))
    }

    @Test
    fun `拉黑 48 小时后自动解封`() {
        var now = 1_000_000L
        val file = tempFile()
        val store = KnownBadStore(file) { now }
        repeat(3) { store.recordFailure("1.2.3.4", 1080) }
        store.persist()
        assertTrue(store.isBlacklisted("1.2.3.4", 1080))

        // 47 小时：仍拉黑；49 小时：解封
        now += 47 * 3_600_000L
        val before = KnownBadStore(file) { now }
        before.load()
        assertTrue(before.isBlacklisted("1.2.3.4", 1080))
        now += 2 * 3_600_000L
        val after = KnownBadStore(file) { now }
        after.load()
        assertFalse(after.isBlacklisted("1.2.3.4", 1080))
    }

    @Test
    fun `开关关闭时不读不写`() {
        val file = tempFile()
        val store = KnownBadStore(file).apply { enabled = false }
        repeat(3) { store.recordFailure("1.2.3.4", 1080) }
        store.persist()
        assertFalse(file.exists())

        store.recordFailure("1.2.3.4", 1080)
        assertFalse(store.isBlacklisted("1.2.3.4", 1080))
    }

    @Test
    fun `损坏文件容忍为空表`() {
        val file = tempFile()
        file.writeText("not-json{")
        val store = KnownBadStore(file)
        store.load()
        assertFalse(store.isBlacklisted("1.2.3.4", 1080))
    }

    @Test
    fun `超容量按最久未失败淘汰`() {
        var now = 1_000_000L
        val store = KnownBadStore(tempFile()) { now }
        // 最早失败的节点在容量压力下应被淘汰
        repeat(3) { store.recordFailure("1.0.0.1", 1080) }
        now += 1_000L
        for (i in 2..Constants.KNOWN_BAD_MAX_ENTRIES + 1) {
            repeat(3) { store.recordFailure("1.0.0.$i", 1080) }
            now += 1_000L
        }
        store.persist()
        store.load()
        assertFalse("最早失败节点被淘汰", store.isBlacklisted("1.0.0.1", 1080))
        assertTrue("最新失败节点保留", store.isBlacklisted("1.0.0.${Constants.KNOWN_BAD_MAX_ENTRIES + 1}", 1080))
    }
}
