package cn.kasuminova.astd.sscsv.i18n

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class StringTableMergeTest {

    private fun source(name: String, text: String) =
        ParsedPropertiesWithSource(name, PropertiesParser.parse(name, StringReader(text)))

    @Test
    fun `跨文件重复键报告两个来源位置`() {
        val merged = StringTableMerge.merge(
            listOf(
                source("i18n/zh-cn.properties", "weapon.a.tooltip.customPrimary=旧\nonly.base=1"),
                source("i18n/tooltip-weapons.zh-cn.properties", "weapon.a.tooltip.customPrimary=新"),
            )
        )

        assertEquals("新", merged.values.getValue("weapon.a.tooltip.customPrimary"))
        assertEquals(
            listOf(
                DuplicateStringKey(
                    key = "weapon.a.tooltip.customPrimary",
                    first = "i18n/zh-cn.properties:1",
                    duplicate = "i18n/tooltip-weapons.zh-cn.properties:1",
                )
            ),
            merged.duplicates,
        )
    }

    @Test
    fun `单文件重复与跨文件重复都进入诊断`() {
        val merged = StringTableMerge.merge(
            listOf(
                source("i18n/zh-cn.properties", "k=1\nk=2"),
                source("i18n/tooltip-weapons.zh-cn.properties", "k=3"),
            )
        )

        assertEquals(
            listOf(
                DuplicateStringKey("k", "i18n/zh-cn.properties:1", "i18n/zh-cn.properties:2"),
                DuplicateStringKey("k", "i18n/zh-cn.properties:2", "i18n/tooltip-weapons.zh-cn.properties:1"),
            ),
            merged.duplicates,
        )
    }
}
