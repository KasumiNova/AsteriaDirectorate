package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

class ASTDPlasmaArmorShieldBoostSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.28f
        private const val ACTIVATE_FLUX_LEVEL = 0.18f
        private const val DEACTIVATE_FLUX_LEVEL = 0.88f
        private const val SAFE_FLUX_LEVEL = 0.34f
        private const val HEAVY_THREAT_RANGE = 1200f
        private const val CLOSE_ENEMY_RANGE = 950f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var flags: ShipwideAIFlags? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.flags = flags
        this.engine = engine
        scanInterval.forceIntervalElapsed()
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return

        val tracker = ship.fluxTracker ?: return
        val fluxLevel = try { tracker.fluxLevel } catch (_: Throwable) { 0f }
        val overloadedOrVenting = try { tracker.isOverloadedOrVenting } catch (_: Throwable) { false }
        if (system.isOn || system.state == ShipSystemAPI.SystemState.IN || system.state == ShipSystemAPI.SystemState.ACTIVE) {
            if (fluxLevel >= DEACTIVATE_FLUX_LEVEL || overloadedOrVenting || isRetreating()) {
                toggle(ship)
            }
            return
        }

        if (!system.canBeActivated()) return
        if (overloadedOrVenting || fluxLevel >= DEACTIVATE_FLUX_LEVEL || isRetreating()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val heavyThreat = incomingHeavyThreat(ship, engine, missileDangerDir, collisionDangerDir)
        val closeEnemy = closeEnemyPressure(ship, engine, target)
        val underPressure = heavyThreat || closeEnemy
        val canCarryFlux = fluxLevel <= SAFE_FLUX_LEVEL || (underPressure && fluxLevel <= 0.72f)
        if (underPressure && canCarryFlux && fluxLevel >= ACTIVATE_FLUX_LEVEL) {
            toggle(ship)
        }
    }

    private fun incomingHeavyThreat(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
    ): Boolean {
        if (missileDangerDir != null && missileDangerDir.lengthSquared() > 0.01f) return true
        if (collisionDangerDir != null && collisionDangerDir.lengthSquared() > 0.01f) return true
        return engine.projectiles.any { projectile ->
            projectile.owner != ship.owner &&
                !projectile.didDamage() &&
                MathUtils.getDistance(ship.location, projectile.location) <= HEAVY_THREAT_RANGE &&
                projectile.damageAmount + projectile.empAmount * 0.25f >= 450f
        }
    }

    private fun closeEnemyPressure(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): Boolean {
        validEnemy(ship, engine, target)?.let {
            if (MathUtils.getDistance(ship.location, it.location) <= CLOSE_ENEMY_RANGE) return true
        }
        return engine.ships.any { candidate ->
            validEnemy(ship, engine, candidate) != null &&
                MathUtils.getDistance(ship.location, candidate.location) <= CLOSE_ENEMY_RANGE
        }
    }

    private fun validEnemy(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): ShipAPI? {
        if (target == null || target === ship || target.owner == ship.owner || target.isHulk) return null
        if (!engine.isEntityInPlay(target)) return null
        return target
    }

    private fun isRetreating(): Boolean {
        val flags = this.flags ?: return false
        return try {
            flags.hasFlag(ShipwideAIFlags.AIFlags.RUN_QUICKLY) ||
                flags.hasFlag(ShipwideAIFlags.AIFlags.BACK_OFF) ||
                flags.hasFlag(ShipwideAIFlags.AIFlags.BACKING_OFF)
        } catch (_: Throwable) {
            false
        }
    }

    private fun toggle(ship: ShipAPI) {
        try {
            ship.useSystem()
        } catch (_: Throwable) {
        }
    }
}
