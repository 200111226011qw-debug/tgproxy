package com.tgproxy

import com.tgproxy.data.ProxyParser
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 双端对拍（跨语言 golden）：读取 docs/vectors.json（由 Python
 * qa_parser_vectors.py 固化的「输入 + 期望输出」契约），用 Kotlin ProxyParser
 * 重放并逐字段比对，关闭「两套实现只有一套被测试」的缺口。
 *
 * vectors.json 定位：从工作目录向上查找 tgproxy-android/docs/（兼容不同 user.dir），
 * 找不到时回退测试类路径资源。org.json 在 AGP 单元测试中提供真实实现。
 *
 * 解析器行为变化时：Python 侧 `python qa_parser_vectors.py --update` 重新固化，
 * 本测试将在下一次运行中暴露两端的不一致。
 */
class ProxyParserGoldenTest {

    private val parser = ProxyParser()

    @Test
    fun `解析结果与 Python golden vectors json 逐字段一致`() {
        val golden = loadGolden()
        val names = golden.keys().asSequence().sorted().toList()
        assertTrue("golden 向量为空", names.isNotEmpty())
        for (name in names) {
            val record = golden.getJSONObject(name)
            val input: String? =
                if (record.isNull("input")) null else record.getString("input")
            val expected = record.getJSONObject("expected")
            val result = parser.parse(input)

            val message = { detail: String -> "向量 $name $detail" }
            assertEquals(
                message("excluded 不一致"),
                expected.getInt("excluded"),
                result.excludedCount,
            )
            assertEquals(
                message("dropped 不一致"),
                expected.getInt("dropped"),
                result.droppedCount,
            )
            val expectedEntries = expected.getJSONArray("entries")
            assertEquals(
                message("条目数不一致"),
                expectedEntries.length(),
                result.entries.size,
            )
            for (i in 0 until expectedEntries.length()) {
                val expectedEntry = expectedEntries.getJSONObject(i)
                val entry = result.entries[i]
                assertEquals(
                    message("entries[$i].link 不一致"),
                    expectedEntry.getString("link"),
                    entry.link,
                )
                assertEquals(
                    message("entries[$i].host 不一致"),
                    expectedEntry.getString("host"),
                    entry.host,
                )
                assertEquals(
                    message("entries[$i].port 不一致"),
                    expectedEntry.getInt("port"),
                    entry.port,
                )
                assertEquals(
                    message("entries[$i].type 不一致"),
                    expectedEntry.getString("type"),
                    entry.type.name.lowercase(),
                )
                assertEquals(
                    message("entries[$i].location 不一致"),
                    expectedEntry.getString("location"),
                    entry.location,
                )
                assertEquals(
                    message("entries[$i].isp 不一致"),
                    expectedEntry.getString("isp"),
                    entry.isp,
                )
                assertEquals(
                    message("entries[$i].added_at 不一致"),
                    expectedEntry.getString("added_at"),
                    entry.addedAt,
                )
            }
        }
    }

    /** 优先读落盘文件（单一事实源），找不到再回退类路径资源。 */
    private fun loadGolden(): JSONObject {
        locateGoldenFile()?.let { return JSONObject(it.readText()) }
        val stream = javaClass.getResourceAsStream("/vectors.json")
            ?: throw AssertionError(
                "vectors.json 未找到：既无 tgproxy-android/docs/ 落盘文件，也无类路径资源。" +
                    "请先运行 python qa_parser_vectors.py --update 固化 golden",
            )
        return JSONObject(stream.bufferedReader().readText())
    }

    /** 从工作目录向上最多 6 级查找 docs/vectors.json 或 tgproxy-android/docs/vectors.json。 */
    private fun locateGoldenFile(): File? {
        var dir: File = File(System.getProperty("user.dir") ?: return null).absoluteFile
        repeat(6) {
            listOf(
                File(dir, "docs/vectors.json"),
                File(dir, "tgproxy-android/docs/vectors.json"),
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile ?: return null
        }
        return null
    }
}
