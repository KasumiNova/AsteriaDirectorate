package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravStormTuning
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

class GravStormSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.5f

        private const val ENGAGE_RANGE_FRAC = 0.9f

        private const val MAX_FLUX_LEVEL_AFTER_USE = 0.85f
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

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
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
        if (ship.isPhased) return
        if (ship.fluxTracker.isOverloadedOrVenting) return
        if (ship.fluxTracker.fluxLevel + GravStormTuning.ACTIVATION_FLUX_FRACTION > MAX_FLUX_LEVEL_AFTER_USE) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravStormTuning.BASE_RANGE) * ENGAGE_RANGE_FRAC
        for (candidate in engine.ships) {
            if (candidate == null || candidate === ship) continue
            if (candidate.owner == ship.owner || candidate.isFighter || candidate.isHulk || !candidate.isAlive) continue
            if (GravStormTuning.arcBaseCountRange(candidate.hullSize) == null) continue
            val dist = Misc.getDistance(ship.location, candidate.location) - candidate.collisionRadius
            if (dist > range) continue
            val angleDiff = Misc.getAngleDiff(ship.facing, Misc.getAngleInDegrees(ship.location, candidate.location))
            if (!GravStormTuning.isInCone(angleDiff)) continue
            ship.useSystem()
            return
        }
    }
}
