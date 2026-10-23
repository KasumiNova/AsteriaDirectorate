package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import kotlin.math.max
import kotlin.math.sqrt

// 原版装甲减伤公式：mult = clamp(h / (h + armor), 1 - maxReduction, 1)，h = 输入伤害 × 类型对装甲倍率（HE ×2）。
// 反解 applyDamage 的输入伤害，使引擎单次结算命中目标穿甲判定，避免两次 applyDamage 把装甲减伤结算两次。
internal object GravityCollapseArmorPiercing {

    fun armorThroughputMultiplier(hitStrength: Float, effectiveArmor: Float, maxReduction: Float): Float {
        val h = hitStrength.coerceAtLeast(0f)
        val a = effectiveArmor.coerceAtLeast(0f)
        if (h <= 0f) return 0f
        if (a <= 0f) return 1f
        val floor = (1f - maxReduction).coerceIn(0f, 1f)
        return (h / (h + a)).coerceIn(floor, 1f)
    }

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
        val t = (d * targetMult).toDouble()
        if (t <= 0.0) return 0f

        val md = m.toDouble()
        val ad = a.toDouble()
        val floor = (1f - maxReduction).coerceIn(0f, 1f).toDouble()

        val xUncapped = (t * md + sqrt(t * t * md * md + 4.0 * md * ad * t)) / (2.0 * md)
        if (floor <= 0.0) return xUncapped.toFloat()

        val multUncapped = md * xUncapped / (md * xUncapped + ad)
        if (multUncapped >= floor) return xUncapped.toFloat()

        return (t / floor).toFloat()
    }

    fun effectiveArmor(
        cellArmor: Float,
        minArmor: Float,
        effectiveArmorMult: Float,
    ): Float = max(max(cellArmor, minArmor), 0f) * effectiveArmorMult.coerceAtLeast(0f)
}
