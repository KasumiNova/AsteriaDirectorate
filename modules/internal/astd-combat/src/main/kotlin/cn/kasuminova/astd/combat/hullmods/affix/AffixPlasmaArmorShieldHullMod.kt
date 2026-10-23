package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class AffixPlasmaArmorShieldHullMod : BaseHullMod() {

    companion object {
        val ARMOR_FRACTION = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)

        private val PREVENTED_DAMAGE_COLOR = Color(150, 220, 255, 220)

        fun armorFraction(tuning: DifficultyTuning): Float = tuning.value(ARMOR_FRACTION)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        if (!ship.hasListenerOfClass(ShieldArmorListener::class.java)) {
            ship.addListener(ShieldArmorListener(ship, armorFraction(AffixShared.tuning)))
        }
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_plasma_armor_shield.desc")
        }
    }

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
