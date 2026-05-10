package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.automated

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_DESC
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SCRIPT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SHORT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName

/** AUTOMATED 设计系 HullMod（原始数据来自 `contents/data/hullmods/hull_mods.csv`）。 */

object HullMod_astd_distributed_grid : HullModEntry() {
    override val id: String = "astd_distributed_grid"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "AUTOMATED"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_distributed_grid.png"
}

object HullMod_astd_inertialess_maneuver : HullModEntry() {
    override val id: String = "astd_inertialess_maneuver"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "AUTOMATED"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_inertialess_maneuver.png"
}

object HullMod_astd_coherent_link : HullModEntry() {
    override val id: String = "astd_coherent_link"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "AUTOMATED"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_coherent_link.png"
}

object HullMod_astd_zero_point_compute_core : HullModEntry() {
    override val id: String = "astd_zero_point_compute_core"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "AUTOMATED"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}
