package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

class ASTDLimitTemporalThrusterSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.25f
        private const val THREAT_RANGE = 700f
        private const val CHASE_RANGE = 1250f
        private const val GOOD_RANGE_MULT = 0.92f
        private const val FIRING_ARC = 38f
        private const val HIGH_FLUX = 0.78f
        private const val OFFENSE_FLUX_LIMIT = 0.68f
        private const val DEFENSE_FLUX_LIMIT = 0.82f
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
        if (engine.isPaused || ship.isHulk) return
        if (system.isOn || system.state != ShipSystemAPI.SystemState.IDLE) return
        if (!system.canBeActivated()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val fluxLevel = try { ship.fluxTracker?.fluxLevel ?: 0f } catch (_: Throwable) { 0f }
        if (fluxLevel >= HIGH_FLUX || ship.fluxTracker?.isOverloadedOrVenting == true) return

        val ammo = try { system.ammo } catch (_: Throwable) { 1 }
        val maxAmmo = try { system.maxAmmo } catch (_: Throwable) { 1 }
        val hasReserveCharge = maxAmmo <= 1 || ammo > 1
        val now = engine.getTotalElapsedTime(false)
        if (now - lastUseAt < 1.4f) return

        if (fluxLevel <= DEFENSE_FLUX_LIMIT && incomingThreat(ship, engine, missileDangerDir, collisionDangerDir)) {
            activate(ship, now)
            return
        }

        val actualTarget = validTarget(ship, engine, target)
            ?: validTarget(ship, engine, try { ship.shipTarget } catch (_: Throwable) { null })
            ?: nearestEnemy(ship, engine)
            ?: return
        val distance = MathUtils.getDistance(ship.location, actualTarget.location)
        val range = longestWeaponRange(ship).coerceAtLeast(600f)
        val angleDiff = abs(angleDiffDeg(ship.facing, VectorUtils.getAngle(ship.location, actualTarget.location)))
        val targetFlux = try { actualTarget.fluxTracker?.fluxLevel ?: 0f } catch (_: Throwable) { 0f }
        val targetVulnerable = targetFlux >= 0.55f ||
            (try { actualTarget.fluxTracker?.isOverloadedOrVenting == true } catch (_: Throwable) { false }) ||
            (try { actualTarget.hullLevel <= 0.45f } catch (_: Throwable) { false })

        val wantsChase = hasReserveCharge &&
            fluxLevel <= OFFENSE_FLUX_LIMIT &&
            targetVulnerable &&
            distance in (range * GOOD_RANGE_MULT)..CHASE_RANGE
        val wantsReposition = hasReserveCharge &&
            fluxLevel <= OFFENSE_FLUX_LIMIT &&
            distance <= range * 1.05f &&
            angleDiff > FIRING_ARC &&
            angleDiff < 130f

        if (wantsChase || wantsReposition) {
            activate(ship, now)
        }
    }

    private fun activate(ship: ShipAPI, now: Float) {
        try {
            ship.useSystem()
            lastUseAt = now
        } catch (_: Throwable) {
        }
    }

    private fun incomingThreat(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?
    ): Boolean {
        if (missileDangerDir != null && missileDangerDir.lengthSquared() > 0.01f) return true
        if (collisionDangerDir != null && collisionDangerDir.lengthSquared() > 0.01f) return true
        return engine.projectiles.any { projectile ->
            projectile is DamagingProjectileAPI &&
                projectile.owner != ship.owner &&
                !projectile.didDamage() &&
                MathUtils.getDistance(ship.location, projectile.location) <= THREAT_RANGE &&
                projectile.damageAmount + projectile.empAmount * 0.25f >= 250f
        }
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

    private fun longestWeaponRange(ship: ShipAPI): Float {
        return try {
            ship.allWeapons
                .asSequence()
                .filter { !it.isDecorative }
                .filter { it.type != com.fs.starfarer.api.combat.WeaponAPI.WeaponType.MISSILE }
                .map { it.range }
                .filter { it > 0f }
                .maxOrNull() ?: 700f
        } catch (_: Throwable) {
            700f
        }
    }

    private fun angleDiffDeg(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
