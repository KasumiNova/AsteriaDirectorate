package cn.kasuminova.astd.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArcShipResourceAdaptationTest {

    @Test
    fun `arc jet has complete bloom overlay resource chain`() {
        val hullPath = Path.of("contents/data/hulls/astd_arc_jet.ship")
        val hull = Files.readString(hullPath)
        val weaponData = Files.readString(Path.of("contents/data/weapons/weapon_data.csv"))
        val weaponSpecPath = Path.of("contents/data/weapons/astd_arc_jet_bloom.wpn")
        val weaponSpec = if (Files.exists(weaponSpecPath)) Files.readString(weaponSpecPath) else ""

        assertTrue(
            Files.exists(Path.of("contents/graphics/ships/astd_arc_jet_bloom.png")),
            "Arc Jet bloom texture must exist beside the ship sprite.",
        )
        assertTrue(
            hull.contains("\"builtInWeapons\"") && hull.contains("\"astd_arc_jet_bloom\""),
            "Arc Jet hull must mount the bloom overlay as a built-in decorative weapon.",
        )
        assertTrue(
            hull.contains("\"type\": \"DECORATIVE\"") && hull.contains("\"renderOrderMod\": 3"),
            "Arc Jet bloom slot must use the same decorative render slot pattern as other ARC bloom ships.",
        )
        assertTrue(
            weaponData.contains(",astd_arc_jet_bloom,") && weaponData.contains("Bloom 描边层 (隐藏)"),
            "weapon_data.csv must contain the hidden Arc Jet bloom weapon.",
        )
        assertTrue(
            weaponSpec.contains("\"id\": \"astd_arc_jet_bloom\"") &&
                weaponSpec.contains("\"RENDER_ADDITIVE\"") &&
                weaponSpec.contains("\"graphics/ships/astd_arc_jet_bloom.png\""),
            "Arc Jet bloom .wpn must render the additive bloom texture.",
        )
    }

    @Test
    fun `production arc ships have vanilla-like logistics data`() {
        val rows = readCsvRows(Path.of("contents/data/hulls/ship_data.csv"))
        assertLogistics(rows.getValue("astd_arc_jet"), ShipLogistics(400, 500, 300, 300, 10, 30, 8, 300000, 3.0, 15.0, 600, 0.25, 40, 40))
        assertLogistics(rows.getValue("astd_plasma_arch"), ShipLogistics(150, 250, 150, 100, 3, 33, 8, 100000, 3.0, 12.0, 480, 0.25, 20, 20))
        assertLogistics(rows.getValue("astd_radiation_belt"), ShipLogistics(50, 70, 80, 50, 2, 25, 9, 45000, 5.0, 15.0, 300, 0.25, 11, 11))
    }

    private fun assertLogistics(row: Map<String, String>, expected: ShipLogistics) {
        assertEquals(expected.minCrew.toString(), row.getValue("min crew"))
        assertEquals(expected.maxCrew.toString(), row.getValue("max crew"))
        assertEquals(expected.cargo.toString(), row.getValue("cargo"))
        assertEquals(expected.fuel.toString(), row.getValue("fuel"))
        assertEquals(expected.fuelPerLy.toString(), row.getValue("fuel/ly"))
        assertEquals(expected.range.toString(), row.getValue("range"))
        assertEquals(expected.maxBurn.toString(), row.getValue("max burn"))
        assertEquals(expected.baseValue.toString(), row.getValue("base value"))
        assertEquals(formatNumber(expected.crPercentPerDay), row.getValue("cr %/day"))
        assertEquals(formatNumber(expected.crToDeploy), row.getValue("CR to deploy"))
        assertEquals(expected.peakCrSec.toString(), row.getValue("peak CR sec"))
        assertEquals(formatNumber(expected.crLossPerSec), row.getValue("CR loss/sec"))
        assertEquals(expected.suppliesRec.toString(), row.getValue("supplies/rec"))
        assertEquals(expected.suppliesPerMonth.toString(), row.getValue("supplies/mo"))
    }

    private fun readCsvRows(path: Path): Map<String, Map<String, String>> {
        val lines = Files.readAllLines(path).filter { it.isNotBlank() && !it.startsWith("#") }
        val header = parseCsvLine(lines.first())
        return lines.drop(1).associate { line ->
            val cells = parseCsvLine(line)
            val row = header.mapIndexed { idx, column -> column to cells.getOrElse(idx) { "" } }.toMap()
            row.getValue("id") to row
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    cells += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        cells += current.toString()
        return cells
    }

    private fun formatNumber(value: Double): String {
        val longValue = value.toLong()
        return if (value == longValue.toDouble()) longValue.toString() else value.toString()
    }

    private data class ShipLogistics(
        val minCrew: Int,
        val maxCrew: Int,
        val cargo: Int,
        val fuel: Int,
        val fuelPerLy: Int,
        val range: Int,
        val maxBurn: Int,
        val baseValue: Int,
        val crPercentPerDay: Double,
        val crToDeploy: Double,
        val peakCrSec: Int,
        val crLossPerSec: Double,
        val suppliesRec: Int,
        val suppliesPerMonth: Int,
    )
}
