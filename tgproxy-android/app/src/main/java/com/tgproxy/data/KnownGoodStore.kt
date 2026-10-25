package com.tgproxy.data

import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyStatus
import com.tgproxy.model.ProxyType
import com.tgproxy.model.TgVerdict
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * known_good 沉淀存储（等价桌面版 known_good.json 的读/写/合并）。
 *
 * - [enabled] 关闭时：不读、不写、不合并（文件保留不动）——开关语义与桌面版一致；
 * - 读：缺失/损坏返回空表；条目逐项校验，坏条目跳过；
 * - 写：整表覆盖，延迟/状态不落盘（下轮一律重测）。
 *
 * Android 运行时自带 org.json，JVM 单测靠 testImplementation 的 org.json。
 */
class KnownGoodStore(private val file: File?) {

    var enabled: Boolean = true

    /** 读取上轮验证通过的节点；开关关闭或文件缺失/损坏返回空表。 */
    fun load(): List<ProxyEntry> {
        if (!enabled || file == null) return emptyList()
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return emptyList()
        }
        val array = try {
            JSONArray(raw)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = mutableListOf<ProxyEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val entry = entryFromJson(obj) ?: continue
            out.add(entry)
        }
        return out
    }

    /** 沉淀本轮验证通过的节点；开关关闭不写。任何写失败静默（下轮再试）。 */
    fun save(entries: List<ProxyEntry>) {
        if (!enabled || file == null || entries.isEmpty()) return
        val array = JSONArray()
        for (entry in entries) {
            array.put(entryToJson(entry))
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(array.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // 落盘失败不影响本轮结果；下轮会再试
        }
    }

    private fun entryToJson(entry: ProxyEntry): JSONObject = JSONObject()
        .put("link", entry.link)
        .put("host", entry.host)
        .put("port", entry.port)
        .put("type", entry.type.name)
        .put("location", entry.location)
        .put("isp", entry.isp)
        .put("added_at", entry.addedAt)

    private fun entryFromJson(obj: JSONObject): ProxyEntry? {
        val link = obj.optString("link")
        val host = obj.optString("host")
        val port = obj.optInt("port", -1)
        val typeName = obj.optString("type")
        if (link.isEmpty() || host.isEmpty() || port !in 1..65535) return null
        val type = try {
            ProxyType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return ProxyEntry(
            link = link,
            host = host,
            port = port,
            type = type,
            latencyMs = null,
            status = ProxyStatus.UNAVAILABLE,
            tgVerdict = TgVerdict.UNVERIFIED,
            location = obj.optString("location"),
            isp = obj.optString("isp"),
            addedAt = obj.optString("added_at"),
        )
    }

    companion object {
        /** 禁用态实例（无文件且关闭）：load 恒空、save 恒空操作。 */
        fun none(): KnownGoodStore = KnownGoodStore(file = null).apply { enabled = false }
    }
}
