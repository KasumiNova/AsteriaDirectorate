package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.effect.joint.VisionShiftTuning
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc

class ASTDVisionShiftSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.5f
        private const val HIGH_FLUX = 0.85f
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
        target: ShipAPI?
    ) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return
        if (system.state != ShipSystemAPI.SystemState.IDLE || !system.canBeActivated()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val fluxTracker = ship.fluxTracker
        if (fluxTracker != null && (fluxTracker.isOverloadedOrVenting || fluxTracker.fluxLevel >= HIGH_FLUX)) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, VisionShiftTuning.SYSTEM_RANGE)
        var chosen = validTarget(ship, engine, ship.shipTarget)
        if (chosen == null || !withinRange(ship, chosen, range)) {
            chosen = engine.ships
                .asSequence()
                .mapNotNull { validTarget(ship, engine, it) }
                .filter { withinRange(ship, it, range) }
                .maxByOrNull { it.fleetMember?.deploymentPointsCost ?: 0f }
        }
        chosen ?: return

        ship.shipTarget = chosen
        ship.useSystem()
    }

    private fun withinRange(ship: ShipAPI, target: ShipAPI, range: Float): Boolean {
        val dist = Misc.getDistance(ship.location, target.location)
        return dist <= range + ship.collisionRadius + target.collisionRadius
    }

    private fun validTarget(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): ShipAPI? {
        if (target == null || target === ship || target.isHulk || !target.isAlive) return null
        if (target.owner == ship.owner || target.isFighter || target.isDrone) return null
        if (!engine.isEntityInPlay(target)) return null
        return target
    }
}
