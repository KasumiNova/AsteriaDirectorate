package cn.kasuminova.astd.combat.effect.arc.starfallwing

import java.util.IdentityHashMap

/**
 * 坠星残翼穿透结算的「单次穿越」闩锁：按目标记录本穿越周期内的接触/结算标记。
 *
 * 动机：穿透伤害为 0.1s 时间拍结算后，空间向闩锁只剩两处——
 * 护盾首触补拍判定（[isFirstContact]/[touch]：率限窗内切换目标时新目标仍补拍）与
 * 非舰船目标（导弹/陨石）的穿越一次性结算（[trySettleOnce]）。
 * 目标脱离接触满一帧即视为穿越结束（闩锁移除），再次接触（折返、下一发弹体重入）算新穿越。
 *
 * 主弹「振频适应」附加是弹体级全局闩锁（[tryLatchAdaptation]）：首个接触目标附加 1 层后
 * 该弹后续不再附加任何层数，不随穿越重置（[retainContacts] 不动它）。
 *
 * 键按引用相等（IdentityHashMap）：CombatEntityAPI 不保证 equals 语义，同一实体对象即同一目标。
 */
class PiercePassTracker {

    /** 非舰船目标的一次性结算标记（占位格号语义已随装甲格全格结算移除，仅作穿越标记）。 */
    private val settled = IdentityHashMap<Any, HashSet<Int>>()

    /** 主弹振频适应全局闩锁：true = 本弹已附加过一次。 */
    private var adaptationLatched = false

    /** 本帧接触登记；返回该目标本穿越已结算的标记集（首次接触创建空集）。 */
    fun touch(target: Any): HashSet<Int> = settled.getOrPut(target) { HashSet() }

    /** 首触判定：目标上一帧未接触（不在闩锁表内）。 */
    fun isFirstContact(target: Any): Boolean = target !in settled

    /** 非舰船目标一次性结算闩锁：本穿越未结算 → 记入并返回 true。 */
    fun trySettleOnce(target: Any): Boolean = touch(target).add(ONCE_MARKER)

    /**
     * 主弹振频适应附加闩锁：本弹从未附加 → 闩锁并返回 true（调用方据此附加 1 层）。
     * 全局一次性：不区分目标、不随穿越重置，闩锁后该弹对任何目标都不再附加。
     */
    fun tryLatchAdaptation(): Boolean {
        if (adaptationLatched) return false
        adaptationLatched = true
        return true
    }

    /** 帧末清理：脱离接触的目标整项移除（下次接触 = 新穿越）；不影响 [tryLatchAdaptation]。 */
    fun retainContacts(contacts: Set<Any>) {
        settled.keys.retainAll(contacts)
    }

    private companion object {
        private const val ONCE_MARKER = -1
    }
}
