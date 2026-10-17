package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 引力坍缩炮穿甲结算的原版装甲公式复刻与反解（纯计算，无引擎依赖）。
 *
 * 原版装甲减伤公式（与 [cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction] 口径一致）：
 * 打击强度 h = 输入伤害 × 伤害类型对装甲倍率（HE ×2），
 * 伤害倍率 mult = clamp(h / (h + armor), 1 - maxArmorDamageReduction, 1)，
 * 引擎结算后：船体伤害 = 输入伤害 × mult，装甲伤害 = 输入伤害 × 类型倍率 × mult。
 *
 * 本模块把「打击强度」替换为 单次结算伤害 × 穿甲倍率（武器档位 5/4/3/2），
 * 并反解出应传给 CombatEngineAPI.applyDamage 的输入伤害值，
 * 使引擎单次结算的装甲/船体伤害命中目标口径——避免两次 applyDamage 导致装甲减伤被结算两次。
 *
 * 精度口径（有意简化）：有效装甲取命中点**单格**装甲值（含最低装甲下限与 effectiveArmorBonus 倍率），
 * 不复制原版引擎的命中点 5×5 邻域加权装甲模型——反解结果在该加权口径下为近似值。
 */
internal object GravityCollapseArmorPiercing {

    /** 原版装甲减伤后的伤害倍率：clamp(h / (h + armor), 1 - maxReduction, 1)。 */
    fun armorThroughputMultiplier(hitStrength: Float, effectiveArmor: Float, maxReduction: Float): Float {
        val h = hitStrength.coerceAtLeast(0f)
        val a = effectiveArmor.coerceAtLeast(0f)
        if (h <= 0f) return 0f
        if (a <= 0f) return 1f
        val floor = (1f - maxReduction).coerceIn(0f, 1f)
        return (h / (h + a)).coerceIn(floor, 1f)
    }

    /**
     * 反解 applyDamage 输入伤害 X，使引擎按原版装甲公式结算后，
     * 船体/装甲伤害等同于「打击强度 = [damage] × [pierceMult] × [typeMultVsArmor]」的单次命中。
     *
     * 推导（记 m = [typeMultVsArmor]，A = [effectiveArmor]，f = 1 - maxReduction）：
     * 目标吞吐 T = D × clamp(mND / (mND + A), f, 1)（N = [pierceMult]，D = [damage]）。
     * 引擎对输入 X 的结算吞吐为 X × clamp(mX / (mX + A), f, 1)，令其等于 T：
     * - 未触顶分支（mX / (mX + A) ≥ f）：mX² / (mX + A) = T，即 mX² - T·mX - TA = 0，
     *   正根 X = (Tm + sqrt(T²m² + 4mTA)) / 2m；
     * - 触顶分支（减伤率达到上限）：X × f = T，X = T / f。
     * 吞吐函数关于 X 单调递增且两分支在边界连续，先求未触顶根并按成立条件择支，解唯一。
     *
     * 调用前置条件：[damage] > 0 且 [effectiveArmor] > 0；[effectiveArmor] ≤ 0（含最低装甲下限为 0
     * 且命中格装甲耗尽）时无减伤可反解，直接返回 [damage]。
     */
    fun solvePiercedInputDamage(
        damage: Float,
        pierceMult: Float,
        effectiveArmor: Float,
        maxReduction: Float,
        typeMultVsArmor: Float = 2f,
    ): Float {
        val d = damage.coerceAtLeast(0f)
        val a = effectiveArmor.coerceAtLeast(0f)
        val m = typeMultVsArmor.coerceAtLeast(0f)
        if (d <= 0f || a <= 0f || m <= 0f) return d

        val targetMult = armorThroughputMultiplier(d * pierceMult.coerceAtLeast(0f) * m, a, maxReduction)
        // 目标吞吐（引擎结算后应达到的船体伤害）。
        val t = (d * targetMult).toDouble()
        if (t <= 0.0) return 0f

        val md = m.toDouble()
        val ad = a.toDouble()
        val floor = (1f - maxReduction).coerceIn(0f, 1f).toDouble()

        // 未触顶分支正根。
        val xUncapped = (t * md + sqrt(t * t * md * md + 4.0 * md * ad * t)) / (2.0 * md)
        if (floor <= 0.0) return xUncapped.toFloat()

        // 成立条件：mX / (mX + A) ≥ f 时未触顶分支才是引擎实际走的分支。
        val multUncapped = md * xUncapped / (md * xUncapped + ad)
        if (multUncapped >= floor) return xUncapped.toFloat()

        // 触顶分支。
        return (t / floor).toFloat()
    }

    /** 原版口径的有效装甲：max(当前格装甲, 最低装甲) × 有效装甲倍率。 */
    fun effectiveArmor(
        cellArmor: Float,
        minArmor: Float,
        effectiveArmorMult: Float,
    ): Float = max(max(cellArmor, minArmor), 0f) * effectiveArmorMult.coerceAtLeast(0f)
}
