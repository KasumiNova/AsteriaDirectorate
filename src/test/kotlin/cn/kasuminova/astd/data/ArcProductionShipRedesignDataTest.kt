package cn.kasuminova.astd.data

import cn.kasuminova.astd.testutil.CsvTestUtil
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcProductionShipRedesignDataTest {

    @Test
    fun `production arc ships match redesign combat stats and systems`() {
        val rows = readCsvRows(Path.of("contents/data/hulls/ship_data.csv"))

        assertShipStats(
            rows.getValue("astd_arc_jet"),
            ExpectedShipStats(
                fleetPts = 55,
                hitpoints = 27500,
                armorRating = 1800,
                maxFlux = 25000,
                fluxDissipation = 1400,
                ordnancePoints = 350,
                maxSpeed = 40,
                shieldType = "OMNI",
                shieldArc = 240,
                shieldEfficiency = 0.70,
                peakCrSec = 720,
                systemId = "astd_arc_shared_flux_network",
            ),
        )
        assertShipStats(
            rows.getValue("astd_plasma_arch"),
            ExpectedShipStats(
                fleetPts = 30,
                hitpoints = 13000,
                armorRating = 1450,
                maxFlux = 14000,
                fluxDissipation = 750,
                maxSpeed = 45,
                shieldType = "FRONT",
                shieldArc = 360,
                shieldEfficiency = 0.60,
                peakCrSec = 600,
                systemId = "astd_plasma_armor_shield_boost",
            ),
        )
        assertShipStats(
            rows.getValue("astd_radiation_belt"),
            ExpectedShipStats(
                fleetPts = 14,
                hitpoints = 5500,
                armorRating = 650,
                maxFlux = 6500,
                fluxDissipation = 500,
                maxSpeed = 95,
                shieldEfficiency = 0.70,
                peakCrSec = 360,
                systemId = "astd_limit_temporal_thruster",
            ),
        )
    }

    @Test
    fun `production arc standard variants carry vanilla and unique built-ins`() {
        assertHullBuiltInMods(
            hullPath = Path.of("contents/data/hulls/astd_arc_jet.ship"),
            variantPath = Path.of("contents/data/variants/astd_arc_jet_Standard.variant"),
            required = listOf(
                "advancedcore",
                "armoredweapons",
                "astd_arc_advanced_fire_control",
                "astd_arc_shared_tactical_network",
            ),
        )
        assertVariantWeapons(
            path = Path.of("contents/data/variants/astd_arc_jet_Standard.variant"),
            required = mapOf(
                "WS0001" to "astd_slt4",
                "WS0002" to "astd_slt4",
                "WS0003" to "astd_slt3",
                "WS0004" to "astd_slt3",
                "WS0015" to "astd_drv9",
                "WS0016" to "astd_drv9",
            ),
        )
        assertHullBuiltInMods(
            hullPath = Path.of("contents/data/hulls/astd_plasma_arch.ship"),
            variantPath = Path.of("contents/data/variants/astd_plasma_arch_Standard.variant"),
            required = listOf(
                "stabilizedshieldemitter",
                "missleracks",
                "astd_plasma_armor_shield",
                "astd_ionized_recoil_accumulator",
            ),
        )
        assertVariantWeapons(
            path = Path.of("contents/data/variants/astd_plasma_arch_Standard.variant"),
            required = mapOf(
                "WS0001" to "astd_slt4",
                "WS0002" to "astd_slt4",
                "WS0003" to "astd_drv9",
                "WS0004" to "astd_drv9",
                "WS0012" to "astd_rct6",
                "WS0013" to "astd_rct6",
            ),
        )
        val radiationBeltBuiltIns = assertHullBuiltInMods(
            hullPath = Path.of("contents/data/hulls/astd_radiation_belt.ship"),
            variantPath = Path.of("contents/data/variants/astd_radiation_belt_Standard.variant"),
            required = listOf(
                "magazines",
                "auxiliarythrusters",
                "astd_arc_advanced_targeting_system",
                "astd_distributed_pursuit_network",
            ),
        )
        assertFalse("expanded_magazines" in radiationBeltBuiltIns, "Starsector canonical expanded magazines id is magazines")
        assertVariantWeapons(
            path = Path.of("contents/data/variants/astd_radiation_belt_Standard.variant"),
            required = mapOf(
                "WS0001" to "astd_vpd6",
                "WS0002" to "astd_spc3",
                "WS0003" to "astd_spc3",
                "WS0007" to "astd_rct6",
                "WS0008" to "astd_rct6",
            ),
        )
    }

    @Test
    fun `production arc ship systems are registered with expected runtime scripts`() {
        val rows = readCsvRows(Path.of("contents/data/shipsystems/ship_systems.csv"))
        listOf(
            "astd_arc_shared_flux_network",
            "astd_plasma_armor_shield_boost",
            "astd_limit_temporal_thruster",
        ).forEach { id ->
            assertTrue(rows.containsKey(id), "missing ship system csv row: $id")
        }

        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_arc_shared_flux_network.system"),
            id = "astd_arc_shared_flux_network",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemStats",
            aiType = "CUSTOM",
            aiScript = "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemAI",
        )
        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_plasma_armor_shield_boost.system"),
            id = "astd_plasma_armor_shield_boost",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDPlasmaArmorShieldBoostSystemStats",
            aiType = "CUSTOM",
            aiScript = "cn.kasuminova.astd.combat.shipsystems.ASTDPlasmaArmorShieldBoostSystemAI",
        )
        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_limit_temporal_thruster.system"),
            id = "astd_limit_temporal_thruster",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemStats",
            aiType = "CUSTOM",
            aiScript = "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemAI",
        )
    }

    private fun assertShipStats(row: Map<String, String>, expected: ExpectedShipStats) {
        assertEquals(expected.fleetPts.toString(), row.getValue("fleet pts"), "fleet pts mismatch for ${row.getValue("id")}")
        assertEquals(expected.hitpoints.toString(), row.getValue("hitpoints"), "hitpoints mismatch for ${row.getValue("id")}")
        assertEquals(expected.armorRating.toString(), row.getValue("armor rating"), "armor rating mismatch for ${row.getValue("id")}")
        assertEquals(expected.maxFlux.toString(), row.getValue("max flux"), "max flux mismatch for ${row.getValue("id")}")
        assertEquals(expected.fluxDissipation.toString(), row.getValue("flux dissipation"), "flux dissipation mismatch for ${row.getValue("id")}")
        expected.ordnancePoints?.let {
            assertEquals(it.toString(), row.getValue("ordnance points"), "ordnance points mismatch for ${row.getValue("id")}")
        }
        assertEquals(expected.maxSpeed.toString(), row.getValue("max speed"), "max speed mismatch for ${row.getValue("id")}")
        expected.shieldType?.let {
            assertEquals(it, row.getValue("shield type"), "shield type mismatch for ${row.getValue("id")}")
        }
        expected.shieldArc?.let {
            assertEquals(it.toString(), row.getValue("shield arc"), "shield arc mismatch for ${row.getValue("id")}")
        }
        assertEquals(formatNumber(expected.shieldEfficiency), row.getValue("shield efficiency"), "shield efficiency mismatch for ${row.getValue("id")}")
        assertEquals(expected.peakCrSec.toString(), row.getValue("peak CR sec"), "peak CR sec mismatch for ${row.getValue("id")}")
        assertEquals(expected.systemId, row.getValue("system id"), "system id mismatch for ${row.getValue("id")}")
    }

    private fun readCsvRows(path: Path): Map<String, Map<String, String>> {
        return CsvTestUtil.readRowsById(path)
    }

    private fun assertHullBuiltInMods(hullPath: Path, variantPath: Path, required: List<String>): Set<String> {
        val builtIns = hullBuiltInMods(hullPath)
        required.forEach { id ->
            assertTrue(id in builtIns, "${hullPath.fileName} missing builtInMod: $id")
        }
        val variantPermaMods = variantPermaMods(variantPath)
        required.forEach { id ->
            assertFalse(id in variantPermaMods, "${variantPath.fileName} must not keep built-in hullmod as removable permaMod: $id")
        }
        return builtIns
    }

    private fun assertVariantWeapons(path: Path, required: Map<String, String>) {
        val groups = JSONObject(Files.readString(path)).getJSONArray("weaponGroups")
        assertTrue(groups.length() > 0, "${path.fileName} must define a production weapon loadout")
        assertValidFluxAllocation(path)
        val weapons = linkedMapOf<String, String>()
        for (groupIndex in 0 until groups.length()) {
            val groupWeapons = groups.getJSONObject(groupIndex).getJSONObject("weapons")
            val keys = groupWeapons.keys()
            while (keys.hasNext()) {
                val slot = keys.next() as String
                weapons[slot] = groupWeapons.getString(slot)
            }
        }
        required.forEach { (slot, weaponId) ->
            assertEquals(weaponId, weapons[slot], "${path.fileName} weapon mismatch for slot $slot")
        }
    }

    private fun assertValidFluxAllocation(path: Path) {
        val variant = JSONObject(Files.readString(path))
        val vents = variant.getInt("fluxVents")
        val capacitors = variant.getInt("fluxCapacitors")
        assertTrue(vents >= 0, "${path.fileName} fluxVents must not be negative")
        assertTrue(capacitors >= 0, "${path.fileName} fluxCapacitors must not be negative")
        assertTrue(vents + capacitors <= 50, "${path.fileName} exceeds Starsector flux vent/capacitor limit")
    }

    private fun variantPermaMods(path: Path): Set<String> {
        val json = JSONObject(Files.readString(path))
        if (!json.has("permaMods")) return emptySet()
        val array = json.getJSONArray("permaMods")
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private fun hullBuiltInMods(path: Path): Set<String> {
        val json = JSONObject(Files.readString(path))
        if (!json.has("builtInMods")) return emptySet()
        val array = json.getJSONArray("builtInMods")
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private fun assertSystemFile(path: Path, id: String, statsScript: String, aiType: String, aiScript: String?) {
        val values = readJsonStringValues(path)
        assertEquals(id, values["id"], "system id mismatch for ${path.fileName}")
        assertEquals(statsScript, values["statsScript"], "statsScript mismatch for $id")
        assertEquals(aiType, values["aiType"], "aiType mismatch for $id")
        if (aiScript == null) {
            assertFalse(values.containsKey("aiScript"), "system should not define aiScript: $id")
        } else {
            assertEquals(aiScript, values["aiScript"], "aiScript mismatch for $id")
        }
    }

    private fun readJsonStringValues(path: Path): Map<String, String> =
        JSONObject(Files.readString(path)).let { json ->
            val values = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next() as String
                values[key] = json.getString(key)
            }
            values
        }

    private fun formatNumber(value: Double): String {
        val longValue = value.toLong()
        return if (value == longValue.toDouble()) longValue.toString() else value.toString()
    }

    private data class ExpectedShipStats(
        val fleetPts: Int,
        val hitpoints: Int,
        val armorRating: Int,
        val maxFlux: Int,
        val fluxDissipation: Int,
        val ordnancePoints: Int? = null,
        val maxSpeed: Int,
        val shieldType: String? = null,
        val shieldArc: Int? = null,
        val shieldEfficiency: Double,
        val peakCrSec: Int,
        val systemId: String,
    )
}
