package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import cn.kasuminova.astd.combat.hullmods.base.directionalArmorFraction
import cn.kasuminova.astd.combat.skills.ASTDCombatIntelTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

class DimShuntSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.dim_shunt.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val frigate = SicShared.formatNumber(DifficultyTuningImpl.valueFor(SicTuning.DIM_ARMOR_FRIGATE, true))
        val destroyer = SicShared.formatNumber(DifficultyTuningImpl.valueFor(SicTuning.DIM_ARMOR_DESTROYER, true))
        val cruiser = SicShared.formatNumber(DifficultyTuningImpl.valueFor(SicTuning.DIM_ARMOR_CRUISER, true))
        val capital = SicShared.formatNumber(DifficultyTuningImpl.valueFor(SicTuning.DIM_ARMOR_CAPITAL, true))
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            para(
                "ui.sic.dim_shunt.line.armor",
                Misc.getHighlightColor(),
                8f,
                v("frigate", frigate), v("destroyer", destroyer), v("cruiser", cruiser), v("capital", capital),
            )
            custom {
                SicShared.subLine(
                    it,
                    I18n.t(
                        I18n.Categories.MOD, "ui.sic.dim_shunt.sub.armor",
                        "cap" to SicShared.formatPercent(SicTuning.DIM_ARMOR_CAP_FRACTION),
                    ),
                    2f,
                    SicShared.formatPercent(SicTuning.DIM_ARMOR_CAP_FRACTION),
                )
            }
            para(
                "ui.sic.dim_shunt.line.absorb",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.DIM_ENERGY_ABSORB, true))),
            )
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.dim_shunt.flavor"], 10f) }
        }
    }

    override fun applyEffectsAfterShipCreation(data: SCData, ship: ShipAPI, variant: ShipVariantAPI, id: String) {
        if (!GravTuningSkill.isPhaseVariant(variant)) return
        if (!ship.hasListenerOfClass(DimShuntListener::class.java)) {
            ship.addListener(DimShuntListener(ship, data.isPlayer))
        }
    }

    private class DimShuntListener(
        private val ship: ShipAPI,
        private val isPlayer: Boolean,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (ship.isHulk || !ship.isAlive) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(
            param: Any?,
            target: CombatEntityAPI?,
            damage: DamageAPI?,
            point: Vector2f?,
            shieldHit: Boolean,
        ): String? {
            val dmg = damage ?: return null
            if (target !== ship || shieldHit || ship.isHulk || !ship.isAlive) return null

            applyArmorBonus(param, dmg, point)
            applyEnergyAbsorb(dmg)
            return null
        }

        private fun applyArmorBonus(param: Any?, dmg: DamageAPI, point: Vector2f?) {
            val hitPoint = point ?: return
            val base = DifficultyTuningImpl.valueFor(SicTuning.dimArmorEntry(ship.hullSize), isPlayer)
            val ventingOrOverloaded = ship.fluxTracker.isVenting || ship.fluxTracker.isOverloaded
            val bonus = SicTuning.dimArmorBonus(base, ventingOrOverloaded, ship.armorGrid.armorRating)
            if (bonus <= 0f) return

            val finalMaxArmor = ship.mutableStats.armorBonus.computeEffective(ship.armorGrid.armorRating).coerceAtLeast(1f)
            val armor = finalMaxArmor * directionalArmorFraction(ship, hitPoint)
            val hitStrength = ASTDArmorDamageReduction.hitStrength(dmg.type, dmg.baseDamage, param is BeamAPI || dmg.isDps)
            val ratio = ASTDCombatIntelTuning.bonusArmorDamageRatio(
                hitStrength = hitStrength,
                armor = armor,
                minArmor = ship.armorGrid.armorRating * ship.mutableStats.minArmorFraction.modifiedValue,
                effectiveArmorMult = ship.mutableStats.effectiveArmorBonus.mult,
                maxArmorDamageReduction = ship.mutableStats.maxArmorDamageReduction.modifiedValue,
                bonusArmor = bonus,
            )
            if (ratio < 0.999f) {
                dmg.modifier.modifyMult(ARMOR_STAT_ID, ratio)
            }
        }

        private fun applyEnergyAbsorb(dmg: DamageAPI) {
            if (dmg.type != DamageType.ENERGY) return
            val absorbFraction = DifficultyTuningImpl.valueFor(SicTuning.DIM_ENERGY_ABSORB, isPlayer)
            val absorbed = SicTuning.dimEnergyAbsorbed(dmg.damage, absorbFraction)
            if (absorbed <= 0f) return

            dmg.modifier.modifyMult(ABSORB_STAT_ID, 1f - absorbFraction)
            // FluxTrackerAPI 无 decreaseHardFlux：setHardFlux + setCurrFlux 组合实现等额硬辐能耗散
            val tracker = ship.fluxTracker
            tracker.setHardFlux((tracker.hardFlux - absorbed).coerceAtLeast(0f))
            tracker.setCurrFlux((tracker.currFlux - absorbed).coerceAtLeast(tracker.hardFlux))
        }
    }

    companion object {
        private const val ARMOR_STAT_ID = "astd_sc_dim_shunt_armor"

        private const val ABSORB_STAT_ID = "astd_sc_dim_shunt_absorb"
    }
}
