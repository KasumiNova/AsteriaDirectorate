package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

class ASTDSuppressionModeSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.25f
        private const val MAX_FLUX_LEVEL_TO_ACTIVATE = 0.5f
        private const val CLOSE_THREAT_RANGE_FRACTION = 0.45f
        private const val ENGAGE_RANGE_MULT = 1.05f
        private const val PUNISH_RANGE_MULT = 1.35f
        private const val VULNERABLE_FLUX_LEVEL = 0.6f
        private const val MIN_WEAPON_RANGE_FALLBACK = 700f
        private const val REACTIVATE_DELAY_SEC = 1f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)
    private var lastUseAt = -999f

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        scanInterval.forceIntervalElapsed()
        lastUseAt = -999f
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk || !ship.isAlive) return
        if (system.isOn || system.state != ShipSystemAPI.SystemState.IDLE) return
        if (!system.canBeActivated()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val tracker = ship.fluxTracker ?: return
        if (tracker.isOverloadedOrVenting) return
        if (tracker.fluxLevel >= MAX_FLUX_LEVEL_TO_ACTIVATE) return

        if (missileDangerDir != null && missileDangerDir.lengthSquared() > 0.01f) return
        if (collisionDangerDir != null && collisionDangerDir.lengthSquared() > 0.01f) return

        val now = engine.getTotalElapsedTime(false)
        if (now - lastUseAt < REACTIVATE_DELAY_SEC) return

        val range = longestNonMissileWeaponRange(ship).coerceAtLeast(MIN_WEAPON_RANGE_FALLBACK)

        val nearest = nearestEnemy(ship, engine) ?: return
        val nearestDist = MathUtils.getDistance(ship.location, nearest.location)
        if (nearestDist <= range * CLOSE_THREAT_RANGE_FRACTION && nearest.shipTarget === ship) return

        val actualTarget = validTarget(ship, engine, target)
            ?: validTarget(ship, engine, ship.shipTarget)
            ?: nearest
        val distance = MathUtils.getDistance(ship.location, actualTarget.location)
        val targetTracker = actualTarget.fluxTracker
        val targetVulnerable = targetTracker != null &&
                (targetTracker.isOverloadedOrVenting || targetTracker.fluxLevel >= VULNERABLE_FLUX_LEVEL)

        val engageLimit = range * if (targetVulnerable) PUNISH_RANGE_MULT else ENGAGE_RANGE_MULT
        if (distance > engageLimit) return

        ship.useSystem()
        lastUseAt = now
    }

    private fun nearestEnemy(ship: ShipAPI, engine: CombatEngineAPI): ShipAPI? =
        engine.ships
            .asSequence()
            .filter { validTarget(ship, engine, it) != null }
            .minByOrNull { MathUtils.getDistance(ship.location, it.location) }

    private fun validTarget(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): ShipAPI? {
        if (target == null || target === ship || target.isHulk || target.owner == ship.owner) return null
        if (!engine.isEntityInPlay(target)) return null
        return target
    }

    private fun longestNonMissileWeaponRange(ship: ShipAPI): Float =
        ship.allWeapons
            .asSequence()
            .filter { !it.isDecorative }
            .filter { it.type != WeaponAPI.WeaponType.MISSILE }
            .map { it.range }
            .filter { it > 0f }
            .maxOrNull() ?: 0f
}
