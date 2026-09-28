package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.annotations.SsCsvComment

/**
 * Example entries migrated from the existing debug placeholder generator.
 *
 * You can delete/rename these freely; the generator discovers entries by scanning the package.
 */

@SsCsvComment("ARC 唯一舰 Arc Flare 的系统（占位实现）。")
object TacticalOverdrive : ShipSystemEntry() {
    override val id: String = "astd_tactical_overdrive"
    override val name: String = "战术超频"

    override val chargeUp: Double = 0.5
    override val active: Double = 5.0
    override val down: Double = 0.5
    override val cooldown: Double = 10.0

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
}

