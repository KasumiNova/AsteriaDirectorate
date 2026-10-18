package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.DamageType

/**
 * 原版装甲减伤公式的唯一实现（hitStrength 换算 + 伤害系数结算）。
 * public 可见性：astd-campaign 的 SIC 技能「维度分流」按 task 口径直引复用
 * （[hitStrength] 与 [compute]），不在 campaign 侧复写装甲公式。
 */
object ASTDArmorDamageReduction {

    fun compute(
        damageAmount: Float,
        hitStrength: Float,
        armorValue: Float,
        minArmorValue: Float,
        effectiveArmorMult: Float,
        maxArmorDamageReduction: Float,
    ): Result {
        val baseDamage = damageAmount.coerceAtLeast(0f)
        if (baseDamage <= 0f) return Result(damageMultiplier = 1f, damageAfterArmor = 0f, preventedDamage = 0f)

        val effectiveHitStrength = hitStrength.coerceAtLeast(0f)
        val effectiveArmor = maxOf(armorValue, minArmorValue, 0f) * effectiveArmorMult.coerceAtLeast(0f)
        val maxReductionFloor = (1f - maxArmorDamageReduction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val armorFormulaMult = if (effectiveHitStrength > 0f && effectiveArmor > 0f) {
            effectiveHitStrength / (effectiveHitStrength + effectiveArmor)
        } else {
            1f
        }
        val damageMultiplier = armorFormulaMult.coerceIn(maxReductionFloor, 1f)
        val damageAfterArmor = baseDamage * damageMultiplier
        return Result(
            damageMultiplier = damageMultiplier,
            damageAfterArmor = damageAfterArmor,
            preventedDamage = baseDamage - damageAfterArmor,
        )
    }

    fun hitStrength(damageType: DamageType, baseDamage: Float, isBeam: Boolean): Float {
        val damage = baseDamage.coerceAtLeast(0f) * damageType.armorMult()
        return if (isBeam) damage * 0.5f else damage
    }

    data class Result(
        val damageMultiplier: Float,
        val damageAfterArmor: Float,
        val preventedDamage: Float,
    )

    private fun DamageType.armorMult(): Float = when (this) {
        DamageType.KINETIC -> 0.5f
        DamageType.HIGH_EXPLOSIVE -> 2f
        DamageType.FRAGMENTATION -> 0.25f
        DamageType.ENERGY,
        DamageType.OTHER,
            -> 1f
    }
}
