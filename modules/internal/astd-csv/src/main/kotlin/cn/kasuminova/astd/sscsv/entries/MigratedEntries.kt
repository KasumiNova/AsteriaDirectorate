package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvCodec
import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.GeneratedFile
import cn.kasuminova.astd.sscsv.SsCsvCellsEntry
import cn.kasuminova.astd.sscsv.SsCsvEntry
import cn.kasuminova.astd.sscsv.SsExtraOutputs

/**
 * 无损迁移用的 CSV 行 Entry：
 * - 保存原始 CSV 行文本（不做数值格式化、不重排字段）
 * - 生成时按 header 顺序输出 cells，从而尽量保持与原文件一致的 diff
 */
abstract class MigratedCsvLineEntry(
    /** 该行所属的 CSV 目标文件类型。 */
    final override val target: CsvTarget,

    /** 唯一键（通常就是 id）；用于去重与排序。 */
    final override val key: String,

    /** 原始 CSV 行内容（不包含 header）。 */
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

/** 同时生成：`ship_systems.csv` + `data/shipsystems/<id>.system` */
open class MigratedShipSystemEntry(
    /** 系统 id；同时用于 `.system` 文件名与 ship_systems.csv 的 key。 */
    val id: String,
    csvLine: String,
) : MigratedCsvLineEntry(CsvTarget.SHIP_SYSTEMS, id, csvLine), SsExtraOutputs {

    /** `.system` 的 type 字段（例如 STAT_MOD）。 */
    open val systemType: String = "STAT_MOD"

    /** `.system` 的 aiType 字段。 */
    open val aiType: String = "WEAPON_BOOST"

    /** `.system` 的 statsScript 字段：系统效果脚本类（完整限定名）。 */
    open val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.PlaceholderShipSystemStats"

    /** `.system` 的 useSound 字段：激活音效 id。 */
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
    /** hullmod id（用于 hull_mods.csv 的 key）。 */
    val id: String,
    csvLine: String,
) : MigratedCsvLineEntry(CsvTarget.HULL_MODS, id, csvLine)

open class MigratedWeaponDataEntry(
    /** weapon id（用于 weapon_data.csv 的 key）。 */
    val id: String,
    csvLine: String,
) : MigratedCsvLineEntry(CsvTarget.WEAPON_DATA, id, csvLine)

open class MigratedShipDataEntry(
    /** ship hull id（用于 ship_data.csv 的 key）。 */
    val id: String,
    csvLine: String,
) : MigratedCsvLineEntry(CsvTarget.SHIP_DATA, id, csvLine)
