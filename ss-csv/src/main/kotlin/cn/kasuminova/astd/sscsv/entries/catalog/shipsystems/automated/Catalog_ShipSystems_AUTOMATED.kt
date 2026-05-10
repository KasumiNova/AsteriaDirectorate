package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.automated

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

/** AUTOMATED 系舰船系统（ship_systems.csv + 对应 .system 文件）。 */

object Sys_astd_grid_hardening : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_grid_hardening"
    override val name: String = systemName(id)

    override val chargeUp: Double = 0.25
    override val active: Double = 5.0
    override val down: Double = 0.25
    override val cooldown: Double = 16.0

    override val icon: String = "graphics/icons/hullsys/damper_field.png"
}

object Sys_astd_emp_burst : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_emp_burst"
    override val name: String = systemName(id)

    override val maxUses: Int = 2
    override val regen: Double = 14.0

    override val chargeUp: Double = 0.1
    override val active: Double = 0.3
    override val down: Double = 0.1
    override val cooldown: Double = 10.0

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
}

object Sys_astd_signal_overload : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_signal_overload"
    override val name: String = systemName(id)

    override val maxUses: Int = 1
    override val regen: Double = 30.0

    override val chargeUp: Double = 0.2
    override val active: Double = 0.6
    override val down: Double = 0.2
    override val cooldown: Double = 20.0

    override val icon: String = "graphics/icons/hullsys/displacer.png"
}

object Sys_astd_logic_collapse : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_logic_collapse"
    override val name: String = systemName(id)

    override val maxUses: Int = 2
    override val regen: Double = 22.0

    override val chargeUp: Double = 0.5
    override val active: Double = 0.8
    override val down: Double = 0.5
    override val cooldown: Double = 18.0

    override val icon: String = "graphics/icons/hullsys/phase_cloak.png"
}
