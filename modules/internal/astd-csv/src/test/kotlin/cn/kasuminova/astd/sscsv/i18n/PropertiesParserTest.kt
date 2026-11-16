package cn.kasuminova.astd.sscsv.i18n

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PropertiesParserTest {

    private val source = "i18n/test.properties"

    private fun parse(text: String): ParsedProperties =
        PropertiesParser.parse(source, StringReader(text))

    @Test
    fun `识别单文件重复键及两处行号`() {
        val parsed = parse(
            """
            weapon.a.tooltip.customPrimary=第一次
            weapon.a.tooltip.customPrimary=第二次
            """.trimIndent()
        )

        assertEquals("第二次", parsed.values.getValue("weapon.a.tooltip.customPrimary"))
        assertEquals(
            listOf(DuplicateStringKey("weapon.a.tooltip.customPrimary", "$source:1", "$source:2")),
            parsed.duplicates,
        )
    }

    @Test
    fun `注释空行与换行续行不影响键解析`() {
        val parsed = parse(
            """
            # 注释
            ! 另一种注释

            weapon.a.tooltip.customPrimary=第一段\n第二段\
                 续行内容
            """.trimIndent()
        )

        assertEquals(emptyList(), parsed.duplicates)
        assertEquals(
            "第一段\n第二段续行内容",
            parsed.values.getValue("weapon.a.tooltip.customPrimary"),
        )
    }

    @Test
    fun `支持等号冒号与空白分隔并保留转义字符`() {
        val parsed = parse(
            """
            a=1
            b : 2
            c 3
            d=%redHullDamage% \| {{%value%}} 50%%
            e=\u4e2d\u6587
            """.trimIndent()
        )

        assertEquals("1", parsed.values.getValue("a"))
        assertEquals("2", parsed.values.getValue("b"))
        assertEquals("3", parsed.values.getValue("c"))
        assertEquals("%redHullDamage% | {{%value%}} 50%%", parsed.values.getValue("d"))
        assertEquals("中文", parsed.values.getValue("e"))
    }

    @Test
    fun `续行展开后再登记行号并按首行定位`() {
        val parsed = parse(
            """
            k=第一行\
              第二行
            k=覆盖
            """.trimIndent()
        )

        assertEquals("覆盖", parsed.values.getValue("k"))
        assertEquals(3, parsed.lineNumbers.getValue("k"))
        assertEquals(listOf(DuplicateStringKey("k", "$source:1", "$source:3")), parsed.duplicates)
    }

    @Test
    fun `仅ASCII空白参与键值切分`() {
        val value = "\u3000全角空格\u00A0与不换行空格"
        val parsed = parse("k=$value")

        assertEquals(value, parsed.values.getValue("k"))
    }

    @Test
    fun `全角空格与不换行空格不作为键分隔符`() {
        assertEquals("c", parse("a\u3000b=c").values.getValue("a\u3000b"))
        assertEquals("c", parse("a\u00A0b=c").values.getValue("a\u00A0b"))
    }

    @Test
    fun `非ASCII空白开头的行不按空行跳过`() {
        assertEquals("1", parse("\u3000k=1").values.getValue("\u3000k"))
    }

    @Test
    fun `续行只跳过ASCII前导空白`() {
        assertEquals("ab", parse("k=a\\\n \t b").values.getValue("k"))
        assertEquals("a\u3000b", parse("k=a\\\n\u3000b").values.getValue("k"))
    }

    @Test
    fun `键前后空白按ASCII规则跳过`() {
        val parsed = parse(" \t k \t = \t v")

        assertEquals(mapOf("k" to "v"), parsed.values)
    }

    @Test
    fun `缺少键的条目失败`() {
        assertFailsWith<TooltipI18nException> { parse("=没有键") }
    }

    @Test
    fun `unicode转义必须是四位十六进制`() {
        for (bad in listOf("k=\\u+123", "k=\\u-123", "k=\\u12G4", "k=\\u 123", "k=\\uZZZZ", "k=\\u12")) {
            assertFailsWith<TooltipI18nException>(bad) { parse(bad) }
        }

        assertEquals("中", parse("k=\\u4e2d").values.getValue("k"))
        assertEquals("A", parse("k=\\u0041").values.getValue("k"))
    }
}
