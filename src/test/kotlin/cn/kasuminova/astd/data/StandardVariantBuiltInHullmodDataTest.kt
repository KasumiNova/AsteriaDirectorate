package cn.kasuminova.astd.data

import cn.kasuminova.astd.testutil.CsvTestUtil
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandardVariantBuiltInHullmodDataTest {

    @Test
    fun `standard variants keep built-in hullmods on hull specs instead of perma mods`() {
        val hullModRows = CsvTestUtil.readRowsById(Path.of("contents/data/hullmods/hull_mods.csv"))
        val astdBuiltInHullmods = hullModRows.values
            .filter { row -> row.hasTag("astd_builtin") }
            .map { row -> row.getValue("id") }
            .toSet()

        expectedBuiltInsByHull.forEach { (hullId, expectedBuiltIns) ->
            val hullPath = Path.of("contents/data/hulls/$hullId.ship")
            val variantPath = Path.of("contents/data/variants/${hullId}_Standard.variant")
            val hullBuiltIns = readStringArray(hullPath, "builtInMods")
            val variantHullMods = readStringArray(variantPath, "hullMods")
            val variantPermaMods = readStringArray(variantPath, "permaMods")

            expectedBuiltIns.forEach { id ->
                assertTrue(id in hullBuiltIns, "$hullId should declare built-in hullmod in .ship: $id")
                assertFalse(id in variantHullMods, "$hullId must not expose built-in hullmod as variant hullMod: $id")
                assertFalse(id in variantPermaMods, "$hullId must not expose built-in hullmod as variant permaMod: $id")
            }
        }

        Files.list(Path.of("contents/data/variants")).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith("_Standard.variant") }
                .filter { it.fileName.toString() != "astd_arc_flare_Standard.variant" }
                .forEach { path ->
                    val variantHullMods = readStringArray(path, "hullMods")
                    val variantPermaMods = readStringArray(path, "permaMods")
                    assertTrue(
                        variantHullMods.intersect(astdBuiltInHullmods).isEmpty(),
                        "${path.fileName} must not use hullMods for astd built-ins: ${variantHullMods.intersect(astdBuiltInHullmods)}",
                    )
                    assertTrue(readStringArray(path, "permaMods").isEmpty(), "${path.fileName} must not use permaMods for built-ins")
                    assertTrue(
                        variantPermaMods.intersect(astdBuiltInHullmods).isEmpty(),
                        "${path.fileName} must not use permaMods for astd built-ins: ${variantPermaMods.intersect(astdBuiltInHullmods)}",
                    )
                }
        }
    }

    @Test
    fun `astd built-in hullmods are hidden and not normally unlockable`() {
        val rows = CsvTestUtil.readRowsById(Path.of("contents/data/hullmods/hull_mods.csv"))
        val builtInRows = rows.values.filter { row ->
            row.getValue("tags")
                .split(',')
                .map { it.trim() }
                .any { it == "astd_builtin" }
        }
        assertTrue(builtInRows.isNotEmpty(), "expected at least one astd built-in hullmod row")

        builtInRows.forEach { row ->
            val id = row.getValue("id")
            assertEquals("FALSE", row.getValue("unlocked"), "$id must not be normally unlockable")
            assertEquals("TRUE", row.getValue("hidden"), "$id must be hidden from normal refit/codex acquisition UI")
            assertEquals("FALSE", row.getValue("hiddenEverywhere"), "$id should remain visible when installed as a built-in hullmod")
        }
    }

    private fun readStringArray(path: Path, key: String): Set<String> {
        val json = JSONObject(Files.readString(path))
        if (!json.has(key)) return emptySet()
        val array = json.getJSONArray(key)
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private fun Map<String, String>.hasTag(tag: String): Boolean =
        getValue("tags")
            .split(',')
            .map { it.trim() }
            .any { it == tag }

    private companion object {
        val expectedBuiltInsByHull = linkedMapOf(
            "astd_apex_logic" to listOf("astd_nano_restoration_protocol", "astd_zero_point_compute_core"),
            "astd_arc_jet" to listOf(
                "advancedcore",
                "armoredweapons",
                "astd_arc_advanced_fire_control",
                "astd_arc_shared_tactical_network",
            ),
            "astd_dark_tide_nebula" to listOf("astd_nano_restoration_protocol", "astd_dark_tide_jammer"),
            "astd_diffraction" to listOf("astd_nano_restoration_protocol", "astd_phase_diffraction_shield"),
            "astd_gravitational_lens" to listOf("astd_nano_restoration_protocol", "astd_lens_array_core"),
            "astd_magnetic_storm_zigzag" to listOf("astd_nano_restoration_protocol", "astd_inertialess_maneuver"),
            "astd_magnetosphere_disturbance" to listOf("astd_nano_restoration_protocol", "astd_fleet_coordination_relay"),
            "astd_nebula_echo" to listOf(
                "astd_nano_restoration_protocol",
                "astd_echo_emitter",
                "astd_echo_reentry_buffer",
                "astd_phase_resonance_jamming",
            ),
            "astd_negentropy_edge" to listOf("astd_virtual_particle_lattice_web", "astd_transient_potential_manifold"),
            "astd_plasma_arch" to listOf(
                "stabilizedshieldemitter",
                "missleracks",
                "astd_plasma_armor_shield",
                "astd_ionized_recoil_accumulator",
            ),
            "astd_radiation_belt" to listOf(
                "magazines",
                "auxiliarythrusters",
                "astd_arc_advanced_targeting_system",
                "astd_distributed_pursuit_network",
            ),
        )
    }
}
