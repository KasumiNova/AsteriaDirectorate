package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravTimeflowTuning
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.ENGAGE_RANGE_FRAC
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.SCAN_INTERVAL_SEC
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc

class GravTimeflowInterferenceSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.4f

        private const val ENGAGE_RANGE_FRAC = 0.95f

        private const val TARGET_FLAG_DURATION = 1.5f

        const val DANGER_FLUX_LEVEL = 0.85f

        const val DANGER_BONUS = 30f
        const val FLAGSHIP_BONUS = 20f
        const val COMBAT_BONUS = 10f
        const val AUTO_SCORE_WEIGHT = 0.5f
        const val OFFENSE_BONUS = 15f

        const val CARRIER_LOGISTICS_PENALTY = 0.3f

        data class CandidateSnapshot(
            val hullSize: ShipAPI.HullSize?,
            val deployPoints: Float,
            val autoScore: Float,
            val fluxLevel: Float,
            val inDanger: Boolean,
            val isPlayerFlagship: Boolean,
            val isCombat: Boolean,
            val offensiveIntent: Boolean,
            val carrierOrLogistics: Boolean,
        )

        fun tierBaseDp(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
            ShipAPI.HullSize.DESTROYER -> 10f
            ShipAPI.HullSize.CRUISER -> 20f
            ShipAPI.HullSize.CAPITAL_SHIP -> 40f
            else -> 5f
        }

        fun sizeScore(hullSize: ShipAPI.HullSize?, deployPoints: Float): Float =
            tierBaseDp(hullSize) + deployPoints

        fun dangerBonus(fluxLevel: Float, inDanger: Boolean): Float =
            if (inDanger && fluxLevel >= DANGER_FLUX_LEVEL) DANGER_BONUS else 0f

        fun roleBonus(isPlayerFlagship: Boolean, isCombat: Boolean, autoScore: Float): Float =
            (if (isPlayerFlagship) FLAGSHIP_BONUS else 0f) +
                    (if (isCombat) COMBAT_BONUS else 0f) +
                    autoScore * AUTO_SCORE_WEIGHT

        fun offenseBonus(offensiveIntent: Boolean): Float =
            if (offensiveIntent) OFFENSE_BONUS else 0f

        fun rolePenaltyMult(carrierOrLogistics: Boolean): Float =
            if (carrierOrLogistics) CARRIER_LOGISTICS_PENALTY else 1f

        fun scoreCandidate(c: CandidateSnapshot): Float =
            (sizeScore(c.hullSize, c.deployPoints) +
                    dangerBonus(c.fluxLevel, c.inDanger) +
                    roleBonus(c.isPlayerFlagship, c.isCombat, c.autoScore) +
                    offenseBonus(c.offensiveIntent)) * rolePenaltyMult(c.carrierOrLogistics)

        fun selectBest(candidates: List<CandidateSnapshot>): Int? {
            if (candidates.isEmpty()) return null
            var bestIdx = 0
            var bestScore = scoreCandidate(candidates[0])
            for (i in 1 until candidates.size) {
                val score = scoreCandidate(candidates[i])
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            return bestIdx
        }
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null

    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        scanInterval.forceIntervalElapsed()
    }

    override fun advance(
        amount: Float,
        missileDangerDir: org.lwjgl.util.vector.Vector2f?,
        collisionDangerDir: org.lwjgl.util.vector.Vector2f?,
        target: ShipAPI?,
    ) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return

        if (engine.isPaused) return
        if (ship.isHulk) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        if (system.isOn) return
        if (system.cooldownRemaining > 0f) return
        if (!system.canBeActivated()) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravTimeflowTuning.BASE_RANGE)
        val engageRange = range * ENGAGE_RANGE_FRAC

        val candidates = ArrayList<ShipAPI>()
        val snapshots = ArrayList<CandidateSnapshot>()
        for (candidate in engine.ships) {
            if (!isValidAlly(ship, candidate)) continue
            val dist = Misc.getDistance(ship.location, candidate.location)
            if (dist > engageRange) continue
            candidates += candidate
            snapshots += snapshotOf(engine, candidate)
        }

        val bestIdx = selectBest(snapshots) ?: return
        val victim = candidates[bestIdx]

        // TARGET_FOR_SHIP_SYSTEM 是原版目标锁定系统（熵放大器等）AI 向 stats 传递目标的约定旗标
        ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM, TARGET_FLAG_DURATION, victim)
        ship.useSystem()
    }

    private fun isValidAlly(ship: ShipAPI, candidate: ShipAPI): Boolean =
        candidate !== ship && candidate.owner == ship.owner &&
                !candidate.isFighter && !candidate.isDrone && !candidate.isHulk && candidate.isAlive &&
                !candidate.isStation && !candidate.isStationModule

    private fun snapshotOf(engine: CombatEngineAPI, candidate: ShipAPI): CandidateSnapshot {
        val hullSpec = candidate.hullSpec
        val hints = hullSpec.hints
        val isCombat = hints.contains(ShipHullSpecAPI.ShipTypeHints.COMBAT)
        val isCarrier = hullSpec.isCarrier
        val isLogistics = hints.contains(ShipHullSpecAPI.ShipTypeHints.CIVILIAN) ||
                hints.contains(ShipHullSpecAPI.ShipTypeHints.FREIGHTER) ||
                hints.contains(ShipHullSpecAPI.ShipTypeHints.TANKER) ||
                hints.contains(ShipHullSpecAPI.ShipTypeHints.LINER) ||
                hints.contains(ShipHullSpecAPI.ShipTypeHints.TRANSPORT)
        val hostileTarget = candidate.shipTarget
        return CandidateSnapshot(
            hullSize = candidate.hullSize,
            deployPoints = hullSpec.suppliesToRecover,
            autoScore = hullSpec.fleetPoints.toFloat(),
            fluxLevel = candidate.fluxTracker?.fluxLevel ?: 0f,
            inDanger = candidate.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.NEEDS_HELP),
            isPlayerFlagship = candidate === engine.playerShip,
            isCombat = isCombat,
            offensiveIntent = hostileTarget != null && hostileTarget.owner != candidate.owner && hostileTarget.isAlive,
            carrierOrLogistics = (isCarrier && !isCombat) || isLogistics,
        )
    }
}
