package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

class AffixRecursiveTargetingHullMod : BaseHullMod() {

    data class Bonuses(
        val rangeMult: Float,
        val projectileSpeedMult: Float,
    )

    companion object {
        private val PER_ALLY_FRIGATE = ScalingEntry(v1 = 0.01f, v2 = 0.015f, v5 = 0.02f)
        private val PER_ALLY_DESTROYER = ScalingEntry(v1 = 0.02f, v2 = 0.03f, v5 = 0.04f)
        private val PER_ALLY_CRUISER = ScalingEntry(v1 = 0.03f, v2 = 0.045f, v5 = 0.06f)
        private val PER_ALLY_CAPITAL = ScalingEntry(v1 = 0.04f, v2 = 0.06f, v5 = 0.08f)

        private val RANGE_CAP_FRIGATE = ScalingEntry(v1 = 0.15f, v2 = 0.225f, v5 = 0.30f)
        private val RANGE_CAP_DESTROYER = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)
        private val RANGE_CAP_CRUISER = ScalingEntry(v1 = 0.30f, v2 = 0.45f, v5 = 0.60f)
        private val RANGE_CAP_CAPITAL = ScalingEntry(v1 = 0.40f, v2 = 0.60f, v5 = 0.80f)

        private val SPEED_CAP = ScalingEntry(v1 = 0.40f, v2 = 0.60f, v5 = 0.80f)

        private const val MOD_ID = "astd_affix_recursive_targeting"

        fun bonuses(tuning: DifficultyTuning, hullSize: ShipAPI.HullSize?, allyCount: Int): Bonuses {
            val perAlly = tuning.value(
                AffixShared.bySize(hullSize, PER_ALLY_FRIGATE, PER_ALLY_DESTROYER, PER_ALLY_CRUISER, PER_ALLY_CAPITAL),
            )
            val rangeCap = tuning.value(
                AffixShared.bySize(hullSize, RANGE_CAP_FRIGATE, RANGE_CAP_DESTROYER, RANGE_CAP_CRUISER, RANGE_CAP_CAPITAL),
            )
            val speedCap = tuning.value(SPEED_CAP)
            val total = allyCount.coerceAtLeast(0) * perAlly
            return Bonuses(
                rangeMult = 1f + minOf(total, rangeCap),
                projectileSpeedMult = 1f + minOf(total, speedCap),
            )
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val b = bonuses(AffixShared.tuning, ship.hullSize, AffixShared.aliveAllies(ship).size)
        val stats = ship.mutableStats
        stats.ballisticWeaponRangeBonus.modifyMult(MOD_ID, b.rangeMult)
        stats.energyWeaponRangeBonus.modifyMult(MOD_ID, b.rangeMult)
        stats.ballisticProjectileSpeedMult.modifyMult(MOD_ID, b.projectileSpeedMult)
        stats.energyProjectileSpeedMult.modifyMult(MOD_ID, b.projectileSpeedMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_recursive_targeting.desc")
        }
    }
}
