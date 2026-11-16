package cn.kasuminova.astd.sscsv.tooltip

import kotlin.test.Test
import kotlin.test.assertEquals

class NamedTemplateTest {

    @Test
    fun `展开只替换登记变量且不递归解析替换值`() {
        val expanded = NamedTemplate.expand(
            "%outer%",
            mapOf("outer" to "%inner%", "inner" to "99%"),
            collapseEscapes = true,
        )

        assertEquals("%inner%", expanded)
    }

    @Test
    fun `重复变量按出现次数展开`() {
        assertEquals(
            "2% + 2%",
            NamedTemplate.expand("%v% + %v%", mapOf("v" to "2%"), collapseEscapes = true),
        )
    }

    @Test
    fun `归一模式折叠转义百分号并保留原生占位符`() {
        assertEquals(
            "命中率 50%，造成 {%s} 伤害，转义 %，变量 12%",
            NamedTemplate.expand(
                "命中率 50%，造成 {%s} 伤害，转义 %%，变量 %damage%",
                mapOf("damage" to "12%"),
                collapseEscapes = true,
            ),
        )
    }

    @Test
    fun `保留模式不改动转义百分号与正文`() {
        assertEquals(
            "提升 50%% 且保留 {%%s} 与 50%",
            NamedTemplate.expand(
                "提升 50%% 且保留 {%%s} 与 50%",
                emptyMap(),
                collapseEscapes = false,
            ),
        )
    }

    @Test
    fun `替换值中的百分号不再参与转义处理`() {
        assertEquals(
            "150%% 与 %",
            NamedTemplate.expand("%a% 与 %b%", mapOf("a" to "150%%", "b" to "%"), collapseEscapes = true),
        )
    }

    @Test
    fun `未知变量保持原文`() {
        assertEquals("值 %unknown%", NamedTemplate.expand("值 %unknown%", emptyMap(), collapseEscapes = true))
    }

    @Test
    fun `槽数量按原生占位符统计`() {
        assertEquals(2, NamedTemplate.slotCount("{%s} 与 {%s}，以及不完整的 {%s"))
        assertEquals(0, NamedTemplate.slotCount("无槽位"))
    }

    @Test
    fun `变量扫描跳过转义百分号且忽略普通百分号文本`() {
        assertEquals(
            listOf("damage"),
            NamedTemplate.variables("%damage% 与 %%escaped%% 以及 50%"),
        )
    }
}
