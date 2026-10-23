package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

abstract class HullModEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.HULL_MODS

    abstract val id: String

    abstract val name: String

    open val tier: Int = 1

    open val rarity: Int = 1

    open val tech: String = ""

    open val tags: String = ""

    open val uiTags: String = ""

    open val baseValue: Int = 0

    open val unlocked: Boolean = false

    open val hidden: Boolean = false

    open val hiddenEverywhere: Boolean = false

    open val costFrigate: Int = 0

    open val costDestroyer: Int = 0

    open val costCruiser: Int = 0

    open val costCapital: Int = 0

    open val script: String = ""

    open val desc: String = ""

    open val short: String = ""

    open val sModDesc: String = ""

    open val sprite: String = ""

    final override val key: String get() = id

    private val isAstdBuiltIn: Boolean
        get() = tags.split(',')
            .map { it.trim() }
            .any { it == "astd_builtin" }

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "id" to id,
        "tier" to tier,
        "rarity" to rarity,
        "tech/manufacturer" to tech,
        "tags" to tags,
        "uiTags" to uiTags,
        "base value" to baseValue,
        "unlocked" to if (unlocked) "TRUE" else "FALSE",
        "hidden" to if (hidden || isAstdBuiltIn) "TRUE" else "FALSE",
        "hiddenEverywhere" to if (hiddenEverywhere) "TRUE" else "FALSE",
        "cost_frigate" to costFrigate,
        "cost_dest" to costDestroyer,
        "cost_cruiser" to costCruiser,
        "cost_capital" to costCapital,
        "script" to script,
        "desc" to desc,
        "short" to short,
        "sModDesc" to sModDesc,
        "sprite" to sprite,
    )
}
