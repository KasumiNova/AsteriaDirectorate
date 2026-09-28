package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.arc

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

/** ARC 系舰船系统（ship_systems.csv + 对应 .system 文件）。 */

/**
 * 星翼级舰船系统「裂隙折跃」（规格 blue/10-unique.md XC-002 节）：
 * 短暂相位并向飞行向量变距折跃（基准 1000su × 系统射程加成，玩家按鼠标选距/AI 按情境
 * 选距，25%~100% 钳制，缓动曲线加减速），途中撕开虚空裂隙。裂隙伤害为离散点位模型——
 * 沿路径每 100su 一个伤害点位（与虚空锚雷布点同序列，点位半径 100su），三相位状态机：
 * 成形拉开（点位随拉开进度逐个激活，激活点位 0.1s 一拍 400 能量）→ 驻留 5s（全部点位
 * 0.2s 一拍 200 能量）→ 闭合拉上（与拉开同向同耗时，扫掠头自起点向终点推进，被扫过点位
 * 在扫过时刻结算一拍 400 后失效）；路径上布设隐藏虚空锚雷驱离敌方 AI。
 * stats 脚本 [RiftShiftSystemStats] 继承 PhaseCloakStats（相位机制原版口径），
 * 折跃位移/裂隙伤害/闭合拉上由脚本侧承担。
 *
 * 激活窗口动态化：拉开时长按折跃距离占比线性映射（25%~100% 距离 → 0.25~1s），
 * .system active=1.0 填名义最大值，stats 脚本在成形完毕时 forceState(OUT) 提前收尾
 * 相位窗口；裂隙推进时钟与原版 ChargeTracker 同为舰船时间（相位三倍时流下天然对齐）。
 *
 * 非开关相位（toggle=false + active=1.0s）：相位斗篷类系统对齐原版 phasecloak 标记组
 * （isPhaseCloak/hardFlux/noHardDissipation/noFiring/noShield）；冷却 8s 为裁定值
 * （设计案未给，旧坍缩折跃的充能池消耗语义已随机制删除）。
 */
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

    // 相位斗篷 .system 必备字段（对齐原版 phasecloak.system；星翼无 _glow1/_glow2 贴图，
    // phaseHighlight/phaseDiffuse 键省略；特效色取虚空裂隙紫色调）。
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

/**
 * 列星级舰船系统「压制模式」（规格 blue/20-production.md §驱逐舰-舰船系统）：
 * 以机动为代价的火力强化窗口——最大航速/机动性与武器辐能产出削减、护盾承伤减免、
 * 武器射程/射速提升，开启期间持续产出硬辐能（2%/s 起，第 4 秒爬坡至 6%/s 封顶）。
 * 数值三锚点与硬辐能曲线见 SuppressionModeTuning；时序：渐入 1s → 持续 8s → 淡出 1s，冷却 10s。
 */
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
