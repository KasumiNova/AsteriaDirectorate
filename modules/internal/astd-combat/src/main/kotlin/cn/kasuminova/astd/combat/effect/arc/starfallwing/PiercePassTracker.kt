package cn.kasuminova.astd.combat.effect.arc.starfallwing

import java.util.IdentityHashMap

/**
 * 坠星残翼穿透结算的「单次穿越」闩锁：按目标记录本穿越周期内已结算的装甲格/接触标记。
 *
 * 动机：穿透伤害改为按装甲格逐格结算后，需要一个空间向的节拍器取代旧的 0.2s 时间拍——
 * 同一装甲格在单次穿越中只结算一次；目标脱离接触满一帧即视为穿越结束（闩锁移除），
 * 再次接触（折返、下一发弹体重入）算新穿越，全部格重新可结算。
 * 非舰船目标（导弹/陨石）无装甲格，用 [trySettleOnce] 的一次性标记表达「本穿越已结算」。
 *
 * 键按引用相等（IdentityHashMap）：CombatEntityAPI 不保证 equals 语义，同一实体对象即同一目标。
 */
class PiercePassTracker {

    /** 非舰船目标的一次性结算标记（占位格号，不与装甲格号冲突——格号均 ≥0）。 */
    private val settled = IdentityHashMap<Any, HashSet<Int>>()

    /** 本帧接触登记；返回该目标本穿越已结算的格号集（首次接触创建空集）。 */
    fun touch(target: Any): HashSet<Int> = settled.getOrPut(target) { HashSet() }

    /** 首触判定：目标上一帧未接触（不在闩锁表内）。 */
    fun isFirstContact(target: Any): Boolean = target !in settled

    /** 装甲格结算闩锁：本格本穿越未结算 → 记入并返回 true（调用方据此执行一次结算）。 */
    fun trySettleCell(target: Any, packedCell: Int): Boolean = touch(target).add(packedCell)

    /** 非舰船目标一次性结算闩锁：本穿越未结算 → 记入并返回 true。 */
    fun trySettleOnce(target: Any): Boolean = trySettleCell(target, ONCE_MARKER)

    /** 装甲格号打包（gridX/gridY 实际量级 ≤64，低位留 8 位足够）。 */
    fun packCell(cellX: Int, cellY: Int): Int = (cellX shl 8) or (cellY and 0xFF)

    /** 帧末清理：脱离接触的目标整项移除（下次接触 = 新穿越，格结算重置）。 */
    fun retainContacts(contacts: Set<Any>) {
        settled.keys.retainAll(contacts)
    }

    private companion object {
        private const val ONCE_MARKER = -1
    }
}
