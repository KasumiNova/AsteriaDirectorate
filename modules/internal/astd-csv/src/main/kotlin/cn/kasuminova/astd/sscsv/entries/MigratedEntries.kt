package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvCodec
import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.GeneratedFile
import cn.kasuminova.astd.sscsv.SsCsvCellsEntry
import cn.kasuminova.astd.sscsv.SsExtraOutputs

abstract class MigratedCsvLineEntry(
    final override val target: CsvTarget,

    final override val key: String,

    private val csvLine: String,
) : SsCsvCellsEntry {

    final override fun toCells(header: List<String>): List<String?> {
        val cells = CsvCodec.parseCsvLine(csvLine)
        return when {
            cells.size == header.size -> cells
            cells.size < header.size -> cells + List(header.size - cells.size) { "" }
            else -> error("Row has ${cells.size} cells but header has ${header.size} cells for $key (${target.name})")
        }
    }

    final override fun toRow(): Map<String, Any?> = emptyMap()
}

open class MigratedShipSystemEntry(
    val id: String,
    csvLine: String,
) : MigratedCsvLineEntry(CsvTarget.SHIP_SYSTEMS, id, csvLine), SsExtraOutputs {

    open val systemType: String = "STAT_MOD"

    open val aiType: String = "WEAPON_BOOST"

    open val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.PlaceholderShipSystemStats"

    open val useSound: String = "system_ammo_feeder"

    override fun extraFiles(): List<GeneratedFile> {
        val json = """
            {
                "id": "$id",
                "type": "$systemType",
                "aiType": "$aiType",
                "statsScript": "$statsScript",
                "useSound": "$useSound"
            }
        """.trimIndent() + "\n"

        return listOf(
            GeneratedFile(
                relativePath = "data/shipsystems/$id.system",
                content = json,
            )
        )
    }
}

open class MigratedHullModEntry(
    val id: String,
    csvLine: String,
) : MigratedCsvLineEntry(CsvTarget.HULL_MODS, id, csvLine)

open class MigratedWeaponDataEntry(
    val id: String,
    csvLine: String,
) : MigratedCsvLineEntry(CsvTarget.WEAPON_DATA, id, csvLine)

open class MigratedShipDataEntry(
    val id: String,
    csvLine: String,
) : MigratedCsvLineEntry(CsvTarget.SHIP_DATA, id, csvLine)
