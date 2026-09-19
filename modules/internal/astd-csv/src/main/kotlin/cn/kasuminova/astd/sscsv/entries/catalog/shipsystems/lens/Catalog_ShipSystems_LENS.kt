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

/**
 * 飞蓬级舰船系统「战机引力联结器」（purple/20-production.md §1，2026-09 重构；
 * 2026-09 二轮：toggle 化 + CUSTOM AI）。
 *
 * 以原版召回装置为基线的增强：持续至多 15s 强化机群（时流/减伤），结束时进入 1s 召回
 * 窗口（快照机群相位渐隐、窗口末 land 回收并快速重新出击）；代价为持续软辐能产出与
 * 结束时的软→硬转化。
 *
 * - toggle=true + active=15s：原版 ChargeTracker 对「toggle 且 active>0」的系统在 ACTIVE 期间
 *   按 15s 上限推进系统条（玩家可见剩余时间）并在到期时自动转 OUT；ACTIVE 期间再次按键
 *   仍可提前关闭（canBeDeactivated 默认 true）；机群全灭提前结束由 stats 脚本计时补发
 *   useSystem() 收口（`ShipSystemAPI.deactivate()` 等价 forceDeactivate——直接跳 COOLDOWN、
 *   跳过 OUT 充能消退窗，召回结算不会触发，故全链路统一走 fire 路径）。
 * - down=1.0s：召回特效窗（快照机群相位态 + 渐隐，窗口结束 land 回收，对齐原版召回装置视觉）。
 * - aiType=CUSTOM + aiScript=[FighterGravLinkSystemAI]：原 RECALL_DEVICE 按「需整备战机占比」
 *   决策，与召回语义匹配但几乎不为强化效果主动激活；CUSTOM AI 按「在外战机规模 + 威胁距离」决策。
 */
object Sys_astd_fighter_grav_link : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_fighter_grav_link"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.FighterGravLinkSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String? =
        "cn.kasuminova.astd.combat.shipsystems.FighterGravLinkSystemAI"
    override val useSound: String? = "system_recall_device"

    override val chargeUp: Double = 0.5
    override val active: Double = 15.0
    override val toggle: Boolean = true
    override val down: Double = 1.0
    override val cooldown: Double = 25.0

    override val icon: String = "graphics/icons/hullsys/recall_device.png"
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

/**
 * 茑萝级舰船系统「引力裂隙发生器」（purple/20-production.md §2，2026-09 D27 重做；
 * 2026-09-20 二轮重做：目标锁定 + 贴图旋涡 + 真实光束）。
 *
 * 需锁定目标舰激活（玩家取 shipTarget，无目标时系统不可用并提示「无目标」）：
 * 激活时在目标舰随机位置（优先无护盾覆盖区域）产生贴图旋涡（60°/s 自旋，1s 淡入 /
 * 1s 淡出，共 3s），1s 后旋涡中心向目标发射**真实光束**（隐藏光束武器挂 FX drone，
 * 机制与布雷间隔直接复用原版裂隙洪流发射极 riftcascade，仅改红色正色、不用反色星云）。
 * 光束命中目标期间每 0.1s 沿目标周边弧线布一枚裂隙（上限 5 枚），裂隙对附近目标造成
 * 能量伤害（随裂隙序位递增、按难度系数三锚点缩放）。
 * 时序：chargeUp 1s（旋涡淡入）→ active 1s（光束发射）→ down 1s（旋涡淡出），
 * 光束全程 2s，冷却 12s。stats/AI 见 [GravityRiftSystemStats] / [GravityRiftSystemAI]。
 */
object Sys_astd_grav_rift_generator : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_grav_rift_generator"
    override val name: String = systemName(id)

    override val statsScript: String =
        "cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String? =
        "cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemAI"

    override val chargeUp: Double = 1.0
    override val active: Double = 1.0
    override val down: Double = 1.0
    override val cooldown: Double = 12.0

    override val icon: String = "graphics/icons/hullsys/mine_strike.png"
    override val useSound: String = "riftcascade_windup"
}

/**
 * 落叶飞花（战机）：茑萝级内置战机「游丝」的战术系统（purple/20-production.md §2）。
 *
 * 机制与飞星 (ARC) 的落叶飞花一致（复用 [ASTDBurstFlowSystemStats] / [ASTDBurstFlowSystemAI]），
 * 仅节奏调整：回充时间 10s（regen 0.1）、最大充能 2。
 */
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
