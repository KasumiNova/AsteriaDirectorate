package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.arc

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

object Sys_astd_rift_shift : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_rift_shift"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.RiftShiftSystemStats"

    override val systemType: String = "PHASE_CLOAK"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.RiftShiftSystemAI"

    override val chargeUp: Double = 0.25
    override val active: Double = 1.0
    override val down: Double = 0.25
    override val cooldown: Double = 8.0

    override val hardFlux: Boolean = true
    override val noHardDissipation: Boolean = true
    override val noFiring: Boolean = true
    override val noShield: Boolean = true
    override val isPhaseCloak: Boolean = true

    override val icon: String = "graphics/icons/hullsys/displacer.png"
    override val useSound: String = "system_phase_cloak_activate"
    override val deactivateSound: String = "system_phase_cloak_deactivate"
    override val outOfUsesSound: String = "system_phase_cloak_collision"

    override val extraSystemRawFields: Map<String, String> = linkedMapOf(
        "runScriptWhilePaused" to "true",
        "runScriptWhileIdle" to "true",
        "blockActionsWhileChargingDown" to "false",
        "canNotCauseOverload" to "true",
        "effectColor1" to "[190,140,255,255]",
        "effectColor2" to "[130,80,255,150]",
        "clampTurnRateAfter" to "true",
        "clampMaxSpeedAfter" to "true",
        "engineGlowColor" to "[0,0,0,0]",
        "engineGlowContrailColor" to "[0,0,0,0]",
        "engineGlowLengthMult" to "0",
        "engineGlowWidthMult" to "0",
        "engineGlowGlowMult" to "0",
        "soundFilterType" to "\"LOWPASS\"",
        "soundFilterGain" to "0.8",
        "soundFilterGainHF" to "0.25",
        "shipAlpha" to "1",
    )
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

object Sys_astd_suppression_mode : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_suppression_mode"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDSuppressionModeSystemStats"

    override val systemType: String = "STAT_MOD"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDSuppressionModeSystemAI"

    override val chargeUp: Double = 1.0
    override val active: Double = 8.0
    override val down: Double = 1.0
    override val cooldown: Double = 10.0

    // 持续硬辐能基线 2%/s 走原版 CSV 结算；第 4 秒起的爬坡增量由脚本补差（曲线无法用 CSV 表达）。
    override val fluxPerSecondBaseCap: Double = 0.02
    override val hardFlux: Boolean = true

    override val icon: String = "graphics/icons/hullsys/ammo_feeder.png"
    override val useSound: String = "system_ammo_feeder"
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
