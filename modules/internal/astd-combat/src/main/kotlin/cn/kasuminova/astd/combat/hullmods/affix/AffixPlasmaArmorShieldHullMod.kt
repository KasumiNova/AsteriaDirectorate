package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 词缀 M-14：等离子装甲护盾（[AffixRegistry.ID_PLASMA_ARMOR_SHIELD]）。
 * 按难度系数：舰船护盾获得 20%~40% 的装甲计算值减免——护盾命中按装甲减伤公式折算，
 * 折算所用装甲值 = 舰船最终最大装甲 × 减免比例。机制复用同名船插
 * [cn.kasuminova.astd.combat.hullmods.arc.ASTDPlasmaArmorShieldHullMod] 的装甲减免链路
 * （[ASTDArmorDamageReduction]）。
 */
class AffixPlasmaArmorShieldHullMod : BaseHullMod() {

    companion object {
        /** 护盾折算装甲值占最终最大装甲的比例：20%~40%。 */
        val ARMOR_FRACTION = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)

        private val PREVENTED_DAMAGE_COLOR = Color(150, 220, 255, 220)

        fun armorFraction(tuning: DifficultyTuning): Float = tuning.value(ARMOR_FRACTION)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        if (!ship.hasListenerOfClass(ShieldArmorListener::class.java)) {
            ship.addListener(ShieldArmorListener(ship, armorFraction(AffixShared.tuning)))
        }
    }

    /**
     * 护盾装甲减免监听器：护盾命中时按装甲公式折算减免。
     *
     * @property armorFraction 折算装甲值占最终最大装甲的比例（安装时按难度系数定值）
     */
    class ShieldArmorListener(
        private val ship: ShipAPI,
        private val armorFraction: Float,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== ship || damage == null || !shieldHit || point == null) return null
            if (!ship.isAlive || ship.isHulk) return null

            val maxArmor = ship.mutableStats.armorBonus.computeEffective(ship.armorGrid.armorRating).coerceAtLeast(1f)
            val armorValue = maxArmor * armorFraction
            val incoming = effectiveDamageAmount(damage)
            if (incoming <= 0f) return null

            val reduction = ASTDArmorDamageReduction.compute(
                damageAmount = incoming,
                hitStrength = ASTDArmorDamageReduction.hitStrength(damage.type, damage.baseDamage, isBeamDamage(param, damage)),
                armorValue = armorValue,
                minArmorValue = armorValue,
                effectiveArmorMult = ship.mutableStats.effectiveArmorBonus.mult,
                maxArmorDamageReduction = ship.mutableStats.maxArmorDamageReduction.modifiedValue,
            )
            if (reduction.damageMultiplier < 0.999f) {
                damage.modifier.modifyMult(STAT_ID, reduction.damageMultiplier)
                Global.getCombatEngine()?.addFloatingDamageText(
                    point, reduction.preventedDamage, PREVENTED_DAMAGE_COLOR, ship, null,
                )
            }
            return null
        }

        private fun effectiveDamageAmount(damage: DamageAPI): Float {
            val duration = if (damage.isDps) damage.dpsDuration.coerceAtLeast(0f) else 1f
            return damage.damage.coerceAtLeast(0f) * duration
        }

        private fun isBeamDamage(param: Any?, damage: DamageAPI): Boolean =
            param is BeamAPI || damage.isDps

        companion object {
            const val STAT_ID: String = "astd_affix_plasma_armor_shield"
        }
    }
}
