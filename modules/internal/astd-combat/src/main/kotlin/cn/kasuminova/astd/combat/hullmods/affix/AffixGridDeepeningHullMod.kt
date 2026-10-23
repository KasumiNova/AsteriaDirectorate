package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lazywizard.lazylib.MathUtils

class AffixGridDeepeningHullMod : BaseHullMod() {

    companion object {
        val VENT_RATE = ScalingEntry(v1 = 1.25f, v2 = 1.375f, v5 = 1.50f)

        val HARD_FLUX_DISSIPATION = ScalingEntry(v1 = 0.10f, v2 = 0.15f, v5 = 0.20f)

        private const val ALLY_SCAN_INTERVAL = 0.5f

        private const val SCAN_TIMER_KEY = "astd_affix_grid_deepening_scan"
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val tuning = AffixShared.tuning
        val cryo = AffixCryoFluxNetworkHullMod.bonuses(tuning)
        stats.fluxDissipation.modifyMult(id, cryo.dissipationMult)
        stats.ventRateMult.modifyMult(id, cryo.ventRateMult)
        stats.empDamageTakenMult.modifyMult(id, cryo.empDamageTakenMult)
        stats.ventRateMult.modifyMult(id, tuning.value(VENT_RATE))
        stats.hardFluxDissipationFraction.modifyFlat(id, tuning.value(HARD_FLUX_DISSIPATION))
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        if (!ship.hasListenerOfClass(AffixReactiveFluxArmorHullMod.VentGuardListener::class.java)) {
            ship.addListener(
                AffixReactiveFluxArmorHullMod.VentGuardListener(
                    ship,
                    AffixReactiveFluxArmorHullMod.damageTakenMult(AffixShared.tuning),
                ),
            )
        }
        ensureVentAssist(ship)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || !ship.isAlive || ship.isHulk) return

        var elapsed = (ship.customData[SCAN_TIMER_KEY] as? Float ?: 0f) + amount
        if (elapsed < ALLY_SCAN_INTERVAL) {
            ship.setCustomData(SCAN_TIMER_KEY, elapsed)
            return
        }
        elapsed = 0f
        ship.setCustomData(SCAN_TIMER_KEY, elapsed)

        for (ally in AffixShared.aliveAllies(ship)) {
            ensureVentAssist(ally)
        }
    }

    private fun ensureVentAssist(ship: ShipAPI) {
        if (!ship.hasListenerOfClass(VentAssistListener::class.java)) {
            ship.addListener(VentAssistListener(ship))
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
            para("ui.hullmod.affix_grid_deepening.desc")
        }
    }

    class VentAssistListener(
        private val ship: ShipAPI,
    ) : AdvanceableListener {

        private var sinceLastAttempt = ATTEMPT_INTERVAL

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
                return
            }
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused) return
            sinceLastAttempt += amount
            if (sinceLastAttempt < ATTEMPT_INTERVAL) return

            val tracker = ship.fluxTracker
            if (tracker.isOverloadedOrVenting || tracker.currFlux <= 0f) return
            val maxFlux = tracker.maxFlux.coerceAtLeast(1f)
            val fluxLevel = tracker.currFlux / maxFlux
            val hardFluxLevel = tracker.hardFlux / maxFlux

            val nearestEnemyDist = nearestEnemyDistance(engine)
            val safeToVent = fluxLevel >= SAFE_VENT_FLUX_LEVEL && nearestEnemyDist > SAFE_VENT_ENEMY_DIST
            val dumpHardFlux = hardFluxLevel >= HARD_FLUX_DUMP_LEVEL && nearestEnemyDist > HARD_FLUX_DUMP_ENEMY_DIST
            if (safeToVent || dumpHardFlux) {
                ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
                sinceLastAttempt = 0f
            }
        }

        private fun nearestEnemyDistance(engine: com.fs.starfarer.api.combat.CombatEngineAPI): Float {
            var nearest = Float.MAX_VALUE
            for (other in engine.ships) {
                if (other.owner == ship.owner || !other.isAlive || other.isHulk) continue
                if (other.isFighter || other.isDrone) continue
                if (other.isPhased) continue
                nearest = minOf(nearest, MathUtils.getDistance(ship, other))
            }
            return nearest
        }

        companion object {
            const val ATTEMPT_INTERVAL = 1.0f

            const val SAFE_VENT_FLUX_LEVEL = 0.55f
            const val SAFE_VENT_ENEMY_DIST = 1200f

            const val HARD_FLUX_DUMP_LEVEL = 0.35f
            const val HARD_FLUX_DUMP_ENEMY_DIST = 600f
        }
    }
}
