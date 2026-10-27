package com.tgproxy

import com.tgproxy.model.ThemeMode
import com.tgproxy.ui.nextThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主题循环：SYSTEM → LIGHT → DARK → SYSTEM。
 *
 * 这条循环是「点一下主题那一行会发生什么」的全部定义。写成表驱动，
 * 三档里任何一档接错（比如 LIGHT 直接跳回 SYSTEM，跳过 DARK）都会立刻红。
 */
class ThemeCycleTest {

    @Test
    fun `三档循环闭合并按固定顺序`() {
        val expected = mapOf(
            ThemeMode.SYSTEM to ThemeMode.LIGHT,
            ThemeMode.LIGHT to ThemeMode.DARK,
            ThemeMode.DARK to ThemeMode.SYSTEM,
        )
        expected.forEach { (from, to) ->
            assertEquals("$from 的下一档应当是 $to", to, nextThemeMode(from))
        }
    }

    @Test
    fun `连续点三次回到原点`() {
        // 坦白说这条被上一条蕴含：映射表全对，三阶循环必然成立。
        // 留着它是因为「连点三次回到原样」是用户视角的说法，真改坏时这行
        // 报错信息比表驱动的断言更容易看懂。不把它当作独立证据。
        ThemeMode.entries.forEach { start ->
            val after3 = nextThemeMode(nextThemeMode(nextThemeMode(start)))
            assertEquals("$start 连点三次应回到自身", start, after3)
        }
    }
}
