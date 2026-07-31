package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAuraUtil
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import kotlin.math.roundToInt

class ASTDArcSharedFluxNetworkSystemStats : BaseShipSystemScript() {

    companion object {
        private const val SOURCE_ROF_MULT = 0.50f
        private const val SOURCE_MANEUVER_MULT = 0.50f
        private const val SOURCE_DISSIPATION_BONUS = 0.30f
        private const val SOURCE_HARD_DISSIPATION_FRACTION = 0.15f
        private const val MAX_SOURCE_FLUX_LEVEL = 0.90f

        private const val TARGET_DISSIPATION_BONUS = 0.15f
        private const val TARGET_ROF_BONUS = 0.30f
        private const val TARGET_WEAPON_FLUX_REDUCTION = 0.20f
        private const val TRANSFER_SOFT_FRACTION = 0.30f
        private const val TRANSFER_HARD_FRACTION = 0.15f
        private const val MAX_TARGETS = 12
        private const val MAX_PRESSURE_FLUX_FRACTION_PER_SECOND = 0.015f
        private const val PRESSURE_RESPONSE = 0.24f
        private const val PRESSURE_DECAY = 0.88f

        private const val TARGETS_KEY = "astd_arc_shared_flux_network_targets:"
        private const val TRACK_KEY = "astd_arc_shared_flux_network_track:"
        private const val PRESSURE_KEY = "astd_arc_shared_flux_network_pressure:"

        private val ELIGIBLE_HULL_SIZES = setOf(
            ShipAPI.HullSize.FRIGATE,
            ShipAPI.HullSize.DESTROYER,
            ShipAPI.HullSize.CRUISER,
            ShipAPI.HullSize.CAPITAL_SHIP,
        )
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk || ship.hitpoints <= 0f) return

        val level = effectLevel.coerceIn(0f, 1f)
        if ((ship.fluxTracker?.fluxLevel ?: 0f) > MAX_SOURCE_FLUX_LEVEL) {
            try { ship.system?.deactivate() } catch (_: Throwable) {}
        }

        applySourceStats(stats, id, level)
        if (level <= 0.001f) return

