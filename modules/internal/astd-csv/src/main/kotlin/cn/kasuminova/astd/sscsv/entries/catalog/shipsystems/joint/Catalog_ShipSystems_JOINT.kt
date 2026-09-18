package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.joint

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

/**
 * 联制线（LH）舰船系统，机制设计见 `docs/design/ships/20-joint.md`。
 *
 * statsScript/aiScript 已指向正式实现（ASTDBurstFlowSystemStats / ASTDVisionShiftSystemStats）；
 * CSV 侧时长/冷却/充能参数按设计案落定。
 */

/** 飞星 (ARC)（LH-001）：「落叶飞花」——1s 瞬时爆发时流/机动/备弹恢复，3 充能、5s 充能间隔。 */
object Sys_astd_lh_001_burst_flow : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_lh_001_burst_flow"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDBurstFlowSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDBurstFlowSystemAI"

    override val maxUses: Int = 3
    override val regen: Double = 0.2
    override val chargeUp: Double = 0.1
    override val active: Double = 1.0
    override val down: Double = 0.1
    override val cooldown: Double = 1.0

    override val icon: String = "graphics/icons/hullsys/temporal_shell.png"
    // 音效 id 以 data/config/sounds.json 为准（无第二个下划线）
    override val useSound: String = "system_temporalshell"
}

/** 飞星 (LENS)（LH-002）：「视界变速」——自身时流提升 + 单目标时流压制与伤害转嫁，10s 持续、15s 冷却。 */
object Sys_astd_lh_002_vision_shift : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_lh_002_vision_shift"
    override val name: String = systemName(id)

    override val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDVisionShiftSystemStats"
    override val aiType: String = "CUSTOM"
    override val aiScript: String = "cn.kasuminova.astd.combat.shipsystems.ASTDVisionShiftSystemAI"

    override val chargeUp: Double = 0.5
    override val active: Double = 10.0
    override val down: Double = 0.5
    override val cooldown: Double = 15.0

    override val icon: String = "graphics/icons/hullsys/quantum_disruptor.png"
    override val useSound: String = "system_quantumdisruptor"
}
