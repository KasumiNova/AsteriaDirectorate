package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lwjgl.util.vector.Vector2f

class AffixReactiveFluxArmorHullMod : BaseHullMod() {

    companion object {
        val REDUCTION = ScalingEntry(v1 = 0.75f, v2 = 0.825f, v5 = 0.90f)

        const val VENT_RATE_MULT = 0.65f

        fun damageTakenMult(tuning: DifficultyTuning): Float = 1f - tuning.value(REDUCTION)
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.ventRateMult.modifyMult(id, VENT_RATE_MULT)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        if (!ship.hasListenerOfClass(VentGuardListener::class.java)) {
            ship.addListener(VentGuardListener(ship, damageTakenMult(AffixShared.tuning)))
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
            para("ui.hullmod.affix_reactive_flux_armor.desc")
        }
    }

    class VentGuardListener(
        private val ship: ShipAPI,
        private val damageTakenMult: Float,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== ship || damage == null || shieldHit) return null
            if (!ship.fluxTracker.isVenting) return null
            damage.modifier.modifyMult(STAT_ID, damageTakenMult)
            return null
        }

        companion object {
            const val STAT_ID: String = "astd_affix_reactive_flux_armor"
        }
    }
}
