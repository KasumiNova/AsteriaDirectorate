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

@SsCsvComment("ARC 唯一舰 Negentropy Edge 的系统（占位实现），带充能。")
object CollapseShift : ShipSystemEntry() {
    override val id: String = "astd_collapse_shift"
    override val name: String = "坍缩折跃"

    override val maxUses: Int = 2
    override val regen: Double = 6.666666666666667

    override val chargeUp: Double = 0.1
    override val active: Double = 0.3
    override val down: Double = 0.1
    override val cooldown: Double = 1.0

    override val icon: String = "graphics/icons/hullsys/displacer.png"
}

