package cn.kasuminova.astd.sscsv.tooltip

import cn.kasuminova.astd.sscsv.entries.DescriptionEntry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TooltipRegistryTest {

    @Test
    fun `武器模板按本ID默认值展开`() {
        val registry = TooltipRegistry.parse(weaponStrings())

        val primaryKey = "weapon.$TEST_WEAPON_ID.tooltip.customPrimary"
        val primaryHLKey = "weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL"
        // 武器正文保留原生 {%s}，动态值只落在高亮参数里，由游戏按槽位顺序填充。
        assertEquals("命中时额外附加目标最大船体值 {%s} 的高爆伤害。", registry.expandField(primaryKey))
        assertEquals("2%", registry.expandField(primaryHLKey))

        val entry = registry.entries.single()
        assertEquals(mapOf("redHullDamage" to "2%"), entry.defaults)
        assertEquals("命中时额外附加目标最大船体值 {%s} 的高爆伤害。", entry.fields.getValue("customPrimary"))
    }

    @Test
    fun `武器高亮模板折叠转义百分号而正文保持原样`() {
        val primaryKey = "weapon.$TEST_WEAPON_ID.tooltip.customPrimary"
        val primaryHLKey = "weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL"
        val registry = TooltipRegistry.parse(
            weaponStrings(
                mapOf(
                    primaryKey to "射程 50% 内造成 {%s} 伤害并转义 %% 标记。",
                    primaryHLKey to "150%%",
                    "tooltip.weapon.$TEST_WEAPON_ID.default.redHullDamage" to "150%",
                )
            )
        )

        assertEquals("150%", registry.expandField(primaryHLKey))
        assertEquals("射程 50% 内造成 {%s} 伤害并转义 %% 标记。", registry.expandField(primaryKey))
        assertEquals("150%%", registry.entries.single().fields.getValue("customPrimaryHL"))
    }

    @Test
    fun `未登记字段不参与展开`() {
        val registry = TooltipRegistry.parse(weaponStrings())

        assertEquals(null, registry.expandField("weapon.$TEST_WEAPON_ID.name"))
    }

    @Test
    fun `系统模板保留原生高亮并展开参数`() {
        val registry = TooltipRegistry.parse(systemStrings())

        assertEquals(
            "系统持续期间获得 {{40%}} 固定减伤。",
            registry.expandField("desc.$TEST_SYSTEM_ID.text5"),
        )
        assertEquals(
            "系统持续期间获得 {{%damageReduction%}} 固定减伤。",
            registry.entries.single().fields.getValue("text5"),
        )
    }

    @Test
    fun `重复声明目标失败`() {
        val source = weaponStrings() + ("dynamic.weapons" to "$TEST_WEAPON_ID,$TEST_WEAPON_ID")

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "kind=weapon")
        assertContains(error.message.orEmpty(), "id=$TEST_WEAPON_ID")
    }

    @Test
    fun `模板变量缺少默认值失败`() {
        val source = weaponStrings() - "tooltip.weapon.$TEST_WEAPON_ID.default.redHullDamage"

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "missing tooltip parameter default")
        assertContains(error.message.orEmpty(), "var=redHullDamage")
    }

    @Test
    fun `未声明目标的参数键失败`() {
        val source = weaponStrings() + ("tooltip.weapon.ghost.default.reach" to "100su")

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "id=ghost")
        assertContains(error.message.orEmpty(), "tooltip.weapon.ghost.default.reach")
    }

    @Test
    fun `高亮模板引用未登记变量失败`() {
        val key = "weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL"
        val source = weaponStrings() + (key to "%missingDamage%")

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "id=$TEST_WEAPON_ID")
        assertContains(error.message.orEmpty(), "field=customPrimaryHL")
        assertContains(error.message.orEmpty(), "var=missingDamage")
    }

    @Test
    fun `武器正文引用命名变量失败`() {
        val key = "weapon.$TEST_WEAPON_ID.tooltip.customPrimary"
        val source = weaponStrings() + (key to "造成 %redHullDamage% 的额外伤害，{%s}")

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "field=customPrimary")
        assertContains(error.message.orEmpty(), "var=redHullDamage")
    }

    @Test
    fun `正文槽数量与高亮参数数量不一致失败`() {
        val key = "weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL"
        val source = weaponStrings() + (key to "%redHullDamage% | 额外参数")

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "field=customPrimaryHL")
        assertContains(error.message.orEmpty(), "1 {%s} slot(s)")
        assertContains(error.message.orEmpty(), "2 parameter(s)")
    }

    @Test
    fun `高亮参数存在空中间或空尾部段失败`() {
        val bodyKey = "weapon.$TEST_WEAPON_ID.tooltip.customPrimary"
        val hlKey = "weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL"
        val templates = listOf(
            "{%s}、{%s}、{%s}" to "第一项 | | 第三项",
            "{%s}、{%s}、{%s}" to "第一项 | 第二项 |",
            "{%s}、{%s}、{%s}" to "| 第二项 | 第三项",
        )

        for ((body, highlight) in templates) {
            val source = weaponStrings() + mapOf(bodyKey to body, hlKey to highlight)
            val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
            assertContains(error.message.orEmpty(), "field=customPrimaryHL")
            assertContains(error.message.orEmpty(), "empty parameter")
        }
    }

    @Test
    fun `参数默认值包含分隔符或换行失败`() {
        val key = "tooltip.weapon.$TEST_WEAPON_ID.default.redHullDamage"

        for (value in listOf("2%|3%", "2%\n3%", "2%\r3%")) {
            val source = weaponStrings() + (key to value)
            val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
            assertContains(error.message.orEmpty(), key)
        }
    }

    @Test
    fun `高亮模板禁止换行`() {
        val key = "weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL"
        val source = weaponStrings() + (key to "%redHullDamage%\n额外")

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "field=customPrimaryHL")
        assertContains(error.message.orEmpty(), "single line")
    }

    @Test
    fun `武器缺失必填模板键失败`() {
        val key = "weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL"
        val source = weaponStrings() - key

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "field=customPrimaryHL")
        assertContains(error.message.orEmpty(), "missing template key")
    }

    @Test
    fun `附加说明正文与高亮必须成对声明`() {
        val source = weaponStrings() + (
            "weapon.$TEST_WEAPON_ID.tooltip.customAncillary" to "附加 {%s} 效果。"
            )

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "field=customAncillary")
        assertContains(error.message.orEmpty(), "declared together")
    }

    @Test
    fun `系统简介为空失败`() {
        val source = systemStrings() + ("desc.$TEST_SYSTEM_ID.text1" to " ")

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "field=text1")
        assertContains(error.message.orEmpty(), "must not be blank")
    }

    @Test
    fun `系统缺失模板键失败`() {
        val source = systemStrings() - "desc.$TEST_SYSTEM_ID.text4"

        val error = assertFailsWith<TooltipTemplateException> { TooltipRegistry.parse(source) }
        assertContains(error.message.orEmpty(), "field=text4")
        assertContains(error.message.orEmpty(), "missing template key")
    }

    @Test
    fun `武器目标只要求武器条目`() {
        val registry = TooltipRegistry.parse(weaponStrings())

        registry.validateTargets(listOf(TestWeaponEntry))
    }

    @Test
    fun `武器目标缺失条目失败`() {
        val registry = TooltipRegistry.parse(weaponStrings())

        val error = assertFailsWith<TooltipTemplateException> {
            registry.validateTargets(emptyList())
        }
        assertContains(error.message.orEmpty(), "kind=weapon")
        assertContains(error.message.orEmpty(), "id=$TEST_WEAPON_ID")
        assertContains(error.message.orEmpty(), "no weapon_data entry")
    }

    @Test
    fun `系统目标要求系统条目与系统描述`() {
        val registry = TooltipRegistry.parse(systemStrings())

        registry.validateTargets(listOf(TestSystemEntry, TestSystemDescription))
    }

    @Test
    fun `系统目标缺失系统描述失败`() {
        val registry = TooltipRegistry.parse(systemStrings())

        val error = assertFailsWith<TooltipTemplateException> {
            registry.validateTargets(listOf(TestSystemEntry))
        }
        assertContains(error.message.orEmpty(), "kind=system")
        assertContains(error.message.orEmpty(), "type=SHIP_SYSTEM")
    }

    @Test
    fun `系统目标描述类型错误失败`() {
        val registry = TooltipRegistry.parse(systemStrings())
        val wrongType = object : DescriptionEntry() {
            override val id: String = TEST_SYSTEM_ID
            override val type: String = "SHIP"
        }

        val error = assertFailsWith<TooltipTemplateException> {
            registry.validateTargets(listOf(TestSystemEntry, wrongType))
        }
        assertContains(error.message.orEmpty(), "type=SHIP_SYSTEM")
    }

    @Test
    fun `系统目标缺失系统条目失败`() {
        val registry = TooltipRegistry.parse(systemStrings())

        val error = assertFailsWith<TooltipTemplateException> {
            registry.validateTargets(listOf(TestSystemDescription))
        }
        assertContains(error.message.orEmpty(), "no ship_systems entry")
    }
}
