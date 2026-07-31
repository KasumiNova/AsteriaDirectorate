package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.combat.CombatRandom

/**
 * 电驱加速炮的机制数值锚点与纯计算（规格 03 §2.2）。
 *
 * 动机：不稳定装药（命中随机附加动能伤害）与净空加速（低辐能射程加成）两条机制的
 * 难度取值与几何公式集中在一处，供 OnHitEffect / WeaponEffect 两个插件接线调用，
 * 单元测试直接驱动本对象，插件内不留重复逻辑。
 *
 * 数值缩放口径（90 计划全局约定）：敌方按轨一 k_s 三锚点映射；玩家来源（owner == 0）固定 v2。
 */
object ElectricDriveAcceleratorDifficulty {

    /** 不稳定装药上限（面板百分比）：迟暮 25 / 砺刃 56.25 / 破晓 150（设计案显式锚点）。 */
    val CHARGE_MAX_PCT = ScalingEntry(25f, 56.25f, 150f, ScalingMap.LINEAR)

    /**
     * 射程锚点专用映射：四档线性（迟暮 v1 / 砺刃 v2 / 远征 (v2+v5)/2 / 破晓 v5）。
     *
     * 设计案显式给定远征 +300，恰为 v2(200)→v5(400) 的中点；标准 [ScalingMap.LINEAR] 的
     * v2→v5 段按 [2,5] 三等分，k=3 产出 266.67，与显式锚点矛盾。故 v2→v5 段拆为
     * k∈[2,3] 与 k∈[3,5] 两段线性，使四个设计值全部精确命中。
     */
    private val RANGE_BONUS_MAP = ScalingMap { k, v1, v2, v5 ->
        val mid = (v2 + v5) / 2f
        when {
            k <= 2f -> v1 + (v2 - v1) * (k - 1f).coerceIn(0f, 1f)
            k <= 3f -> v2 + (mid - v2) * (k - 2f)
            else -> mid + (v5 - mid) * ((k - 3f) / 2f).coerceIn(0f, 1f)
        }
    }

    /** 净空加速射程加成（su）：迟暮 100 / 砺刃 200 / 远征 300 / 破晓 400（设计案显式锚点）。 */
    val RANGE_BONUS_SU = ScalingEntry(100f, 200f, 400f, RANGE_BONUS_MAP)

    /** 辐能衰减区间：水平 ≤ 0.2 满额，≥ 0.4 归零（设计案定稿，固定不缩放）。 */
    const val FLUX_FULL_THRESHOLD = 0.2f
    const val FLUX_ZERO_THRESHOLD = 0.4f

    /** 追加伤害触发阈值：extra ≥ 该值才产生伤害事件与浮字（roll ≈ 0 不弹 0 伤害浮字）。 */
    const val EXTRA_APPLY_THRESHOLD = 1f

    /** 装药上限取值：玩家来源（owner == 0）固定 v2，否则按轨一 k_s 映射。每次命中调用一次。 */
    fun chargeMaxPct(tuning: DifficultyTuning, owner: Int): Float =
        if (owner == 0) CHARGE_MAX_PCT.v2 else tuning.value(CHARGE_MAX_PCT)

    /** 射程加成基础值取值：玩家固定 v2，否则按轨一 k_s 映射。每帧调用一次（LunaLib 调档即时生效）。 */
    fun rangeBonusBase(tuning: DifficultyTuning, owner: Int): Float =
        if (owner == 0) RANGE_BONUS_SU.v2 else tuning.value(RANGE_BONUS_SU)

    /**
     * 辐能衰减系数：[level] ≤ [FLUX_FULL_THRESHOLD] → 1.0；≥ [FLUX_ZERO_THRESHOLD] → 0.0；中间线性。
     * [level] 先 coerceIn(0f, 1f)；NaN 按 0 加成处理（返回 0f，调用侧 WARN，不静默）。
     */
    fun fluxDecayFactor(level: Float): Float {
        if (level.isNaN()) return 0f
        val clamped = level.coerceIn(0f, 1f)
        if (clamped <= FLUX_FULL_THRESHOLD) return 1f
        if (clamped >= FLUX_ZERO_THRESHOLD) return 0f
        return 1f - (clamped - FLUX_FULL_THRESHOLD) / (FLUX_ZERO_THRESHOLD - FLUX_FULL_THRESHOLD)
    }

    /** 最终射程加成 = [rangeBonusBase] × [fluxDecayFactor]。 */
    fun rangeBonus(tuning: DifficultyTuning, owner: Int, level: Float): Float =
        rangeBonusBase(tuning, owner) * fluxDecayFactor(level)

    /** 装药额外伤害 = 命中当发实际伤害 × rollPct/100（以面板为基准、随修正自然缩放）。 */
    fun extraDamage(baseDamage: Float, rollPct: Float): Float = baseDamage * rollPct / 100f

    /** 追加伤害触发判定：extra ≥ [EXTRA_APPLY_THRESHOLD] 才 applyDamage 与飘浮字。 */
    fun shouldApplyExtra(extra: Float): Boolean = extra >= EXTRA_APPLY_THRESHOLD

    /**
     * 随机种子派生（00 §4.1 口径，战斗内稳定）。
     * 委托共享 [CombatRandom.seedOf]——确定性序列的种子算法唯一出处，不在此处复写。
     */
    fun seedOf(shipId: String, slotId: String): Long = CombatRandom.seedOf(shipId, slotId)
}
