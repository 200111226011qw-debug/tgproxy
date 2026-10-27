package com.tgproxy.data

import com.tgproxy.Constants
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 连续失败拉黑存储（v2.1.0：几次都不通的节点跳过测试，节省筛选时间）。
 *
 * 规则（与桌面版 v1.9.0 对齐）：
 * - 一轮里 TCP 预筛不通或 TG 两关未过 → 该节点连败计数 +1；
 * - TG 两关通过（含超门槛——通只是慢）→ 计数清零；
 * - 连败达到 [Constants.KNOWN_BAD_STREAK_LIMIT]（3）→ 拉黑：后续轮次跳过测试；
 * - 拉黑 [Constants.KNOWN_BAD_EXPIRY_MS]（48 小时）后自动解封重测（免费池 IP 会轮换/复活）；
 * - 条目超 [Constants.KNOWN_BAD_MAX_ENTRIES] 时按最久未失败淘汰，防无限膨胀。
 *
 * 时钟注入便于 JVM 单测虚拟推进；读写失败静默（黑名单是加速优化，坏了不挡主流程）。
 */
class KnownBadStore(
    private val file: File?,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    var enabled: Boolean = true

    /** 连败记录：host:port → (连败次数, 最近一次失败时间戳 ms)。 */
    private val fails = HashMap<String, Pair<Int, Long>>()

    private fun key(host: String, port: Int) = "$host:$port"

    /** 从磁盘载入（每轮刷新开始时调用一次）；开关关闭或文件损坏得到空表。 */
    @Synchronized
    fun load() {
        fails.clear()
        if (!enabled || file == null) return
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return
        }
        val array = try {
            JSONArray(raw)
        } catch (e: JSONException) {
            return
        }
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("key")
            val count = obj.optInt("fails", 0)
            val lastFailAt = obj.optLong("last_fail_at", 0L)
            if (key.isNotEmpty() && count > 0) {
                fails[key] = count to lastFailAt
            }
        }
    }

    /** 该节点当前是否处于拉黑中（连败达标且未过期）。 */
    @Synchronized
    fun isBlacklisted(host: String, port: Int): Boolean {
        val record = fails[key(host, port)] ?: return false
        val (count, lastFailAt) = record
        return count >= Constants.KNOWN_BAD_STREAK_LIMIT &&
            clockMs() - lastFailAt < Constants.KNOWN_BAD_EXPIRY_MS
    }

    /** 记一次失败（连败 +1）。只改内存，轮末统一 [persist] 落盘。 */
    @Synchronized
    fun recordFailure(host: String, port: Int) {
        if (!enabled) return
        val now = clockMs()
        val (count, _) = fails[key(host, port)] ?: (0 to 0L)
        fails[key(host, port)] = (count + 1) to now
    }

    /** 记一次通过（连败清零）。只改内存，轮末统一 [persist] 落盘。 */
    @Synchronized
    fun recordSuccess(host: String, port: Int) {
        if (!enabled) return
        fails.remove(key(host, port))
    }

    /** 轮末落盘：先清理过期与超量条目，再整表覆盖写；失败静默。 */
    @Synchronized
    fun persist() {
        if (!enabled || file == null) return
        compact(clockMs())
        val array = JSONArray()
        for ((key, record) in fails) {
            array.put(
                JSONObject()
                    .put("key", key)
                    .put("fails", record.first)
                    .put("last_fail_at", record.second),
            )
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(array.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // 黑名单落盘失败不影响本轮结果
        }
    }

    /** 清理过期条目（超过保质期的失败记录失去意义）与超量条目（按最久未失败淘汰）。 */
    private fun compact(now: Long) {
        fails.entries.removeIf { (_, record) ->
            now - record.second >= Constants.KNOWN_BAD_EXPIRY_MS
        }
        if (fails.size > Constants.KNOWN_BAD_MAX_ENTRIES) {
            val byOldest = fails.entries.sortedBy { it.value.second }
            for (i in 0 until fails.size - Constants.KNOWN_BAD_MAX_ENTRIES) {
                fails.remove(byOldest[i].key)
            }
        }
    }
}
