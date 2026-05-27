package cn.kasuminova.astd.data

import cn.kasuminova.astd.testutil.CsvTestUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcProductionHullmodRegistrationTest {

    @Test
    fun `production arc hullmods are registered with real metadata`() {
        val rows = readCsvRows(Path.of("contents/data/hullmods/hull_mods.csv"))

        expectedHullmodIds.forEach { id ->
            val row = rows[id]
            assertTrue(row != null, "missing hullmod registration: $id")
            assertEquals("ARC", row.getValue("tech/manufacturer"), "hullmod must use ARC tech: $id")
            assertTrue(row.getValue("tags").split("|").contains("astd_builtin"), "hullmod must be a ship-specific built-in: $id")
            assertIsRealText(row.getValue("short"), "short", id)
            if (id in customRenderedTooltipHullmodIds) {
                assertTrue(row.getValue("desc").isBlank(), "custom rendered tooltip must leave native desc blank: $id")
            } else {
                assertIsRealText(row.getValue("desc"), "desc", id)
            }

            val script = row.getValue("script")
            assertTrue(script.isNotBlank(), "script must not be blank: $id")
            assertFalse(script.contains("PlaceholderHullMod"), "script must not use PlaceholderHullMod: $id")
            assertFalse(script.contains("Placeholder", ignoreCase = true), "script must not be placeholder-named: $id")
            assertRuntimeClassExists(script, "hullmod script", id)
        }
    }

    @Test
    fun `production arc ship system runtime classes exist`() {
        val systems = mapOf(
            "astd_arc_shared_flux_network" to listOf(
                "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemStats",
                "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemAI",
            ),
            "astd_plasma_armor_shield_boost" to listOf(
                "cn.kasuminova.astd.combat.shipsystems.ASTDPlasmaArmorShieldBoostSystemStats",
                "cn.kasuminova.astd.combat.shipsystems.ASTDPlasmaArmorShieldBoostSystemAI",
            ),
            "astd_limit_temporal_thruster" to listOf(
                "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemStats",
                "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemAI",
            ),
        )
        systems.forEach { (systemId, classNames) ->
            classNames.forEach { className ->
                assertRuntimeClassExists(className, "ship system class", systemId)
            }
        }
    }

    private fun assertIsRealText(value: String, field: String, id: String) {
        assertTrue(value.isNotBlank(), "$field must not be blank: $id")
        assertFalse(value.contains("占位"), "$field must not contain placeholder wording: $id")
        assertFalse(value.contains("placeholder", ignoreCase = true), "$field must not contain placeholder wording: $id")
        assertFalse(value.contains("调试"), "$field must not contain debug wording: $id")
        assertFalse(value.contains("TODO", ignoreCase = true), "$field must not contain TODO wording: $id")
    }

    private fun readCsvRows(path: Path): Map<String, Map<String, String>> {
        return CsvTestUtil.readRowsById(path)
    }

    private fun assertRuntimeClassExists(className: String, label: String, id: String) {
        val relativePath = className.removePrefix("cn.kasuminova.astd.")
            .replace('.', '/')
            .let { Path.of("src/main/kotlin/cn/kasuminova/astd/$it.kt") }
        assertTrue(Files.exists(relativePath), "$label does not exist for $id: $className")
    }

    private companion object {
        val expectedHullmodIds = listOf(
            "astd_arc_advanced_fire_control",
            "astd_arc_shared_tactical_network",
            "astd_plasma_armor_shield",
            "astd_ionized_recoil_accumulator",
            "astd_arc_advanced_targeting_system",
            "astd_distributed_pursuit_network",
        )

        val customRenderedTooltipHullmodIds = setOf(
            "astd_plasma_armor_shield",
            "astd_ionized_recoil_accumulator",
        )
    }
}
