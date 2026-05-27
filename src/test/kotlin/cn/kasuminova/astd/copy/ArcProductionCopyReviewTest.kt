package cn.kasuminova.astd.copy

import org.json.JSONObject
import org.json.JSONArray
import java.nio.file.Files
import java.nio.file.Path
import cn.kasuminova.astd.testutil.CsvTestUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcProductionCopyReviewTest {

    @Test
    fun `production arc runtime copy keys exist without placeholder wording`() {
        val strings = readRuntimeStrings(
            Path.of("contents/data/strings/strings.json"),
            Path.of("contents/data/strings/systems_strings.json"),
        )

        expectedRuntimeKeys.forEach { key ->
            val value = strings[key]
            assertTrue(value != null, "missing runtime copy key: $key")
            assertTrue(value.isNotBlank(), "runtime copy value must not be blank: $key")
            assertFalse(value.contains("占位"), "runtime copy value must not contain placeholder wording: $key")
            assertFalse(value.contains("placeholder", ignoreCase = true), "runtime copy value must not contain placeholder wording: $key")
            assertFalse(value.contains("调试"), "runtime copy value must not contain debug wording: $key")
            assertFalse(value.contains("后续"), "runtime copy value must not contain implementation scheduling wording: $key")
            assertFalse(value.contains("脚本"), "runtime copy value must not contain implementation wording: $key")
            assertFalse(value.contains("TODO", ignoreCase = true), "runtime copy value must not contain TODO wording: $key")
        }
    }

    @Test
    fun `production arc tooltip copy exposes exact design values`() {
        val strings = readRuntimeStrings(Path.of("contents/data/strings/strings.json"))

        expectedRuntimeValues.forEach { (key, expected) ->
            assertTrue(strings[key] == expected, "runtime copy value mismatch for $key: expected <$expected>, got <${strings[key]}>")
        }
    }

    @Test
    fun `plasma arch unique hullmod tooltips mirror exported tooltip text exactly`() {
        val strings = readRuntimeStrings(Path.of("contents/data/strings/strings.json"))
        val contracts = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionTooltipContracts.kt"))
        val renderer = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDHullModTooltipRenderer.kt"))
        val hullmods = CsvTestUtil.readRowsById(Path.of("contents/data/hullmods/hull_mods.csv"))

        exportedPlasmaArmorShieldValues().forEach { (key, expected) ->
            assertEquals(expected, strings[key], "plasma armor shield tooltip key must mirror tools export exactly: $key")
            assertTrue(contracts.contains("\"$key\""), "plasma armor shield export key must be referenced by tooltip contract: $key")
        }
        stalePlasmaArmorShieldExportKeys.forEach { key ->
            assertFalse(strings.containsKey(key), "removed plasma armor shield export key must not remain in runtime strings: $key")
            assertFalse(contracts.contains("\"$key\""), "removed plasma armor shield export key must not remain referenced by tooltip contract: $key")
        }
        exportedIonizedRecoilValues().forEach { (key, expected) ->
            assertEquals(expected, strings[key], "ionized recoil tooltip key must mirror tools export exactly: $key")
            assertTrue(contracts.contains("\"$key\""), "ionized recoil export key must be referenced by tooltip contract: $key")
        }
        listOf(
            "astd_plasma_armor_shield",
            "astd_ionized_recoil_accumulator",
        ).forEach { hullmodId ->
            val desc = hullmods.getValue(hullmodId).getValue("desc")
            assertTrue(desc.isBlank(), "custom rendered production tooltip must not keep a native hull_mods.csv desc: $hullmodId")
        }
        exportedTooltipHighlightMarkers().forEach { marker ->
            assertTrue(contracts.contains(marker), "tooltip contract must preserve exported highlight/color metadata: $marker")
        }
        listOf(
            "highlights: List<Highlight>",
            "labelRole",
            "valueRole",
            "colorForRole",
            "I18n.Rendered(",
            "text = I18n[I18n.Categories.MOD, block.key]",
            "I18n.Highlight(it.value, it.color)",
            "I18nUi.addParaRendered",
        ).forEach { fragment ->
            assertTrue(renderer.contains(fragment), "tooltip renderer must consume exported highlight/color metadata: $fragment")
        }
    }

    @Test
    fun `production arc ss csv names match design names`() {
        val i18n = Files.readString(Path.of("ss-csv/src/main/resources/i18n/zh-cn.properties"))

        expectedSsCsvLines.forEach { expected ->
            assertTrue(i18n.contains(expected), "missing exact ss-csv i18n line: $expected")
        }
    }

    @Test
    fun `production arc system descriptions are exported from ss csv source`() {
        val descriptions = Files.readString(Path.of("ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/strings/Catalog_Descriptions.kt"))
        val i18n = Files.readString(Path.of("ss-csv/src/main/resources/i18n/zh-cn.properties"))

        expectedSystemDescriptionIds.forEach { id ->
            assertTrue(descriptions.contains("LocalizedDescription(\"$id\", \"SHIP_SYSTEM\")"), "missing ss-csv description entry: $id")
            assertTrue(i18n.contains("desc.$id.text1="), "missing ss-csv description text1: $id")
            assertTrue(i18n.contains("desc.$id.text4="), "missing ss-csv description text4: $id")
        }
        assertFalse(i18n.contains("高能装填系统"), "radiation belt ship description must not mention the old system name")
    }

    @Test
    fun `plasma armor shield boost system copy matches required player facing text`() {
        val runtimeStrings = readRuntimeStrings(Path.of("contents/data/strings/strings.json"))
        val i18n = Files.readString(Path.of("ss-csv/src/main/resources/i18n/zh-cn.properties"))
        val descriptions = CsvTestUtil.readRowsById(Path.of("contents/data/strings/descriptions.csv"))
        val systemSource = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDPlasmaArmorShieldBoostSystemStats.kt"),
        )

        assertTrue(
            i18n.contains("system.astd_plasma_armor_shield_boost.name=装甲护盾增压"),
            "plasma armor shield boost system name must use the shortened design name",
        )

        val descriptionRow = descriptions.getValue("astd_plasma_armor_shield_boost")
        expectedPlasmaShieldBoostDescriptionValues.forEach { (column, expected) ->
            assertEquals(expected, descriptionRow.getValue(column), "plasma shield boost description mismatch in $column")
        }

        expectedPlasmaShieldBoostDescriptionI18nLines.forEach { expected ->
            assertTrue(i18n.contains(expected), "missing exact plasma shield boost ss-csv i18n line: $expected")
        }

        expectedPlasmaShieldBoostStatusValues.forEach { (key, expected) ->
            assertEquals(expected, runtimeStrings[key], "plasma shield boost status text mismatch for $key")
        }

        assertFalse(
            systemSource.contains("if (index != 0) return null"),
            "plasma shield boost status should expose multiple status rows",
        )
        listOf(
            "0 -> \"line1\"",
            "1 -> \"line2\"",
            "\"shield\" to formatPercent(SHIELD_DR_MAX * level)",
            "\"armor\" to formatPercent(ARMOR_DR_MAX * level)",
            "\"weaponRate\" to formatPercent((1f - WEAPON_ROF_MULT) * level)",
            "I18n.t(",
            "\"system.plasma_armor_shield_boost.status.default.\$suffix.\$line\"",
        ).forEach { requiredSource ->
            assertTrue(
                systemSource.contains(requiredSource),
                "plasma shield boost status script must reference required source fragment: $requiredSource",
            )
        }
        assertFalse(
            systemSource.contains("2 -> \"weapon_rate\""),
            "plasma shield boost status should compress three data lines into two HUD entries",
        )
    }

    @Test
    fun `ship system description skill documents Starsector description fields`() {
        val skill = Files.readString(Path.of(".agents/skills/ship-system-description-guidelines/SKILL.md"))
        val index = Files.readString(Path.of(".agents/skills/00-skill-index/SKILL.md"))

        listOf(
            "text1 为图鉴界面的首行描述文本",
            "text2 为类型短词",
            "text3 为装配界面的舰船信息文本",
            "text4 暂时作用不明",
            "text5 为效果文本",
            "text1 / text5 支持原生换行",
        ).forEach { fragment ->
            assertTrue(skill.contains(fragment), "ship system description skill must document field rule: $fragment")
        }
        assertTrue(
            index.contains(".agents/skills/ship-system-description-guidelines/SKILL.md"),
            "skill index must include ship-system-description-guidelines",
        )
    }

    @Test
    fun `production arc ship system type labels are short category words`() {
        val descriptions = CsvTestUtil.readRowsById(Path.of("contents/data/strings/descriptions.csv"))

        descriptions.values
            .filter { it.getValue("type") == "SHIP_SYSTEM" }
            .forEach { row ->
                val id = row.getValue("id")
                val label = row.getValue("text2")
                assertTrue(label in allowedSystemTypeLabels, "ship system type label must be a known short category word: $id")
                assertTrue(label.length <= 2, "ship system type label must be short: $id")
                assertFalse(label.contains("，"), "system type label must not be a sentence: $id")
                assertFalse(label.contains("。"), "system type label must not be a sentence: $id")
            }

        expectedSystemTypeLabels.forEach { (id, expectedType) ->
            val row = descriptions.getValue(id)
            assertEquals("SHIP_SYSTEM", row.getValue("type"), "description type mismatch for $id")
            assertEquals(expectedType, row.getValue("text2"), "system type label mismatch for $id")
            assertTrue(row.getValue("text3").isNotBlank(), "system short description must be present in text3: $id")
        }
    }

    @Test
    fun `production arc runtime copy does not keep stale tooltip aliases`() {
        val strings = readRuntimeStrings(Path.of("contents/data/strings/strings.json"))

        staleRuntimeKeys.forEach { key ->
            assertFalse(strings.containsKey(key), "stale runtime copy key should be removed: $key")
        }
    }

    @Test
    fun `production arc design document is synchronized with current plasma arch mechanics`() {
        val productionDesign = Files.readString(Path.of("docs/design/ships/blue/20-production.md"))
        listOf(
            "方位装甲计算",
            "装配点 | 185",
            "部署点 | 32",
            "正前方 **60°**",
            "左右侧前方 **60°~180°**",
            "后方 **180°~360°**",
            "当前 **2% 硬辐能**",
            "系统启动期间，辐能转换量与软辐能生成同步翻倍",
            "激活期间护盾受到的单次伤害大于舰船最大辐能的 **5%** 时，超出的伤害部分降低 **50%**",
            "软辐能生成 | 与被转换硬辐能等额",
        ).forEach { text ->
            assertTrue(productionDesign.contains(text), "production design doc missing updated plasma arch design text: $text")
        }
        listOf(
            "12 个区域",
            "虚拟装甲值",
            "区域承压",
            "当前 **0.5% 硬辐能**",
            "被转换硬辐能的 1.5 倍",
            "护盾会根据受损比例受到额外伤害",
        ).forEach { stale ->
            assertFalse(productionDesign.contains(stale), "production design doc still contains stale plasma arch design text: $stale")
        }
    }

    private fun readRuntimeStrings(vararg paths: Path): Map<String, String> {
        val result = linkedMapOf<String, String>()
        paths.forEach { path ->
            val root = JSONObject(Files.readString(path))
            val category = root.getJSONObject("asteria_directorate")
            val keys = category.keys()
            while (keys.hasNext()) {
                val key = keys.next() as String
                result[key] = category.getString(key)
            }
        }
        return result
    }

    private fun exportedPlasmaArmorShieldValues(): Map<String, String> {
        val blocks = exportBlocks(Path.of("tools/exports/hullmod_tooltip_等离子装甲护盾.json"))
        return linkedMapOf(
            "ui.hullmod.plasma_armor_shield.summary" to blocks.paragraph(0),
            "ui.hullmod.plasma_armor_shield.section.directional_armor" to blocks.heading(0),
            "ui.hullmod.plasma_armor_shield.line.directional_armor" to blocks.paragraph(1),
            "ui.hullmod.plasma_armor_shield.table.direction.header_a" to blocks.tableHeader(0, 0),
            "ui.hullmod.plasma_armor_shield.table.direction.header_b" to blocks.tableHeader(0, 1),
            "ui.hullmod.plasma_armor_shield.table.direction.row_0.label" to blocks.tableCell(0, 0, 0),
            "ui.hullmod.plasma_armor_shield.table.direction.row_0.value" to blocks.tableCell(0, 0, 1),
            "ui.hullmod.plasma_armor_shield.table.direction.row_1.label" to blocks.tableCell(0, 1, 0),
            "ui.hullmod.plasma_armor_shield.table.direction.row_1.value" to blocks.tableCell(0, 1, 1),
            "ui.hullmod.plasma_armor_shield.table.direction.row_2.label" to blocks.tableCell(0, 2, 0),
            "ui.hullmod.plasma_armor_shield.table.direction.row_2.value" to blocks.tableCell(0, 2, 1),
            "ui.hullmod.plasma_armor_shield.section.effect" to blocks.heading(1),
            "ui.hullmod.plasma_armor_shield.line.shield_damage_type" to blocks.paragraph(2),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.header_a" to blocks.tableHeader(1, 0),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.header_b" to blocks.tableHeader(1, 1),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.row_0.label" to blocks.tableCell(1, 0, 0),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.row_0.value" to blocks.tableCell(1, 0, 1),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.row_1.label" to blocks.tableCell(1, 1, 0),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.row_1.value" to blocks.tableCell(1, 1, 1),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.row_2.label" to blocks.tableCell(1, 2, 0),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.row_2.value" to blocks.tableCell(1, 2, 1),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.row_3.label" to blocks.tableCell(1, 3, 0),
            "ui.hullmod.plasma_armor_shield.table.shield_damage.row_3.value" to blocks.tableCell(1, 3, 1),
            "ui.hullmod.plasma_armor_shield.line.armor_damage_type" to blocks.paragraph(3),
            "ui.hullmod.plasma_armor_shield.table.armor_damage.header_a" to blocks.tableHeader(2, 0),
            "ui.hullmod.plasma_armor_shield.table.armor_damage.header_b" to blocks.tableHeader(2, 1),
            "ui.hullmod.plasma_armor_shield.table.armor_damage.row_0.label" to blocks.tableCell(2, 0, 0),
            "ui.hullmod.plasma_armor_shield.table.armor_damage.row_0.value" to blocks.tableCell(2, 0, 1),
            "ui.hullmod.plasma_armor_shield.table.armor_damage.row_1.label" to blocks.tableCell(2, 1, 0),
            "ui.hullmod.plasma_armor_shield.table.armor_damage.row_1.value" to blocks.tableCell(2, 1, 1),
            "ui.hullmod.plasma_armor_shield.section.limits" to blocks.heading(2),
            "ui.hullmod.plasma_armor_shield.line.limits" to blocks.paragraph(4),
            "ui.hullmod.plasma_armor_shield.line.limit_hardened_shields" to blocks.paragraph(5),
            "ui.hullmod.plasma_armor_shield.line.limit_shield_shunt" to blocks.paragraph(6),
            "ui.hullmod.plasma_armor_shield.line.max_armor_penalty" to blocks.paragraph(7),
        )
    }

    private fun exportedIonizedRecoilValues(): Map<String, String> {
        val blocks = exportBlocks(Path.of("tools/exports/hullmod_tooltip_离子化反冲蓄能器.json"))
        return linkedMapOf(
            "ui.hullmod.ionized_recoil_accumulator.summary" to blocks.paragraph(0),
            "ui.hullmod.ionized_recoil_accumulator.section.effect" to blocks.heading(0),
            "ui.hullmod.ionized_recoil_accumulator.line.proc_intro" to blocks.paragraph(1),
            "ui.hullmod.ionized_recoil_accumulator.table.flux.header_a" to blocks.tableHeader(0, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.flux.header_b" to blocks.tableHeader(0, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.flux.row_0.label" to blocks.tableCell(0, 0, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.flux.row_0.value" to blocks.tableCell(0, 0, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.flux.row_1.label" to blocks.tableCell(0, 1, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.flux.row_1.value" to blocks.tableCell(0, 1, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.flux.row_2.label" to blocks.tableCell(0, 2, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.flux.row_2.value" to blocks.tableCell(0, 2, 1),
            "ui.hullmod.ionized_recoil_accumulator.line.proc_type" to blocks.paragraph(2),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.header_a" to blocks.tableHeader(1, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.header_b" to blocks.tableHeader(1, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_0.label" to blocks.tableCell(1, 0, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_0.value" to blocks.tableCell(1, 0, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_1.label" to blocks.tableCell(1, 1, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_1.value" to blocks.tableCell(1, 1, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_2.label" to blocks.tableCell(1, 2, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_2.value" to blocks.tableCell(1, 2, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_3.label" to blocks.tableCell(1, 3, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_3.value" to blocks.tableCell(1, 3, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_4.label" to blocks.tableCell(1, 4, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_4.value" to blocks.tableCell(1, 4, 1),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_5.label" to blocks.tableCell(1, 5, 0),
            "ui.hullmod.ionized_recoil_accumulator.table.proc_type.row_5.value" to blocks.tableCell(1, 5, 1),
            "ui.hullmod.ionized_recoil_accumulator.line.beam_proc" to blocks.paragraph(3),
            "ui.hullmod.ionized_recoil_accumulator.line.damage_proc" to blocks.paragraph(4),
            "ui.hullmod.ionized_recoil_accumulator.section.flux_damage" to blocks.heading(1),
            "ui.hullmod.ionized_recoil_accumulator.line.flux_conversion" to blocks.paragraph(5),
            "ui.hullmod.ionized_recoil_accumulator.line.damage" to blocks.paragraph(6),
            "ui.hullmod.ionized_recoil_accumulator.line.targeting" to blocks.paragraph(7),
            "ui.hullmod.ionized_recoil_accumulator.line.cooldown" to blocks.paragraph(8),
        )
    }

    private fun exportedTooltipHighlightMarkers(): List<String> = listOf(
        "highlight(\"阿斯忒里亚遗构局\", \"#FF9600\")",
        "highlight(\"等离子装甲护盾\", \"#FFE024\")",
        "highlight(\"90%\", \"#FFE024\")",
        "highlight(\"800su\", \"#FFE024\")",
        "highlight(\"等额\", \"#FFE024\")",
        "highlight(\"100%\", \"#FFE024\")",
        "role = \"warning\"",
    )

    private fun exportBlocks(path: Path): ExportBlocks {
        val root = JSONObject(Files.readString(path))
        val blocks = root.getJSONArray("blocks")
        return ExportBlocks(
            paragraphs = blocks.textsByKind("paragraph"),
            headings = blocks.textsByKind("section-heading"),
            tables = buildList {
                for (idx in 0 until blocks.length()) {
                    val block = blocks.getJSONObject(idx)
                    if (block.getString("kind") != "table") continue
                    add(block)
                }
            },
        )
    }

    private fun JSONArray.textsByKind(kind: String): List<String> = buildList {
        for (idx in 0 until length()) {
            val block = getJSONObject(idx)
            if (block.getString("kind") == kind) add(block.getString("text"))
        }
    }

    private data class ExportBlocks(
        val paragraphs: List<String>,
        val headings: List<String>,
        val tables: List<JSONObject>,
    ) {
        fun paragraph(index: Int): String = paragraphs[index]
        fun heading(index: Int): String = headings[index]
        fun tableHeader(table: Int, column: Int): String =
            tables[table].getJSONArray("columns").getJSONObject(column).getString("label")

        fun tableCell(table: Int, row: Int, column: Int): String =
            tables[table].getJSONArray("rows")
                .getJSONObject(row)
                .getJSONArray("cells")
                .getJSONObject(column)
                .getString("text")
    }

    private companion object {
        val expectedRuntimeKeys = listOf(
            "ui.hullmod.arc_advanced_fire_control.summary",
            "ui.hullmod.arc_advanced_fire_control.attr.weapon_flux",
            "ui.hullmod.arc_advanced_fire_control.value.weapon_flux",
            "ui.hullmod.arc_advanced_fire_control.attr.weapon_rate",
            "ui.hullmod.arc_advanced_fire_control.value.weapon_rate",
            "ui.hullmod.arc_advanced_fire_control.attr.ramp",
            "ui.hullmod.arc_advanced_fire_control.value.ramp",
            "ui.hullmod.arc_advanced_fire_control.note",
            "ui.hullmod.arc_shared_tactical_network.summary",
            "ui.hullmod.arc_shared_tactical_network.attr.network",
            "ui.hullmod.arc_shared_tactical_network.value.network",
            "ui.hullmod.arc_shared_tactical_network.attr.command",
            "ui.hullmod.arc_shared_tactical_network.value.command",
            "ui.hullmod.arc_shared_tactical_network.attr.frigate",
            "ui.hullmod.arc_shared_tactical_network.value.frigate",
            "ui.hullmod.arc_shared_tactical_network.attr.destroyer",
            "ui.hullmod.arc_shared_tactical_network.value.destroyer",
            "ui.hullmod.arc_shared_tactical_network.attr.cruiser",
            "ui.hullmod.arc_shared_tactical_network.value.cruiser",
            "ui.hullmod.arc_shared_tactical_network.note",
            "ui.hullmod.plasma_armor_shield.summary",
            "ui.hullmod.plasma_armor_shield.attr.armor_shield",
            "ui.hullmod.plasma_armor_shield.value.armor_shield",
            "ui.hullmod.plasma_armor_shield.attr.damage_type",
            "ui.hullmod.plasma_armor_shield.value.damage_type",
            "ui.hullmod.plasma_armor_shield.attr.grid",
            "ui.hullmod.plasma_armor_shield.value.grid",
            "ui.hullmod.plasma_armor_shield.attr.boost",
            "ui.hullmod.plasma_armor_shield.value.boost",
            "ui.hullmod.plasma_armor_shield.attr.recovery",
            "ui.hullmod.plasma_armor_shield.value.recovery",
            "ui.hullmod.plasma_armor_shield.attr.limits",
            "ui.hullmod.plasma_armor_shield.value.limits",
            "ui.hullmod.plasma_armor_shield.note",
            "ui.hullmod.ionized_recoil_accumulator.summary",
            "ui.hullmod.ionized_recoil_accumulator.attr.recoil",
            "ui.hullmod.ionized_recoil_accumulator.value.recoil",
            "ui.hullmod.ionized_recoil_accumulator.attr.proc_type",
            "ui.hullmod.ionized_recoil_accumulator.value.proc_type",
            "ui.hullmod.ionized_recoil_accumulator.attr.proc_strength",
            "ui.hullmod.ionized_recoil_accumulator.value.proc_strength",
            "ui.hullmod.ionized_recoil_accumulator.attr.volley",
            "ui.hullmod.ionized_recoil_accumulator.value.volley",
            "ui.hullmod.ionized_recoil_accumulator.attr.damage",
            "ui.hullmod.ionized_recoil_accumulator.value.damage",
            "ui.hullmod.ionized_recoil_accumulator.attr.pierce",
            "ui.hullmod.ionized_recoil_accumulator.value.pierce",
            "ui.hullmod.ionized_recoil_accumulator.note",
            "ui.hullmod.arc_advanced_targeting_system.summary",
            "ui.hullmod.arc_advanced_targeting_system.attr.range",
            "ui.hullmod.arc_advanced_targeting_system.value.range",
            "ui.hullmod.arc_advanced_targeting_system.attr.short_range",
            "ui.hullmod.arc_advanced_targeting_system.value.short_range",
            "ui.hullmod.arc_advanced_targeting_system.note",
            "ui.hullmod.distributed_pursuit_network.summary",
            "ui.hullmod.distributed_pursuit_network.section.members",
            "ui.hullmod.distributed_pursuit_network.section.bonus",
            "ui.hullmod.distributed_pursuit_network.attr.members",
            "ui.hullmod.distributed_pursuit_network.value.members",
            "ui.hullmod.distributed_pursuit_network.attr.same_network",
            "ui.hullmod.distributed_pursuit_network.value.same_network",
            "ui.hullmod.distributed_pursuit_network.attr.speed",
            "ui.hullmod.distributed_pursuit_network.value.speed",
            "ui.hullmod.distributed_pursuit_network.attr.range",
            "ui.hullmod.distributed_pursuit_network.value.range",
            "ui.hullmod.distributed_pursuit_network.attr.peak",
            "ui.hullmod.distributed_pursuit_network.value.peak",
            "ui.hullmod.distributed_pursuit_network.note",
            "system.arc_shared_flux_network.status.default.in",
            "system.arc_shared_flux_network.status.default.active",
            "system.arc_shared_flux_network.status.default.out",
            "system.plasma_armor_shield_boost.status.default.in.line1",
            "system.plasma_armor_shield_boost.status.default.in.line2",
            "system.plasma_armor_shield_boost.status.default.active.line1",
            "system.plasma_armor_shield_boost.status.default.active.line2",
            "system.plasma_armor_shield_boost.status.default.out.line1",
            "system.plasma_armor_shield_boost.status.default.out.line2",
            "system.limit_temporal_thruster.status.default.in",
            "system.limit_temporal_thruster.status.default.active",
            "system.limit_temporal_thruster.status.default.out",
        )

        val expectedPlasmaShieldBoostStatusValues = linkedMapOf(
            "system.plasma_armor_shield_boost.status.default.in.line1" to "减少 %shield% 护盾受到的伤害",
            "system.plasma_armor_shield_boost.status.default.in.line2" to "减少 %armor% 装甲受到的伤害\n减少 %weaponRate% 非导弹非点防武器射速",
            "system.plasma_armor_shield_boost.status.default.active.line1" to "减少 %shield% 护盾受到的伤害",
            "system.plasma_armor_shield_boost.status.default.active.line2" to "减少 %armor% 装甲受到的伤害\n减少 %weaponRate% 非导弹非点防武器射速",
            "system.plasma_armor_shield_boost.status.default.out.line1" to "减少 %shield% 护盾受到的伤害",
            "system.plasma_armor_shield_boost.status.default.out.line2" to "减少 %armor% 装甲受到的伤害\n减少 %weaponRate% 非导弹非点防武器射速",
        )

        val expectedRuntimeValues = linkedMapOf(
            "ui.hullmod.arc_advanced_fire_control.value.weapon_flux" to "常态+20%；满层后相对基础-40%",
            "ui.hullmod.arc_advanced_fire_control.value.weapon_rate" to "6秒满层：射速/射弹速度/伤害+20%",
            "ui.hullmod.arc_advanced_fire_control.value.ramp" to "大型非点防非导弹武器持续开火；停火后按同速率回落",
            "ui.hullmod.arc_advanced_fire_control.note" to "火控阵列只响应主炮级负载。",
            "ui.hullmod.arc_shared_tactical_network.value.network" to "自身非导弹非点防武器射程-20%；电子战网络+4%",
            "ui.hullmod.arc_shared_tactical_network.value.command" to "1000/2000su 衰减；最远保留25%",
            "ui.hullmod.arc_shared_tactical_network.value.frigate" to "护卫舰：射程+40%，机动+20%，护盾承伤-20%",
            "ui.hullmod.arc_shared_tactical_network.value.destroyer" to "驱逐舰：射程+30%，机动+10%，护盾承伤-15%",
            "ui.hullmod.arc_shared_tactical_network.value.cruiser" to "巡洋舰：射程+20%，护盾承伤-10%",
            "ui.hullmod.arc_shared_tactical_network.note" to "阿斯忒里亚遗构局舰船效果+25%；弧光子型额外+25%。主力舰不获得加成。",
            "ui.hullmod.plasma_armor_shield.value.armor_shield" to "按受击方位取最终装甲10%~20%参与装甲减伤",
            "ui.hullmod.plasma_armor_shield.value.damage_type" to "护盾：能量-15%，动能-33%，高爆+33%，破片+20%；装甲：动能-33%",
            "ui.hullmod.plasma_armor_shield.value.grid" to "正前方60度=20%；侧前方线性15%~20%；后方线性10%~15%",
            "ui.hullmod.plasma_armor_shield.value.boost" to "护盾减伤+50%；装甲减伤+25%；方位装甲计算值翻倍",
            "ui.hullmod.plasma_armor_shield.value.recovery" to "护盾与装甲受击都会弹出被抵消伤害数字",
            "ui.hullmod.plasma_armor_shield.value.limits" to "护盾分流与强化护盾禁用；最大装甲值固定-50%",
            "ui.hullmod.plasma_armor_shield.note" to "微型护盾单元会把冲击摊入装甲骨架。",
            "ui.hullmod.ionized_recoil_accumulator.value.recoil" to "触发率25%~85%；冷却1秒；射程800su",
            "ui.hullmod.ionized_recoil_accumulator.value.proc_type" to "护盾：动能+75%，高爆-50%，破片-75%；装甲：动能-50%，高爆+150%，破片-75%；光束额外-75%",
            "ui.hullmod.ionized_recoil_accumulator.value.proc_strength" to "原始伤害以最大辐能2%为基准；触发率倍率0.1x~3x",
            "ui.hullmod.ionized_recoil_accumulator.value.volley" to "转换当前硬辐能2%为等额软辐能；系统启动期间翻倍",
            "ui.hullmod.ionized_recoil_accumulator.value.damage" to "电弧伤害=转换量100%；EMP=转换量200%；射程受能量射弹射程影响",
            "ui.hullmod.ionized_recoil_accumulator.value.pierce" to "按硬辐能水平获得15%~85%概率",
            "ui.hullmod.ionized_recoil_accumulator.note" to "电弧优先打击武器或引擎，不攻击友军。",
            "ui.hullmod.arc_advanced_targeting_system.value.range" to "非导弹武器射程+20%",
            "ui.hullmod.arc_advanced_targeting_system.value.short_range" to "低基础射程补偿：<600，最高+150",
            "ui.hullmod.distributed_pursuit_network.value.members" to "1200su内至多5艘友军护卫舰/驱逐舰",
            "ui.hullmod.distributed_pursuit_network.value.same_network" to "同网络连接：效果+50%",
            "ui.hullmod.distributed_pursuit_network.value.speed" to "连接目标航速/机动+4%；同网络目标+6%",
            "ui.hullmod.distributed_pursuit_network.value.range" to "连接目标武器射程+4%；同网络目标+6%",
            "ui.hullmod.distributed_pursuit_network.value.peak" to "连接目标峰值时间消耗率-4%；同网络目标-6%",
            "ui.hullmod.distributed_pursuit_network.note" to "连接目标无需同型；距离不影响强度。",
        ).apply {
            putAll(expectedPlasmaShieldBoostStatusValues)
        }

        val expectedSsCsvLines = listOf(
            "hullmod.astd_arc_advanced_fire_control.name=弧光先进火控",
            "hullmod.astd_arc_advanced_targeting_system.name=弧光先进目标定位系统",
            "hullmod.astd_ionized_recoil_accumulator.name=离子化反冲蓄能器",
            "hullmod.astd_distributed_pursuit_network.name=分布式追猎网络",
            "system.astd_arc_shared_flux_network.name=弧光共享辐能网络",
            "system.astd_plasma_armor_shield_boost.name=装甲护盾增压",
            "system.astd_limit_temporal_thruster.name=压限时流推进器",
        )

        val expectedPlasmaShieldBoostDescriptionValues = linkedMapOf(
            "type" to "SHIP_SYSTEM",
            "text1" to "舰船将大部分能量导向装甲护盾，显著增加护盾和装甲的承伤能力。",
            "text2" to "防御",
            "text3" to "舰船将大部分能量导向装甲护盾，显著增加护盾和装甲的承伤能力。",
            "text4" to "",
            "text5" to "护盾减少 {{50％}}} 受到的伤害，装甲减少 {{25％}}} 受到的伤害。\n并降低 {{50％}}} 非导弹（不包括点防御）武器射速。\n激活期间护盾受到的单次伤害大于舰船最大辐能的 {{5％}}} 时，超出的伤害部分降低 {{50％}}}。\n等离子装甲护盾的装甲计算值翻倍。\n并使离子化反冲蓄能器效果翻倍。",
        )

        val expectedPlasmaShieldBoostDescriptionI18nLines = listOf(
            "desc.astd_plasma_armor_shield_boost.text1=舰船将大部分能量导向装甲护盾，显著增加护盾和装甲的承伤能力。",
            "desc.astd_plasma_armor_shield_boost.text3=舰船将大部分能量导向装甲护盾，显著增加护盾和装甲的承伤能力。",
            "desc.astd_plasma_armor_shield_boost.text4=",
            "desc.astd_plasma_armor_shield_boost.text5=护盾减少 {{50％}}} 受到的伤害，装甲减少 {{25％}}} 受到的伤害。\\n并降低 {{50％}}} 非导弹（不包括点防御）武器射速。\\n激活期间护盾受到的单次伤害大于舰船最大辐能的 {{5％}}} 时，超出的伤害部分降低 {{50％}}}。\\n等离子装甲护盾的装甲计算值翻倍。\\n并使离子化反冲蓄能器效果翻倍。",
        )

        val expectedSystemDescriptionIds = listOf(
            "astd_arc_flare_overdrive_crewed",
            "astd_arc_flare_overdrive_automated",
            "astd_arc_shared_flux_network",
            "astd_plasma_armor_shield_boost",
            "astd_limit_temporal_thruster",
            "astd_stellar_jet",
        )

        val expectedSystemTypeLabels = linkedMapOf(
            "astd_arc_flare_overdrive_crewed" to "进攻",
            "astd_arc_flare_overdrive_automated" to "进攻",
            "astd_arc_shared_flux_network" to "支援",
            "astd_plasma_armor_shield_boost" to "防御",
            "astd_limit_temporal_thruster" to "机动",
            "astd_stellar_jet" to "机动",
        )

        val allowedSystemTypeLabels = setOf("进攻", "防御", "机动", "支援", "特殊")

        val staleRuntimeKeys = listOf(
            "ui.hullmod.arc_advanced_targeting_system.attr.targeting",
            "ui.hullmod.arc_advanced_targeting_system.value.targeting",
            "ui.hullmod.arc_advanced_targeting_system.attr.beam",
            "ui.hullmod.arc_advanced_targeting_system.value.beam",
            "ui.hullmod.distributed_pursuit_network.attr.pursuit",
            "ui.hullmod.distributed_pursuit_network.value.pursuit",
            "ui.hullmod.distributed_pursuit_network.attr.missile",
            "ui.hullmod.distributed_pursuit_network.value.missile",
            "ui.hullmod.plasma_armor_shield.line.armor_damage_ratio",
            "ui.hullmod.plasma_armor_shield.table.armor_ratio.header_a",
            "ui.hullmod.plasma_armor_shield.table.armor_ratio.header_b",
            "ui.hullmod.plasma_armor_shield.table.armor_ratio.row_0.label",
            "ui.hullmod.plasma_armor_shield.table.armor_ratio.row_0.value",
            "ui.hullmod.plasma_armor_shield.table.armor_ratio.row_1.label",
            "ui.hullmod.plasma_armor_shield.table.armor_ratio.row_1.value",
            "ui.hullmod.plasma_armor_shield.table.armor_ratio.row_2.label",
            "ui.hullmod.plasma_armor_shield.table.armor_ratio.row_2.value",
        )

        val stalePlasmaArmorShieldExportKeys = staleRuntimeKeys
            .filter { it.startsWith("ui.hullmod.plasma_armor_shield.") }
    }
}
