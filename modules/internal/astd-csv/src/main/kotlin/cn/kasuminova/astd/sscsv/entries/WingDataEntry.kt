package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

abstract class WingDataEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.WING_DATA

    abstract val id: String

    abstract val variant: String

    open val tags: String = ""

    open val tier: Int = 0

    open val rarity: Double? = null

    open val fleetPts: Int = 0

    open val opCost: Int = 0

    open val formation: String = ""

    open val range: Int = 0

    open val attackRunRange: Int = 0

    open val attackPositionOffset: Int? = null

    open val num: Int = 0

    open val role: String = ""

    open val roleDesc: String = ""

    open val refit: Int = 0

    open val baseValue: Int = 0

    open val number: Int? = null

    final override val key: String get() = id

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "variant" to variant,
        "tags" to tags,
        "tier" to tier,
        "rarity" to rarity,
        "fleet pts" to fleetPts,
        "op cost" to opCost,
        "formation" to formation,
        "range" to range,
        "attackRunRange" to attackRunRange,
        "attackPositionOffset" to attackPositionOffset,
        "num" to num,
        "role" to role,
        "role desc" to roleDesc,
        "refit" to refit,
        "base value" to baseValue,
        "number" to number,
    )
}
