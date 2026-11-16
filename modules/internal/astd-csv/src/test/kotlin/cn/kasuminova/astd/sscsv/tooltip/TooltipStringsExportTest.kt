package cn.kasuminova.astd.sscsv.tooltip

import cn.kasuminova.astd.sscsv.SsCsvEntry
import cn.kasuminova.astd.sscsv.i18n.DuplicateStringKey
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TooltipStringsExportTest {

    private fun exported(
        source: Map<String, String>,
        entries: List<SsCsvEntry>,
    ): TooltipRegistry = TooltipRegistry.parse(source).also { it.validateTargets(entries) }

    @Test
    fun `导出包含原始模板与默认值`() {
        val registry = exported(
            weaponStrings() + systemStrings(),
            listOf(TestWeaponEntry, TestSystemEntry, TestSystemDescription),
        )

        val strings = registry.exportedStrings()

        assertEquals(
            "命中时额外附加目标最大船体值 {%s} 的高爆伤害。",
            strings.getValue("weapon.$TEST_WEAPON_ID.tooltip.customPrimary"),
        )
        assertEquals("2%", strings.getValue("tooltip.weapon.$TEST_WEAPON_ID.default.redHullDamage"))
        assertEquals("系统持续期间获得 {{%damageReduction%}} 固定减伤。", strings.getValue("desc.$TEST_SYSTEM_ID.text5"))
    }

    @Test
    fun `导出使用动态声明ID而非注册声明本身`() {
        val registry = TooltipRegistry.parse(weaponStrings() + systemStrings())

        val strings = registry.exportedStrings()

        assertEquals(TEST_WEAPON_ID, strings.getValue("tooltip.weapon.ids"))
        assertEquals(TEST_SYSTEM_ID, strings.getValue("tooltip.system.ids"))
        assertFalse(strings.containsKey("dynamic.weapons"))
        assertFalse(strings.containsKey("dynamic.systems"))
    }

    @Test
    fun `未登记目标类型的覆盖清单输出空值`() {
        val registry = TooltipRegistry.parse(weaponStrings())

        val strings = registry.exportedStrings()

        assertEquals(TEST_WEAPON_ID, strings.getValue("tooltip.weapon.ids"))
        assertEquals("", strings.getValue("tooltip.system.ids"))
    }

    @Test
    fun `导出JSON保留未展开的高亮模板`() {
        val files = TooltipStringsExport.files(
            registry = TooltipRegistry.parse(weaponStrings()),
            csvEntries = listOf(TestWeaponEntry),
            duplicateStringKeys = emptyList(),
        )

        val file = files.single()
        assertContains(file.content, "\"weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL\": \"%redHullDamage%\"")
    }

    @Test
    fun `JSON转义模板中的换行与引号`() {
        val registry = TooltipRegistry.parse(
            systemStrings(
                mapOf(
                    "desc.$TEST_SYSTEM_ID.text4" to "第一行\n\"第二行\"",
                )
            )
        )

        val file = TooltipStringsExport.files(
            registry = registry,
            csvEntries = listOf(TestSystemEntry, TestSystemDescription),
            duplicateStringKeys = emptyList(),
        ).single()

        assertContains(file.content, "\"desc.$TEST_SYSTEM_ID.text4\": \"第一行\\n\\\"第二行\\\"\"")
    }

    @Test
    fun `字符串键重复时报告键来源与两处行号`() {
        val error = assertFailsWith<TooltipTemplateException> {
            TooltipStringsExport.files(
                registry = TooltipRegistry.parse(weaponStrings()),
                csvEntries = listOf(TestWeaponEntry),
                duplicateStringKeys = listOf(
                    DuplicateStringKey(
                        key = "weapon.$TEST_WEAPON_ID.tooltip.customPrimary",
                        first = "i18n/zh-cn.properties:118",
                        duplicate = "i18n/tooltip-weapons.zh-cn.properties:3",
                    )
                ),
            )
        }

        assertContains(error.message.orEmpty(), "weapon.$TEST_WEAPON_ID.tooltip.customPrimary")
        assertContains(error.message.orEmpty(), "i18n/zh-cn.properties:118")
        assertContains(error.message.orEmpty(), "i18n/tooltip-weapons.zh-cn.properties:3")
    }
}
