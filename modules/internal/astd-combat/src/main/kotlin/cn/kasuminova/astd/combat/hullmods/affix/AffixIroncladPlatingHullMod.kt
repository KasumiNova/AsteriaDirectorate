package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

class AffixIroncladPlatingHullMod : BaseHullMod() {

    data class Bonuses(
        val armorMult: Float,
        val minArmorValue: Float,
    )

    companion object {
        private const val BASE_MIN_ARMOR_FRACTION = 0.05f

        val ARMOR_MULT = ScalingEntry(v1 = 1.15f, v2 = 1.30f, v5 = 1.45f)

        val MIN_ARMOR_FRIGATE = ScalingEntry(v1 = 50f, v2 = 75f, v5 = 100f)
        val MIN_ARMOR_DESTROYER = ScalingEntry(v1 = 100f, v2 = 150f, v5 = 200f)
        val MIN_ARMOR_CRUISER = ScalingEntry(v1 = 200f, v2 = 300f, v5 = 400f)
        val MIN_ARMOR_CAPITAL = ScalingEntry(v1 = 300f, v2 = 450f, v5 = 600f)

        const val MANEUVER_MULT = 0.85f

        fun minArmorEntry(hullSize: ShipAPI.HullSize?): ScalingEntry = AffixShared.bySize(
            hullSize, MIN_ARMOR_FRIGATE, MIN_ARMOR_DESTROYER, MIN_ARMOR_CRUISER, MIN_ARMOR_CAPITAL,
        )

        fun bonuses(tuning: DifficultyTuning, hullSize: ShipAPI.HullSize?): Bonuses = Bonuses(
            armorMult = tuning.value(ARMOR_MULT),
            minArmorValue = tuning.value(minArmorEntry(hullSize)),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning, hullSize)

        stats.armorBonus.modifyMult(id, b.armorMult)

        val baseArmor = stats.variant?.hullSpec?.armorRating ?: 0f
        if (baseArmor > 0f) {
            val targetFraction = b.minArmorValue / baseArmor
            val delta = (targetFraction - BASE_MIN_ARMOR_FRACTION).coerceAtLeast(0f)
            if (delta > 0f) {
                stats.minArmorFraction.modifyFlat(id, delta)
            }
        }

        stats.maxSpeed.modifyMult(id, MANEUVER_MULT)
        stats.acceleration.modifyMult(id, MANEUVER_MULT)
        stats.deceleration.modifyMult(id, MANEUVER_MULT)
        stats.turnAcceleration.modifyMult(id, MANEUVER_MULT)
        stats.maxTurnRate.modifyMult(id, MANEUVER_MULT)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_ironclad_plating.desc")
        }
    }
}
