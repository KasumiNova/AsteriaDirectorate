package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.joint

import cn.kasuminova.astd.sscsv.entries.ShipSystemWithSystemFileEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipsystems.systemName

/**
 * 联制线（LH）舰船系统，机制设计见 `docs/design/ships/20-joint.md`。
 *
 * 当前 statsScript 走占位脚本（PlaceholderShipSystemStats），机制代码待实装后替换为正式实现类。
 * CSV 侧的时长/冷却/充能参数已按设计案落定，占位期即可在实机中验证节奏。
 */

/** 锻萼（LH-001）：「落叶飞花」——1s 瞬时爆发时流/机动/备弹恢复，3 充能、5s 充能间隔。 */
object Sys_astd_lh_001_burst_flow : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_lh_001_burst_flow"
    override val name: String = systemName(id)

    // TODO(落叶飞花实装): 替换为正式 stats 脚本（爆发时流 200%/250%/400% + 机动 + 备弹恢复，难度系数五档）。
    override val maxUses: Int = 3
    override val regen: Double = 0.2
    override val chargeUp: Double = 0.1
    override val active: Double = 1.0
    override val down: Double = 0.1
    override val cooldown: Double = 1.0

    override val icon: String = "graphics/icons/hullsys/temporal_shell.png"
}

/** 飞星（LH-002）：「视界变速」——自身时流提升 + 单目标时流压制与伤害转嫁，10s 持续、15s 冷却。 */
object Sys_astd_lh_002_vision_shift : ShipSystemWithSystemFileEntry() {
    override val id: String = "astd_lh_002_vision_shift"
    override val name: String = systemName(id)

    // TODO(视界变速实装): 替换为正式 stats 脚本（自身时流 50%~100%、目标时流压制按体型分档、伤害转嫁窗口）。
    override val chargeUp: Double = 0.5
    override val active: Double = 10.0
    override val down: Double = 0.5
    override val cooldown: Double = 15.0

    override val icon: String = "graphics/icons/hullsys/quantum_disruptor.png"
}