        val amount = safeElapsed(engine)
        val targets = selectTargets(engine, ship)
        val activeSet = targets.mapTo(LinkedHashSet()) { System.identityHashCode(it) }
        clearStaleTargets(engine, ship, activeSet)
        var maxPressureRatio = 0f
        for (target in targets) {
            val distance = ASTDArcAuraUtil.distance(ship.location, target.location)
            val falloff = arcJetSystemFalloff(ship, distance)
            if (falloff <= 0f) continue
            applyTargetStats(ship, target, falloff * level)
            val transfer = transferFluxDelta(engine, ship, target, falloff * level, amount)
            val pressureRatio = updateFluxPressure(engine, ship, target, transfer, amount)
            maxPressureRatio = maxOf(maxPressureRatio, pressureRatio)
            maintainTargetStatus(engine, ship, target, falloff * level)
        }
        ASTDArcProductionVfx.renderArcJetShockwaveRing(engine, ship, level, maxPressureRatio)
        engine.customData["$TARGETS_KEY${System.identityHashCode(ship)}"] = activeSet
        engine.customData[ASTDArcProductionShipIds.DATA_ARC_SHARED_FLUX_TARGETS] = activeSet
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.ballisticRoFMult.unmodify(id)
        stats.energyRoFMult.unmodify(id)
        stats.maxSpeed.unmodify(id)
        stats.acceleration.unmodify(id)
        stats.deceleration.unmodify(id)
        stats.maxTurnRate.unmodify(id)
        stats.turnAcceleration.unmodify(id)
        stats.fluxDissipation.unmodify(id)
        stats.hardFluxDissipationFraction.unmodify(id)

        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        clearStaleTargets(engine, ship, emptySet())
        engine.customData.remove("$TARGETS_KEY${System.identityHashCode(ship)}")
        engine.customData.remove(ASTDArcProductionShipIds.DATA_ARC_SHARED_FLUX_TARGETS)
    }

    private fun applySourceStats(stats: MutableShipStatsAPI, id: String, level: Float) {
        stats.ballisticRoFMult.modifyMult(id, 1f + (SOURCE_ROF_MULT - 1f) * level)
        stats.energyRoFMult.modifyMult(id, 1f + (SOURCE_ROF_MULT - 1f) * level)
        stats.maxSpeed.modifyMult(id, 1f + (SOURCE_MANEUVER_MULT - 1f) * level)
        stats.acceleration.modifyMult(id, 1f + (SOURCE_MANEUVER_MULT - 1f) * level)
        stats.deceleration.modifyMult(id, 1f + (SOURCE_MANEUVER_MULT - 1f) * level)
        stats.maxTurnRate.modifyMult(id, 1f + (SOURCE_MANEUVER_MULT - 1f) * level)
        stats.turnAcceleration.modifyMult(id, 1f + (SOURCE_MANEUVER_MULT - 1f) * level)
        stats.fluxDissipation.modifyMult(id, 1f + SOURCE_DISSIPATION_BONUS * level)
        stats.hardFluxDissipationFraction.modifyFlat(id, SOURCE_HARD_DISSIPATION_FRACTION * level)
    }

    private fun selectTargets(engine: CombatEngineAPI, source: ShipAPI): List<ShipAPI> {
        val candidates = try { engine.ships } catch (_: Throwable) { null } ?: return emptyList()
        val bySummaryId = LinkedHashMap<String, ShipAPI>()
        for (candidate in candidates) {
            if (candidate === source) continue
            val summary = ASTDArcAuraUtil.summaryFor(candidate)
            bySummaryId[summary.id] = candidate
        }
        val selected = ASTDArcAuraUtil.selectTargets(
            sourceOwner = source.owner,
            sourceLocation = source.location,
            maxRange = ASTDArcCombatUtil.effectiveSystemRange(source, ASTDArcAuraUtil.ARC_JET_SYSTEM_MAX_RANGE),
            maxCount = MAX_TARGETS,
            eligibleHullSizes = ELIGIBLE_HULL_SIZES,
            candidates = bySummaryId.values.map { ASTDArcAuraUtil.summaryFor(it).withPressureBias(it) },
        )
        return selected.mapNotNull { bySummaryId[it.id] }
    }

    private fun ASTDArcAuraUtil.CandidateSummary.withPressureBias(ship: ShipAPI): ASTDArcAuraUtil.CandidateSummary {
        val flux = try { ship.fluxTracker?.fluxLevel ?: 0f } catch (_: Throwable) { 0f }
        val sizeBias = when (ship.hullSize) {
            ShipAPI.HullSize.CAPITAL_SHIP -> 0.24f
            ShipAPI.HullSize.CRUISER -> 0.16f
            ShipAPI.HullSize.DESTROYER -> 0.08f
            else -> 0f
        }
        return copy(scoreBias = (sizeBias + flux * 0.24f).coerceIn(0f, 0.48f))
    }

    private fun arcJetSystemFalloff(ship: ShipAPI, distance: Float): Float {
        val fullRange = ASTDArcCombatUtil.effectiveSystemRange(ship, ASTDArcAuraUtil.ARC_JET_SYSTEM_FULL_RANGE)
        val maxRange = ASTDArcCombatUtil.effectiveSystemRange(ship, ASTDArcAuraUtil.ARC_JET_SYSTEM_MAX_RANGE)
        return ASTDArcAuraUtil.distanceFalloff(distance, fullRange, maxRange, ASTDArcAuraUtil.EDGE_SCALE)
    }

    private fun applyTargetStats(source: ShipAPI, target: ShipAPI, level: Float) {
        val id = targetModId(source, target)
        val stats = target.mutableStats
        stats.fluxDissipation.modifyMult(id, 1f + TARGET_DISSIPATION_BONUS * level)
        stats.ballisticRoFMult.modifyMult(id, 1f + TARGET_ROF_BONUS * level)
        stats.energyRoFMult.modifyMult(id, 1f + TARGET_ROF_BONUS * level)
        val fluxMult = 1f - TARGET_WEAPON_FLUX_REDUCTION * level
        stats.ballisticWeaponFluxCostMod.modifyMult(id, fluxMult)
        stats.energyWeaponFluxCostMod.modifyMult(id, fluxMult)
        stats.beamWeaponFluxCostMult.modifyMult(id, fluxMult)
    }

    private fun clearStaleTargets(engine: CombatEngineAPI, source: ShipAPI, activeSet: Set<Int>) {
        val key = "$TARGETS_KEY${System.identityHashCode(source)}"
        val previous = engine.customData[key] as? Set<*> ?: emptySet<Any>()
        val ships = try { engine.ships } catch (_: Throwable) { null } ?: return
        for (entry in previous) {
            val identity = entry as? Int ?: continue
            if (identity in activeSet) continue
            val target = ships.firstOrNull { System.identityHashCode(it) == identity } ?: continue
            clearTargetStats(source, target)
            engine.customData.remove(trackKey(source, target))
        }
    }

    private fun clearTargetStats(source: ShipAPI, target: ShipAPI) {
        val id = targetModId(source, target)
        val stats = target.mutableStats
        stats.fluxDissipation.unmodify(id)
        stats.ballisticRoFMult.unmodify(id)
        stats.energyRoFMult.unmodify(id)
        stats.ballisticWeaponFluxCostMod.unmodify(id)
        stats.energyWeaponFluxCostMod.unmodify(id)
        stats.beamWeaponFluxCostMult.unmodify(id)
    }

    private fun transferFluxDelta(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI, level: Float, amount: Float): FluxTransfer {
        val targetTracker = target.fluxTracker ?: return FluxTransfer.None
        val sourceTracker = source.fluxTracker ?: return FluxTransfer.None
        val key = trackKey(source, target)
        val current = FluxSnapshot(
            curr = targetTracker.currFlux,
            hard = targetTracker.hardFlux,
        )
        val previous = engine.customData[key] as? FluxSnapshot
        engine.customData[key] = current
        if (previous == null) return FluxTransfer.None

        val currDelta = (current.curr - previous.curr).coerceAtLeast(0f)
        val hardDelta = (current.hard - previous.hard).coerceAtLeast(0f)
        if (currDelta <= 0f && hardDelta <= 0f) return FluxTransfer.None

        val softDelta = (currDelta - hardDelta).coerceAtLeast(0f)
        val softTransfer = softDelta * TRANSFER_SOFT_FRACTION * level
        val hardTransfer = hardDelta * TRANSFER_HARD_FRACTION * level
        val totalTransfer = softTransfer + hardTransfer
        if (totalTransfer <= 0f) return FluxTransfer.None

        val newHard = (targetTracker.hardFlux - hardTransfer).coerceAtLeast(0f)
        targetTracker.decreaseFlux(totalTransfer)
        targetTracker.setHardFlux(newHard)
        if (softTransfer > 0f) sourceTracker.increaseFlux(softTransfer, false)
        if (hardTransfer > 0f) sourceTracker.increaseFlux(hardTransfer, true)
        return FluxTransfer(
            soft = softTransfer,
            hard = hardTransfer,
            perSecond = totalTransfer / amount.coerceAtLeast(0.0001f),
        )
    }

    private fun updateFluxPressure(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI, transfer: FluxTransfer, amount: Float): Float {
        val key = pressureKey(source, target)
        val previous = engine.customData[key] as? Float ?: 0f
        val maxPressure = ((source.fluxTracker?.maxFlux ?: 0f) * MAX_PRESSURE_FLUX_FRACTION_PER_SECOND).coerceAtLeast(1f)
        val targetPressure = (transfer.perSecond / maxPressure).coerceIn(0f, 1f)
        val response = (PRESSURE_RESPONSE * (amount / 0.0167f).coerceIn(0.35f, 2.5f)).coerceIn(0.04f, 0.72f)
        val smoothed = if (targetPressure > previous) {
            previous + (targetPressure - previous) * response
        } else {
            previous * PRESSURE_DECAY
        }.coerceIn(0f, 1f)
        engine.customData[key] = smoothed
        return smoothed
    }

    private fun maintainTargetStatus(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI, level: Float) {
        val player = engine.playerShip ?: return
        if (target !== player) return
        val shield = formatPercent(TARGET_WEAPON_FLUX_REDUCTION * level)
        engine.maintainStatusForPlayerShip(
            "astd_arc_shared_flux_network_target:${System.identityHashCode(source)}:line1",
            null,
            I18n.t(I18n.Categories.MOD, "system.arc_shared_flux_network.target_status.line1"),
            I18n.t(
                I18n.Categories.MOD,
                "system.arc_shared_flux_network.target_status.line2",
                "dissipation" to formatPercent(TARGET_DISSIPATION_BONUS * level),
                "rof" to formatPercent(TARGET_ROF_BONUS * level),
                "weaponFlux" to shield,
            ),
            false,
        )
    }

    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean {
        val fluxLevel = try { ship.fluxTracker?.fluxLevel ?: 0f } catch (_: Throwable) { 0f }
        return fluxLevel <= MAX_SOURCE_FLUX_LEVEL
    }

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State, effectLevel: Float): ShipSystemStatsScript.StatusData? {
        val suffix = when (state) {
            ShipSystemStatsScript.State.IN -> "in"
            ShipSystemStatsScript.State.ACTIVE -> "active"
            ShipSystemStatsScript.State.OUT -> "out"
            else -> return null
        }
        val line = when (index) {
            0 -> "line1"
            1 -> "line2"
            else -> return null
        }
        val level = effectLevel.coerceIn(0f, 1f)
        return ShipSystemStatsScript.StatusData(
            I18n.t(
                I18n.Categories.MOD,
                "system.arc_shared_flux_network.status.default.$suffix.$line",
                "selfRof" to formatPercent((1f - SOURCE_ROF_MULT) * level),
                "selfManeuver" to formatPercent((1f - SOURCE_MANEUVER_MULT) * level),
                "selfDissipation" to formatPercent(SOURCE_DISSIPATION_BONUS * level),
                "hardDissipation" to formatPercent(SOURCE_HARD_DISSIPATION_FRACTION * level),
                "targetDissipation" to formatPercent(TARGET_DISSIPATION_BONUS * level),
                "targetRof" to formatPercent(TARGET_ROF_BONUS * level),
                "targetWeaponFlux" to formatPercent(TARGET_WEAPON_FLUX_REDUCTION * level),
            ),
            false,
        )
    }

    private data class FluxSnapshot(val curr: Float, val hard: Float)
    private data class FluxTransfer(val soft: Float, val hard: Float, val perSecond: Float) {
        companion object {
            val None = FluxTransfer(0f, 0f, 0f)
        }
    }

    private fun formatPercent(value: Float): String =
        "${(value.coerceAtLeast(0f) * 100f).roundToInt()}%"

    private fun safeElapsed(engine: CombatEngineAPI): Float =
        try { engine.elapsedInLastFrame.coerceIn(0.001f, 0.25f) } catch (_: Throwable) { 0.0167f }

    private fun targetModId(source: ShipAPI, target: ShipAPI): String =
        "${ASTDArcProductionShipIds.STAT_ARC_SHARED_FLUX_NETWORK}:${System.identityHashCode(source)}:${System.identityHashCode(target)}"

    private fun trackKey(source: ShipAPI, target: ShipAPI): String =
        "$TRACK_KEY${System.identityHashCode(source)}:${System.identityHashCode(target)}"

    private fun pressureKey(source: ShipAPI, target: ShipAPI): String =
        "$PRESSURE_KEY${System.identityHashCode(source)}:${System.identityHashCode(target)}"
}
