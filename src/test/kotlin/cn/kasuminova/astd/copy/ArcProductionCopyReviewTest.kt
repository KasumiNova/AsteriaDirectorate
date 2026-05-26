package cn.kasuminova.astd.copy

import org.json.JSONObject
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
            "正前方 **60°**",
            "左右侧前方 **60°~180°**",
            "后方 **180°~360°**",
            "当前 **2% 硬辐能**",
            "系统启动期间，辐能转换量与软辐能生成同步翻倍",
        ).forEach { text ->
            assertTrue(productionDesign.contains(text), "production design doc missing updated plasma arch design text: $text")
        }
        listOf(
            "12 个区域",
            "虚拟装甲值",
            "区域承压",
            "当前 **0.5% 硬辐能**",
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
            "system.plasma_armor_shield_boost.status.default.in",
            "system.plasma_armor_shield_boost.status.default.active",
            "system.plasma_armor_shield_boost.status.default.out",
            "system.limit_temporal_thruster.status.default.in",
            "system.limit_temporal_thruster.status.default.active",
            "system.limit_temporal_thruster.status.default.out",
        )

        val expectedRuntimeValues = linkedMapOf(
            "ui.hullmod.arc_advanced_fire_control.value.weapon_flux" to "常态+20%；满层后相对基础-40%",
            "ui.hullmod.arc_advanced_fire_control.value.weapon_rate" to "6秒满层：射速/射弹速度/伤害+20%",
            "ui.hullmod.arc_advanced_fire_control.value.ramp" to "大型非点防非导弹武器持续开火；停火后按同速率回落",
            "ui.hullmod.arc_advanced_fire_control.note" to "火控阵列只响应主炮级负载。",
            "ui.hullmod.arc_shared_tactical_network.value.network" to "自身非导弹武器射程-20%；电子战网络+4%",
            "ui.hullmod.arc_shared_tactical_network.value.command" to "1000/2000su 衰减；最远保留25%",
            "ui.hullmod.arc_shared_tactical_network.value.frigate" to "护卫舰：射程+40%，机动+20%，护盾承伤-20%",
            "ui.hullmod.arc_shared_tactical_network.value.destroyer" to "驱逐舰：射程+30%，机动+10%，护盾承伤-15%",
            "ui.hullmod.arc_shared_tactical_network.value.cruiser" to "巡洋舰：射程+20%，护盾承伤-10%",
            "ui.hullmod.arc_shared_tactical_network.note" to "阿斯忒里亚遗构局舰船效果+25%；弧光子型额外+25%。主力舰不获得加成。",
            "ui.hullmod.plasma_armor_shield.value.armor_shield" to "按受击方位取最终装甲5%~15%参与装甲减伤",
            "ui.hullmod.plasma_armor_shield.value.damage_type" to "护盾：能量-15%，动能-33%，高爆+33%，破片+20%；装甲：动能-33%",
            "ui.hullmod.plasma_armor_shield.value.grid" to "正前方60度=15%；侧前方线性10%~15%；后方线性5%~10%",
            "ui.hullmod.plasma_armor_shield.value.boost" to "护盾减伤+50%；装甲减伤+25%；方位装甲计算值翻倍",
            "ui.hullmod.plasma_armor_shield.value.recovery" to "护盾与装甲受击都会弹出被抵消伤害数字",
            "ui.hullmod.plasma_armor_shield.value.limits" to "护盾分流禁用；装甲提升船插仅享受66%",
            "ui.hullmod.plasma_armor_shield.note" to "微型护盾单元会把冲击摊入装甲骨架。",
            "ui.hullmod.ionized_recoil_accumulator.value.recoil" to "触发率25%~85%；冷却1秒；射程800su",
            "ui.hullmod.ionized_recoil_accumulator.value.volley" to "转换当前硬辐能2%为2倍软辐能；系统启动期间翻倍",
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
        )

        val expectedSsCsvLines = listOf(
            "hullmod.astd_arc_advanced_fire_control.name=弧光先进火控",
            "hullmod.astd_arc_advanced_targeting_system.name=弧光先进目标定位系统",
            "hullmod.astd_ionized_recoil_accumulator.name=离子化反冲蓄能器",
            "hullmod.astd_distributed_pursuit_network.name=分布式追猎网络",
            "system.astd_arc_shared_flux_network.name=弧光共享辐能网络",
            "system.astd_plasma_armor_shield_boost.name=等离子装甲护盾增压",
            "system.astd_limit_temporal_thruster.name=压限时流推进器",
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
        )
    }
}
