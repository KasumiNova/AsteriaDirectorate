package cn.kasuminova.astd.copy

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
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
            "ui.hullmod.arc_advanced_fire_control.note",
            "ui.hullmod.arc_shared_tactical_network.summary",
            "ui.hullmod.arc_shared_tactical_network.attr.network",
            "ui.hullmod.arc_shared_tactical_network.value.network",
            "ui.hullmod.arc_shared_tactical_network.attr.command",
            "ui.hullmod.arc_shared_tactical_network.value.command",
            "ui.hullmod.arc_shared_tactical_network.note",
            "ui.hullmod.plasma_armor_shield.summary",
            "ui.hullmod.plasma_armor_shield.attr.armor_shield",
            "ui.hullmod.plasma_armor_shield.value.armor_shield",
            "ui.hullmod.plasma_armor_shield.attr.overload",
            "ui.hullmod.plasma_armor_shield.value.overload",
            "ui.hullmod.plasma_armor_shield.note",
            "ui.hullmod.ionized_recoil_accumulator.summary",
            "ui.hullmod.ionized_recoil_accumulator.attr.recoil",
            "ui.hullmod.ionized_recoil_accumulator.value.recoil",
            "ui.hullmod.ionized_recoil_accumulator.attr.volley",
            "ui.hullmod.ionized_recoil_accumulator.value.volley",
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
    }
}
