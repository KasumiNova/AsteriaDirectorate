package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.lens

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

/** LENS 系舰船系统（ship_systems.csv + 对应 .system 文件）。 */

/**
 * 决明级·载人版“回声定影”系统（Task 5 真实实现）。
 *
 * 动机：双模式 hullmod（ASTDLensCrewedModeHullMod）在建船时调用
 * setShipSystemId(astd_echo_fixation_crewed)，故该系统 id 必须在 ship_systems.csv +
 * .system 中存在，否则决明级无法在战斗内实例化（"System with id ... not found"）。
 *
 * statsScript = [EchoFixationCrewedSystemStats]：IN 首帧在落点建定影场（spec §2）；
 * aiScript = [EchoFixationSystemAI]：敌群密集处自动施放并写落点坐标供 stats 读取。
 * active 6s 涵盖定影 4s（spec §2.3）+ 收尾余量。
 */
object Sys_astd_echo_fixation_crewed : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_echo_fixation_crewed"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.EchoFixationCrewedSystemStats"
    override val aiScript: String? =
        "cn.kasuminova.astd.combat.shipsystems.EchoFixationSystemAI"

    override val chargeUp: Double = 0.5
    override val active: Double = 6.0
    override val down: Double = 0.5
    override val cooldown: Double = 14.0

    override val icon: String = "graphics/icons/hullsys/damper_field.png"
}

/** 决明级·无人版“回声定影”系统（Task 5 真实实现，动机同 [Sys_astd_echo_fixation_crewed]）。 */
object Sys_astd_echo_fixation_automated : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_echo_fixation_automated"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.EchoFixationAutomatedSystemStats"
    override val aiScript: String? =
        "cn.kasuminova.astd.combat.shipsystems.EchoFixationSystemAI"

    override val chargeUp: Double = 0.5
    override val active: Double = 6.0
    override val down: Double = 0.5
    override val cooldown: Double = 14.0

    override val icon: String = "graphics/icons/hullsys/damper_field.png"
}

object Sys_astd_jamming_swarm : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_jamming_swarm"
    override val name: String = systemName(id)

    override val chargeUp: Double = 0.5
    override val active: Double = 10.0
    override val down: Double = 0.5
    override val cooldown: Double = 16.0

    override val icon: String = "graphics/icons/hullsys/drone_pd_high.png"
}

object Sys_astd_targeting_beacon : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_targeting_beacon"
    override val name: String = systemName(id)

    override val maxUses: Int = 3
    override val regen: Double = 10.0

    override val chargeUp: Double = 0.2
    override val active: Double = 0.5
    override val down: Double = 0.2
    override val cooldown: Double = 6.0

    override val icon: String = "graphics/icons/hullsys/phase_cloak.png"
}

object Sys_astd_emergency_recall : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_emergency_recall"
    override val name: String = systemName(id)

    override val chargeUp: Double = 0.5
    override val active: Double = 0.75
    override val down: Double = 0.5
    override val cooldown: Double = 18.0

    override val icon: String = "graphics/icons/hullsys/drone_pd_high.png"
}

/**
 * 密蒙级防御系统「引力相位」（相位舰船化改造，2026-09）。
 *
 * 行为与原版相位线圈（phasecloak / PhaseCloakStats）一致；独立系统 id 的意义：
 * - 防御名从原版「相位线圈」独立为「引力相位」（ship_data.csv defense id 指向本系统）；
 * - statsScript 指向 [GravityPhaseCloakStats]（PhaseCloakStats 子类）——后续引力相位专属特效
 *   的接入点，舰船侧无需再改。
 *
 * 数值对齐原版 phasecloak 行（toggle/noHardDissipation/hardFlux/noFiring/noShield/isPhaseCloak）；
 * 相位激活/维持辐能消耗在 ship_data.csv 的 phase cost/upkeep（按辐能容量比例）配置。
 */
object Sys_astd_gravity_phase : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_gravity_phase"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakStats"

    override val systemType: String = "PHASE_CLOAK"
    override val aiType: String = "PHASE_CLOAK"

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

    // 相位披风 .system 必备字段（对齐原版 phasecloak.system；特效色取紫菀引力系紫色调）。
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

object Sys_astd_em_smoke : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_em_smoke"
    override val name: String = systemName(id)

    override val chargeUp: Double = 0.4
    override val active: Double = 3.0
    override val down: Double = 0.4
    override val cooldown: Double = 14.0

    override val icon: String = "graphics/icons/hullsys/phase_cloak.png"
}

object Sys_astd_drone_surge : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_drone_surge"
    override val name: String = systemName(id)

    override val chargeUp: Double = 0.35
    override val active: Double = 6.0
    override val down: Double = 0.35
    override val cooldown: Double = 16.0

    override val icon: String = "graphics/icons/hullsys/drone_pd_high.png"
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
