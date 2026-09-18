package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils

/**
 * 落叶飞花系统 AI：爆发窗口（1s）用于输出窗口期压制或紧急规避。
 *
 * 进攻口径：目标处于本舰非导弹武器射程内且目标已脆弱（高辐能/过载/低结构）时激活，
 * 保留至少 1 充能兜底；防御口径：大额来袭威胁（导弹/高伤弹体逼近）且辐能安全时激活规避。
 * 结构对照 [ASTDLimitTemporalThrusterSystemAI]（同族爆发系统），扫描间隔 0.25s。
 */
class ASTDBurstFlowSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.25f
        private const val THREAT_RANGE = 700f
        private const val THREAT_DAMAGE = 250f
        private const val HIGH_FLUX = 0.78f
        private const val OFFENSE_FLUX_LIMIT = 0.68f
        private const val DEFENSE_FLUX_LIMIT = 0.82f
        private const val TARGET_VULNERABLE_FLUX = 0.55f
        private const val TARGET_VULNERABLE_HULL = 0.45f
        private const val MIN_REUSE_INTERVAL_SEC = 1.4f
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

    override fun advance(amount: Float, missileDangerDir: org.lwjgl.util.vector.Vector2f?, collisionDangerDir: org.lwjgl.util.vector.Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return
        if (system.state != ShipSystemAPI.SystemState.IDLE || !system.canBeActivated()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val fluxTracker = ship.fluxTracker ?: return
        if (fluxTracker.isOverloadedOrVenting) return
        val fluxLevel = fluxTracker.fluxLevel
        if (fluxLevel >= HIGH_FLUX) return

        val now = engine.getTotalElapsedTime(false)
        if (now - lastUseAt < MIN_REUSE_INTERVAL_SEC) return

        if (fluxLevel <= DEFENSE_FLUX_LIMIT && incomingThreat(ship, engine, missileDangerDir, collisionDangerDir)) {
            activate(ship, now)
            return
        }

        if (fluxLevel > OFFENSE_FLUX_LIMIT) return
        if (system.maxAmmo > 1 && system.ammo <= 1) return

        val actualTarget = validTarget(ship, engine, target)
            ?: validTarget(ship, engine, ship.shipTarget)
            ?: return
        if (!isVulnerable(actualTarget)) return

        val distance = MathUtils.getDistance(ship.location, actualTarget.location)
        if (distance <= longestNonMissileRange(ship)) {
            activate(ship, now)
        }
    }

    private fun activate(ship: ShipAPI, now: Float) {
        ship.useSystem()
        lastUseAt = now
    }

    private fun incomingThreat(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        missileDangerDir: org.lwjgl.util.vector.Vector2f?,
        collisionDangerDir: org.lwjgl.util.vector.Vector2f?
    ): Boolean {
        if (missileDangerDir != null && missileDangerDir.lengthSquared() > 0.01f) return true
        if (collisionDangerDir != null && collisionDangerDir.lengthSquared() > 0.01f) return true
        return engine.projectiles.any { projectile ->
            projectile is DamagingProjectileAPI &&
                projectile.owner != ship.owner &&
                !projectile.didDamage() &&
                MathUtils.getDistance(ship.location, projectile.location) <= THREAT_RANGE &&
                projectile.damageAmount + projectile.empAmount * 0.25f >= THREAT_DAMAGE
        }
    }

    private fun isVulnerable(target: ShipAPI): Boolean {
        val tracker = target.fluxTracker
        return (tracker != null && (tracker.fluxLevel >= TARGET_VULNERABLE_FLUX || tracker.isOverloadedOrVenting)) ||
            target.hullLevel <= TARGET_VULNERABLE_HULL
    }

    private fun validTarget(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): ShipAPI? {
        if (target == null || target === ship || target.isHulk || target.owner == ship.owner) return null
        if (!engine.isEntityInPlay(target)) return null
        return target
    }

    private fun longestNonMissileRange(ship: ShipAPI): Float =
        ship.allWeapons
            .asSequence()
            .filter { !it.isDecorative && it.type != WeaponAPI.WeaponType.MISSILE }
            .map { it.range }
            .filter { it > 0f }
            .maxOrNull() ?: 600f
}
