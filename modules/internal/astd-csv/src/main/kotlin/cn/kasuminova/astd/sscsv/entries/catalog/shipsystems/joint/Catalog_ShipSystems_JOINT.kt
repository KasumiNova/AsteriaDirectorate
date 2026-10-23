package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.joint

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

object Sys_astd_lh_001_burst_flow : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_lh_001_burst_flow"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDBurstFlowSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDBurstFlowSystemAI"

    override val maxUses: Int = 3
    override val regen: Double = 0.1667
    override val chargeUp: Double = 0.1
    override val active: Double = 1.0
    override val down: Double = 0.1
    override val cooldown: Double = 1.0

    override val icon: String = "graphics/icons/hullsys/temporal_shell.png"

    override val useSound: String = "system_temporalshell"
}

object Sys_astd_lh_002_vision_shift : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_lh_002_vision_shift"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDVisionShiftSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDVisionShiftSystemAI"

    override val chargeUp: Double = 0.5
    override val active: Double = 14.0
    override val down: Double = 0.5
    override val cooldown: Double = 15.0

    override val icon: String = "graphics/icons/hullsys/quantum_disruptor.png"

    override val useSound: String = "system_entropy"
    override val loopSound: String = "system_entropy_loop"
    override val deactivateSound: String = "system_entropy_off"
}
