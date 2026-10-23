package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.ui.TooltipMakerAPI

class AffixSwarmCoordinationHullMod : BaseHullMod() {

    data class Bonuses(
        val damageTakenMult: Float,
        val rofMult: Float,
    )

    companion object {
        val PER_ALLY = ScalingEntry(v1 = 0.01f, v2 = 0.015f, v5 = 0.02f)
        val CAP = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        private const val MOD_ID = "astd_affix_swarm_coordination"

        fun isAutomated(ship: ShipAPI): Boolean {
            if (ship.variant?.hasHullMod(HullMods.AUTOMATED) == true) return true
            return ship.fleetMember?.captain?.isAICore == true
        }

        fun bonuses(tuning: DifficultyTuning, automatedAllyCount: Int): Bonuses {
            val bonus = minOf(
                automatedAllyCount.coerceAtLeast(0) * tuning.value(PER_ALLY),
                tuning.value(CAP),
            )
            return Bonuses(
                damageTakenMult = 1f - bonus,
                rofMult = 1f + bonus,
            )
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val automated = AffixShared.aliveAllies(ship).count { isAutomated(it) }
        val b = bonuses(AffixShared.tuning, automated)
        val stats = ship.mutableStats
        stats.hullDamageTakenMult.modifyMult(MOD_ID, b.damageTakenMult)
        stats.armorDamageTakenMult.modifyMult(MOD_ID, b.damageTakenMult)
        stats.shieldDamageTakenMult.modifyMult(MOD_ID, b.damageTakenMult)
        stats.empDamageTakenMult.modifyMult(MOD_ID, b.damageTakenMult)
        stats.ballisticRoFMult.modifyMult(MOD_ID, b.rofMult)
        stats.energyRoFMult.modifyMult(MOD_ID, b.rofMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_swarm_coordination.desc")
        }
    }
}
