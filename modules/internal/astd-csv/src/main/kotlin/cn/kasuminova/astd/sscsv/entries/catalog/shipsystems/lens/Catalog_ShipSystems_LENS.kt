package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.lens

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

object Sys_astd_grav_timeflow_interference : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_grav_timeflow_interference"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI"
    override val useSound: String = "system_temporalshell"

    override val chargeUp: Double = 0.5
    override val active: Double = 10.0
    override val down: Double = 0.5
    override val cooldown: Double = 30.0

    override val icon: String = "graphics/icons/hullsys/temporal_shell.png"
}

object Sys_astd_fighter_grav_link : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_fighter_grav_link"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.FighterGravLinkSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String =
        "cn.kasuminova.astd.combat.shipsystems.FighterGravLinkSystemAI"
    override val useSound: String = "system_recall_device"

    override val chargeUp: Double = 0.5
    override val active: Double = 15.0
    override val toggle: Boolean = true
    override val down: Double = 1.0
    override val cooldown: Double = 25.0

    // 持续软辐能产出走原版 CSV 结算；脚本不得再自行 increaseFlux。
    override val fluxPerSecondBaseCap: Double = 0.05

    override val icon: String = "graphics/icons/hullsys/recall_device.png"
}

object Sys_astd_gravity_phase : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_gravity_phase"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakStats"

    override val systemType: String = "PHASE_CLOAK"
    override val aiType: String = "CUSTOM"
    override val aiScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakAI"

    override val toggle: Boolean = true
    override val noHardDissipation: Boolean = true
    override val hardFlux: Boolean = true
    override val noFiring: Boolean = true
    override val noShield: Boolean = true
    override val isPhaseCloak: Boolean = true

    override val chargeUp: Double = 0.5
    override val active: Double? = null
    override val down: Double = 0.5
    override val cooldown: Double = 2.0

    override val tags: String = "defensive"
    override val icon: String = "graphics/icons/hullsys/phase_cloak.png"

    override val useSound: String = "system_phase_cloak_activate"
    override val deactivateSound: String = "system_phase_cloak_deactivate"
    override val outOfUsesSound: String = "system_phase_cloak_collision"

    // 相位披风 .system 必备字段（对齐原版 phasecloak.system）。
    override val extraSystemRawFields: Map<String, String> = linkedMapOf(
        "runScriptWhilePaused" to "true",
        "runScriptWhileIdle" to "true",
        "blockActionsWhileChargingDown" to "false",
        "canNotCauseOverload" to "true",
        "phaseHighlight" to "\"_glow1\"",
        "phaseDiffuse" to "\"_glow2\"",
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

object Sys_astd_grav_rift_generator : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_grav_rift_generator"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemAI"

    override val fluxUseBaseCap: Double = 0.1

    override val chargeUp: Double = 1.0
    override val active: Double = 1.0
    override val down: Double = 1.0
    override val cooldown: Double = 12.0

    override val icon: String = "graphics/icons/hullsys/mine_strike.png"
    override val useSound: String = "riftcascade_windup"
}

object Sys_astd_grav_storm : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_grav_storm"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravStormSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravStormSystemAI"

    override val toggle: Boolean = true
    override val chargeUp: Double = 4.0
    override val active: Double = 0.5
    override val down: Double = 1.5
    override val cooldown: Double = 24.0

    // 激活代价走原版 CSV 结算；脚本不得再自行 increaseFlux。
    override val fluxUseBaseCap: Double = 0.2

    // canUseWhileRightClickSystemOn 必须为 true：原版 PhaseCloakSystem.advanceImpl 每帧把相位中
    // 处于激活态的 toggle 主系统 forceDeactivate 直压 COOLDOWN，本 flag 跳过该压制。
    override val extraSystemRawFields: Map<String, String> = linkedMapOf(
        "canUseWhileRightClickSystemOn" to "true",
    )

    override val icon: String = "graphics/icons/hullsys/quantum_disruptor.png"
    override val useSound: String = "system_quantumdisruptor"
}

object Sys_astd_grav_replicator : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_grav_replicator"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravReplicatorSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravReplicatorSystemAI"

    override val chargeUp: Double = 0.0
    override val active: Double = 2.0
    override val down: Double = 0.0
    override val cooldown: Double = 12.0

    // 激活代价走原版 CSV 结算；每发复制体的折算软辐能是动态值，仍由脚本 increaseFlux 产出。
    override val fluxUseBaseCap: Double = 0.1

    override val icon: String = "graphics/icons/hullsys/entropy_amplifier.png"
    override val useSound: String = "system_entropy"
}

object Sys_astd_burst_flow_fighter : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_burst_flow_fighter"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDBurstFlowSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDBurstFlowSystemAI"

    override val maxUses: Int = 2
    override val regen: Double = 0.1
    override val chargeUp: Double = 0.1
    override val active: Double = 1.0
    override val down: Double = 0.1
    override val cooldown: Double = 1.0

    override val icon: String = "graphics/icons/hullsys/temporal_shell.png"
    override val useSound: String = "system_temporalshell"
}

object Sys_astd_holographic_decoy : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_holographic_decoy"
    override val name: String = systemName(id)

    override val maxUses: Int = 3
    override val regen: Double = 12.0

    override val chargeUp: Double = 0.15
    override val active: Double = 0.5
    override val down: Double = 0.15
    override val cooldown: Double = 8.0

    override val icon: String = "graphics/icons/hullsys/phase_cloak.png"
}
