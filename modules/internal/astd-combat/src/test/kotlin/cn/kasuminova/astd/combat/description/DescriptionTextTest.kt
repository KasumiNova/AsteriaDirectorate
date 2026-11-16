package cn.kasuminova.astd.combat.description

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DescriptionTextTest {
    @Test
    fun `命名参数重复出现时全部替换但替换值不递归解析`() {
        val template = "{{%amount%}} / %amount% / %other%"
        val values = mapOf("amount" to "%other%", "other" to "20%")
        assertEquals(setOf("amount", "other"), DescriptionTemplate.parameters(template))
        assertEquals("{{%other%}} / %other% / 20%", DescriptionTemplate.render(template, values, "sample"))
    }

    @Test
    fun `转义百分号和原生槽不作为命名参数`() {
        assertEquals(setOf("value"), DescriptionTemplate.parameters("%%literal%% {%s} %value%"))
        assertEquals("%literal% {%s} 12%", DescriptionTemplate.render("%%literal%% {%s} %value%", mapOf("value" to "12%"), "sample"))
    }

    @Test
    fun `缺失变量携带字段与变量定位`() {
        val error = assertFailsWith<IllegalArgumentException> {
            DescriptionTemplate.render("%damage% / %range%", mapOf("damage" to "100"), "weapon.sample")
        }
        assertTrue(error.message.orEmpty().contains("weapon.sample"))
        assertTrue(error.message.orEmpty().contains("range"))
    }

    @Test
    fun `原生参数按槽位顺序解析并保留重复值`() {
        assertEquals(listOf("10%", "10%", "3"), DescriptionTemplate.highlights("{%s} / {%s}\n{%s}", "10% | 10% | 3", "sample"))
        assertEquals(emptyList(), DescriptionTemplate.highlights("plain", null, "sample"))
    }

    @Test
    fun `原生参数拒绝数量错误及空分段`() {
        for (highlights in listOf("1", "1|2|3", "1|", "|2", "1|\n2")) {
            assertFailsWith<IllegalArgumentException>(highlights) {
                DescriptionTemplate.highlights("{%s} {%s}", highlights, "sample")
            }
        }
        assertFailsWith<IllegalArgumentException> { DescriptionTemplate.highlights(null, "1", "sample") }
    }

    @Test
    fun `武器模板保留正文与高亮参数中的原始空白`() {
        val text = renderer()
        val result = text.weaponBase("sample", mapOf("power" to "20%"), original())
        assertEquals("100%% native {%s}", result.primary)
        assertEquals(" 20% ", result.primaryHighlights)
        assertEquals("original {%s}", result.ancillary)
        assertEquals("fixed", result.ancillaryHighlights)
    }

    @Test
    fun `连续改档从原始模板展开变量`() {
        val text = renderer()
        val snapshot = original()
        val low = text.weaponBase("sample", mapOf("power" to "20%"), snapshot)
        val high = text.weaponBase("sample", mapOf("power" to "50%"), snapshot)
        assertNotEquals(low, high)
        repeat(5) {
            assertEquals(low, text.weaponBase("sample", mapOf("power" to "20%"), snapshot))
        }
    }

    @Test
    fun `系统模板只展开命名参数并保留换行与标记`() {
        val result = renderer().systemBase("sample", mapOf("power" to "20%"))
        assertEquals("intro {{20%}}", result.text1)
        assertEquals(result.text1, result.text3)
        assertEquals(null, result.text4)
        assertEquals("detail {{20%}}\nnext", result.text5)
    }

    private fun original() = WeaponDescriptionText("old", null, "original {%s}", "fixed")

    private fun renderer(): DescriptionText {
        val templates = mapOf(
            "weapon.sample.tooltip.customPrimary" to "100%% native {%s}",
            "weapon.sample.tooltip.customPrimaryHL" to " %power% ",
            "weapon.sample.tooltip.customAncillary" to "",
            "weapon.sample.tooltip.customAncillaryHL" to "",
            "desc.sample.text1" to "intro {{%power%}}",
            "desc.sample.text3" to "intro {{%power%}}",
            "desc.sample.text4" to "",
            "desc.sample.text5" to "detail {{%power%}}\nnext",
        )
        return DescriptionText(templates::getValue)
    }
}
