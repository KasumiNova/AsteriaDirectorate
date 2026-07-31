package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.arc

import cn.kasuminova.astd.sscsv.annotations.SsCsvComment
import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

/** ARC 系舰船系统（ship_systems.csv + 对应 .system 文件）。 */

object Sys_astd_arc_flare_overdrive_crewed : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_arc_flare_overdrive_crewed"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveCrewedSystemStats"

    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveCrewedSystemAI"

    override val chargeUp: Double = 1.0
    override val active: Double = 8.0
    override val down: Double = 1.0
    override val cooldown: Double = 20.0

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
}

object Sys_astd_arc_flare_overdrive_automated : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_arc_flare_overdrive_automated"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveAutomatedSystemStats"

    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveAutomatedSystemAI"

    override val chargeUp: Double = 1.0
    override val active: Double = 8.0
    override val down: Double = 1.0
    override val cooldown: Double = 20.0

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
}

/** 旧 Arc Flare 系统 id 的兼容行，供已保存 hullSpec / 旧部署数据引用。 */
object Sys_astd_arc_flare_overdrive : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_arc_flare_overdrive"
    override val name: String = systemName("astd_arc_flare_overdrive_crewed")

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveCrewedSystemStats"

    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveCrewedSystemAI"

    override val chargeUp: Double = 1.0
    override val active: Double = 8.0
    override val down: Double = 1.0
    override val cooldown: Double = 20.0

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
}

object Sys_astd_collapse_shift : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_collapse_shift"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.CollapseShiftSystemStats"

    override val systemType: String = "STAT_MOD"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.CollapseShiftSystemAI"

    override val maxUses: Int = 2
    override val regen: Double = 6.666666666666667

    override val chargeUp: Double = 0.25
    override val active: Double = 0.3
    override val down: Double = 0.1
    override val cooldown: Double = 1.0

    override val icon: String = "graphics/icons/hullsys/displacer.png"
    override val useSound: String = "system_phase_cloak_activate"
}

object Sys_astd_micro_burn_drive : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_micro_burn_drive"
    override val name: String = systemName(id)

    override val maxUses: Int = 3
    override val regen: Double = 10.0

    override val chargeUp: Double = 0.25
    override val active: Double = 1.25
    override val down: Double = 0.25
    override val cooldown: Double = 8.0

    override val icon: String = "graphics/icons/hullsys/burn_drive.png"
}

object Sys_astd_arc_shared_flux_network : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_arc_shared_flux_network"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemStats"

    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemAI"

    override val chargeUp: Double = 0.5
    override val active: Double = 10.0
    override val down: Double = 0.5
    override val cooldown: Double = 20.0

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
}

object Sys_astd_fire_control_array : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_fire_control_array"
    override val name: String = systemName(id)

    override val chargeUp: Double = 0.5
    override val active: Double = 10.0
    override val down: Double = 0.5
    override val cooldown: Double = 16.0

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
}

object Sys_astd_plasma_armor_shield_boost : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_plasma_armor_shield_boost"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDPlasmaArmorShieldBoostSystemStats"

    override val systemType: String = "SHIELD_MOD"
    override val aiType: String = "FORTRESS_SHIELD"

    override val chargeUp: Double = 0.5
    override val active: Double? = null
    override val down: Double = 0.5
    override val cooldown: Double = 0.0
    override val toggle: Boolean = true
    override val fluxPerSecondBaseCap: Double = 0.02
    override val hardFlux: Boolean = true
    override val noFiring: Boolean = true
    override val tags: String = "defensive"

    override val icon: String = "graphics/icons/hullsys/fortress_shield.png"
    override val useSound: String? = null
    override val loopSound: String = "system_fortress_shield_loop"
    override val outOfUsesSound: String = "gun_out_of_ammo"
}

object Sys_astd_high_energy_loader : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_high_energy_loader"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.HighEnergyLoaderSystemStats"

    override val chargeUp: Double = 0.25
    override val active: Double = 3.0
    override val down: Double = 0.25
    override val cooldown: Double = 12.0

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
}

object Sys_astd_limit_temporal_thruster : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_limit_temporal_thruster"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemStats"

    override val systemType: String = "ENGINE_MOD"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemAI"

    override val maxUses: Int = 3
    override val regen: Double = 0.1

    override val chargeUp: Double = 0.2
    override val active: Double = 2.0
    override val down: Double = 0.2
    override val cooldown: Double = 1.5

    override val icon: String = "graphics/icons/hullsys/maneuvering_jets.png"
    override val useSound: String = "system_burn_drive_activate"
}

object Sys_astd_static_discharge : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_static_discharge"
    override val name: String = systemName(id)

    override val maxUses: Int = 2
    override val regen: Double = 14.0

    override val chargeUp: Double = 0.15
    override val active: Double = 0.35
    override val down: Double = 0.15
    override val cooldown: Double = 10.0

    override val icon: String = "graphics/icons/hullsys/damper_field.png"
}
