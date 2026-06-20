package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.lens

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

/** LENS 系舰船系统（ship_systems.csv + 对应 .system 文件）。 */

/**
 * 引力透镜级·载人版「回声定影」系统（Task 5 真实实现）。
 *
 * 动机：双模式 hullmod（ASTDLensCrewedModeHullMod）在建船时调用
 * setShipSystemId(astd_echo_fixation_crewed)，故该系统 id 必须在 ship_systems.csv +
 * .system 中存在，否则引力透镜级无法在战斗内实例化（"System with id ... not found"）。
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

/** 引力透镜级·无人版「回声定影」系统（Task 5 真实实现，动机同 [Sys_astd_echo_fixation_crewed]）。 */
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

object Sys_astd_stasis_field : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_stasis_field"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.StasisFieldSystemStats"

    override val chargeUp: Double = 0.5
    override val active: Double = 6.0
    override val down: Double = 0.5
    override val cooldown: Double = 14.0

    override val icon: String = "graphics/icons/hullsys/damper_field.png"
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
