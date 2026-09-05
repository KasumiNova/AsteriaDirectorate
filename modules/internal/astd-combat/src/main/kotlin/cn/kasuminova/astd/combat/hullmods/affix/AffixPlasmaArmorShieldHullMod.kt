package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import org.lwjgl.util.vector.Vector2f

/**
 * M-14 等离子装甲护盾（affixes.md v3.0）：
 * - 按难度系数，舰船护盾获得 20%~40% 的装甲计算值减免；
 * - 机制复用同名船插 [cn.kasuminova.astd.combat.hullmods.arc.ASTDPlasmaArmorShieldHullMod]
 *   的护盾承压口径：命中护盾时按 `有效最大装甲 × 分档系数` 作为装甲值走
 *   [ASTDArmorDamageReduction] 装甲公式折算伤害乘区（本词缀为单一方向系数，不做方位分档）。
 */
class AffixPlasmaArmorShieldHullMod : BaseHullMod() {

    companion object {
        /** 护盾获得的装甲计算值系数（有效最大装甲的比例）。 */
        val SHIELD_ARMOR_FRACTION = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)

        const val DAMAGE_STAT_ID = "astd_affix_plasma_armor_shield"

        /** 护盾命中的装甲减免乘区计算（纯逻辑，测试直调）。 */
        fun shieldHitDamageMult(
            damageAmount: Float,
            hitStrength: Float,
            effectiveMaxArmor: Float,
            effectiveArmorMult: Float,
            maxArmorDamageReduction: Float,
            tuning: DifficultyTuning,
        ): Float {
            val armorValue = effectiveMaxArmor.coerceAtLeast(0f) * tuning.value(SHIELD_ARMOR_FRACTION)
            return ASTDArmorDamageReduction.compute(
                damageAmount = damageAmount,
                hitStrength = hitStrength,
                armorValue = armorValue,
                minArmorValue = armorValue,
                effectiveArmorMult = effectiveArmorMult,
                maxArmorDamageReduction = maxArmorDamageReduction,
            ).damageMultiplier
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (!ship.isAlive || ship.isHulk) return
        if (!ship.hasListenerOfClass(AffixPlasmaArmorShieldListener::class.java)) {
            ship.addListener(AffixPlasmaArmorShieldListener(ship))
        }
    }

    private class AffixPlasmaArmorShieldListener(
        private val ship: ShipAPI,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            val dmg = damage ?: return null
            if (target !== ship || !shieldHit || !ship.isAlive || ship.isHulk) return null

            val effectiveMaxArmor = ship.mutableStats.armorBonus
                .computeEffective(ship.armorGrid.armorRating).coerceAtLeast(1f)
            val isBeam = param is BeamAPI || dmg.isDps
            val duration = if (dmg.isDps) dmg.dpsDuration.coerceAtLeast(0f) else 1f
            val mult = shieldHitDamageMult(
                damageAmount = dmg.damage.coerceAtLeast(0f) * duration,
                hitStrength = ASTDArmorDamageReduction.hitStrength(dmg.type, dmg.baseDamage, isBeam),
                effectiveMaxArmor = effectiveMaxArmor,
                effectiveArmorMult = ship.mutableStats.effectiveArmorBonus.mult,
                maxArmorDamageReduction = ship.mutableStats.maxArmorDamageReduction.modifiedValue,
                tuning = DifficultyTuningImpl,
            )
            if (mult < 0.999f) {
                dmg.modifier.modifyMult(DAMAGE_STAT_ID, mult)
            }
            return null
        }
    }
}
