package com.tgproxy.data

import com.tgproxy.Constants
import com.tgproxy.model.RoundRecord
import com.tgproxy.model.StageDurations
import com.tgproxy.model.Stats
import com.tgproxy.model.TgGateStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * 轮次记录存储（JSON Lines：每行一个 JSON 对象）。
 *
 * 为什么是 JSONL 而不是一个 JSON 数组：
 * - 单行损坏只丢一行。数组格式下一个字符截断就整份解析失败，历史全没了；
 * - 追加语义天然（虽然本实现为了截断会重写，但读侧仍逐行容错）。
 *
 * 写入策略：**读全部 → 追加 → 截断到 [maxEntries] → 整份原子重写**。
 * 每轮一次，50 行约 10KB，代价可忽略；换来的是「截断」和「原子性」同时成立。
 * 纯追加虽然更快，但超限后就地截断更麻烦（要处理半行），不划算。
 *
 * 失败一律静默：记日志失败不该影响刷新结果。写失败时仍把本轮记录返回给调用方，
 * 界面照常显示 —— 只是重启后会丢（这点如实写在类注释里，不假装持久化了）。
 */
class RoundLogger(
    private val file: File?,
    private val maxEntries: Int = Constants.ROUND_LOG_MAX_ENTRIES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 串行化「读-改-写」。
     *
     * 没有它，两轮几乎同时结束时会各读一份旧内容再各自写回，后写的把先写的
     * 覆盖掉（丢一条记录，且 seq 可能重复）。刷新本身有互斥，但日志写入发生在
     * 互斥区之外，所以这里要自己守。
     */
    private val writeLock = Mutex()

    /** 读取全部记录，按写入顺序（旧 → 新）。文件缺失/损坏返回空表。 */
    suspend fun load(): List<RoundRecord> {
        // 禁用态（无文件）不走 Dispatchers.IO：没有 IO 就别调度线程切换。
        // 这不只是省开销 —— 测试里 withContext 会跳出虚拟时间，调用方若正持有
        // 互斥锁，锁的释放就要等真实线程调度，`advanceTimeBy` 直接失效。
        if (file == null) return emptyList()
        return withContext(io) { readAll() }
    }

    /**
     * 追加一条记录，返回追加后的完整列表（旧 → 新，已截断）。
     *
     * 返回列表而不是让调用方再读一次：省一次 IO，也避免「写完立刻读」之间
     * 的窗口期读到旧内容。
     */
    suspend fun append(record: RoundRecord): List<RoundRecord> {
        if (file == null) return emptyList()
        return withContext(io) {
            writeLock.withLock {
                val existing = readAll()
                // seq 单调递增：UI 用它当列表 key，必须稳定且唯一
                val next = record.copy(seq = (existing.maxOfOrNull { it.seq } ?: 0L) + 1L)
                val merged = (existing + next).takeLast(maxEntries)
                writeAll(merged)
                merged
            }
        }
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    private fun readAll(): List<RoundRecord> {
        val target = file ?: return emptyList()
        val raw = try {
            target.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return emptyList()
        }
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<RoundRecord>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            // 逐行容错：坏行跳过，不影响其余行（JSONL 的核心优势）
            val obj = try {
                JSONObject(line)
            } catch (e: JSONException) {
                continue
            }
            out.add(recordFromJson(obj) ?: continue)
        }
        return out
    }

    private fun recordFromJson(obj: JSONObject): RoundRecord? {
        val timestamp = obj.optString("ts")
        // 没有时间戳的行无法定位，跳过
        if (timestamp.isEmpty()) return null
        return RoundRecord(
            timestamp = timestamp,
            success = obj.optBoolean("ok", false),
            error = if (obj.isNull("error")) null else obj.optString("error").ifEmpty { null },
            elapsedMs = obj.optLong("elapsed_ms", 0L),
            stats = statsFromJson(obj.optJSONObject("stats")),
            tgStats = tgStatsFromJson(obj.optJSONObject("tg")),
            durations = durationsFromJson(obj.optJSONObject("durations")),
            seq = obj.optLong("seq", 0L),
        )
    }

    private fun statsFromJson(obj: JSONObject?): Stats {
        if (obj == null) return Stats()
        return Stats(
            available = obj.optInt("available", 0),
            unverified = obj.optInt("unverified", 0),
            tgFailed = obj.optInt("tg_failed", 0),
            overThreshold = obj.optInt("over_threshold", 0),
            dropped = obj.optInt("dropped", 0),
            excluded = obj.optInt("excluded", 0),
            blacklisted = obj.optInt("blacklisted", 0),
            tested = obj.optInt("tested", 0),
        )
    }

    private fun tgStatsFromJson(obj: JSONObject?): TgGateStats {
        if (obj == null) return TgGateStats()
        return TgGateStats(
            dcFail = obj.optInt("dc_fail", 0),
            latencyRejected = obj.optInt("latency_rejected", 0),
            webFail = obj.optInt("web_fail", 0),
            ok = obj.optInt("ok", 0),
        )
    }

    private fun durationsFromJson(obj: JSONObject?): StageDurations {
        if (obj == null) return StageDurations()
        return StageDurations(
            fetchMs = obj.optLong("fetch_ms", 0L),
            prepareMs = obj.optLong("prepare_ms", 0L),
            tcpMs = obj.optLong("tcp_ms", 0L),
            tgMs = obj.optLong("tg_ms", 0L),
            commitMs = obj.optLong("commit_ms", 0L),
            totalMs = obj.optLong("total_ms", 0L),
        )
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    private fun writeAll(records: List<RoundRecord>) {
        val target = file ?: return
        val content = records.joinToString("\n") { recordToJson(it).toString() } + "\n"
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(content, Charsets.UTF_8)
            // POSIX 上 rename 覆盖是原子的（Android 就是 POSIX）；
            // Windows 上目标存在时 rename 会失败，退化成先删再改——单测跑在
            // Windows 上也能过，代价是那一瞬间不原子（仅影响本地测试环境）。
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    // 两次都失败：退化为直接覆盖，至少内容是对的
                    target.writeText(content, Charsets.UTF_8)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            // 落盘失败不影响本轮结果；内存里的列表照常返回给界面
        }
    }

    private fun recordToJson(record: RoundRecord): JSONObject = JSONObject()
        .put("ts", record.timestamp)
        .put("ok", record.success)
        .put("error", record.error ?: JSONObject.NULL)
        .put("elapsed_ms", record.elapsedMs)
        .put("seq", record.seq)
        .put("stats", JSONObject().apply {
            put("available", record.stats.available)
            put("unverified", record.stats.unverified)
            put("tg_failed", record.stats.tgFailed)
            put("over_threshold", record.stats.overThreshold)
            put("dropped", record.stats.dropped)
            put("excluded", record.stats.excluded)
            put("blacklisted", record.stats.blacklisted)
            put("tested", record.stats.tested)
        })
        .put("tg", JSONObject().apply {
            put("dc_fail", record.tgStats.dcFail)
            put("latency_rejected", record.tgStats.latencyRejected)
            put("web_fail", record.tgStats.webFail)
            put("ok", record.tgStats.ok)
        })
        .put("durations", JSONObject().apply {
            put("fetch_ms", record.durations.fetchMs)
            put("prepare_ms", record.durations.prepareMs)
            put("tcp_ms", record.durations.tcpMs)
            put("tg_ms", record.durations.tgMs)
            put("commit_ms", record.durations.commitMs)
            put("total_ms", record.durations.totalMs)
        })

    companion object {
        /** 禁用态实例（无文件）：load 恒空、append 恒空操作。测试与降级用。 */
        fun none(): RoundLogger = RoundLogger(file = null)

        /** 轮次时间戳，格式与 `updatedAt` 一致（`yyyy-MM-dd HH:mm:ss`）。 */
        fun formatTimestamp(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }
}
