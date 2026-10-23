package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect.Companion.projectileStates
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect.ProjectileState
import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil

class StarfallWingWeaponEffect : EveryFrameWeaponEffectPlugin {

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        // .wpn 只有一个 everyFrameEffect 槽，bootstrap 必须由此代行。
        CombatVfxBootstrap.ensureInstalled(engine)
        StarfallWingVfx.advance(engine, amount)

        val states = projectileStates(engine)
        for ((proj, state) in states.entries.toList()) {
            if (state.ownerWeapon !== weapon) continue
            if (!engine.isEntityInPlay(proj)) {
                states.remove(proj)
                continue
            }
            advanceProjectile(engine, weapon, proj, state, amount)
        }
    }

    private fun advanceProjectile(
        engine: CombatEngineAPI,
        weapon: WeaponAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        amount: Float,
    ) {
        state.pierceTimer += amount
        val sweepFrom = state.lastPierceLocation ?: Vector2f(proj.location)
        val sweepTo = Vector2f(proj.location)
        state.lastPierceLocation = sweepTo
        val contacted = Collections.newSetFromMap<CombatEntityAPI>(IdentityHashMap())
        if (pierceSweep(engine, proj, state, sweepFrom, sweepTo, contacted)) {
            state.pierceTimer = 0f
        }
        state.passContacts.retainContacts(contacted)

        state.moteTimer += amount
        while (state.moteTimer >= StarfallWingTuning.MOTE_INTERVAL_SECONDS) {
            state.moteTimer -= StarfallWingTuning.MOTE_INTERVAL_SECONDS
            spawnMote(engine, weapon, proj)
        }
    }

    private fun pierceSweep(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        from: Vector2f,
        to: Vector2f,
        contactedTargets: MutableSet<CombatEntityAPI>,
    ): Boolean {
        val source = proj.source
        val owner = source?.owner ?: 0
        val projRadius = proj.collisionRadius
        val shipTouchRadius = projRadius
        val swept = MathUtils.getDistance(from, to)
        val samples = ceil(swept / SWEEP_SAMPLE_SPACING).toInt().coerceAtLeast(1)
        val tickDueGlobal = state.pierceTimer >= StarfallWingTuning.PIERCE_TICK_SECONDS
        val midX = (from.x + to.x) * 0.5f
        val midY = (from.y + to.y) * 0.5f

        var damaged = false

        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == owner) continue
            if (!ship.isAlive && !ship.isHulk) continue
            val shieldRadius = ship.shield?.radius ?: 0f
            val reach = maxOf(shieldRadius, ship.collisionRadius) + swept * 0.5f + shipTouchRadius + CONTACT_MARGIN
            val mdx = ship.location.x - midX
            val mdy = ship.location.y - midY
            if (mdx * mdx + mdy * mdy > reach * reach) continue

            // exactBounds 每舰每帧只 update 一次，采样循环与贴面判定复用同一份快照。
            val bounds = ship.exactBounds
            bounds?.update(ship.location, ship.facing)
            val hullSegments = bounds?.segments?.map { Vector2f(it.p1) to Vector2f(it.p2) }

            var shieldContact: Vector2f? = null
            var hullContact: Vector2f? = null
            for (i in 0..samples) {
                val t = i / samples.toFloat()
                val p = Vector2f(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
                if (shieldCoversAt(ship, p, shipTouchRadius)) {
                    shieldContact = p
                    break
                }
                if (hullContact == null && touchesHull(ship, p, shipTouchRadius, hullSegments)) hullContact = p
            }
            if (shieldContact == null && hullContact == null) continue
            contactedTargets.add(ship)
            val firstContact = state.passContacts.isFirstContact(ship)
            state.passContacts.touch(ship)
            val tickDue = tickDueGlobal || firstContact

            if (shieldContact != null) {
                if (resolveShieldContact(engine, proj, state, ship, shieldContact, tickDue)) damaged = true
            } else if (tickDue) {
                settleHullPierce(engine, proj, state, ship)
                damaged = true
            }
        }

        for (missile in engine.missiles) {
            if (missile === proj || missile.owner == owner || missile.isExpired) continue
            if (pierceSimpleTarget(engine, proj, state, missile, from, to, midX, midY, swept, projRadius, contactedTargets)) {
                damaged = true
            }
        }
        for (asteroid in engine.asteroids) {
            if (asteroid.owner == owner || !engine.isEntityInPlay(asteroid)) continue
            if (pierceSimpleTarget(engine, proj, state, asteroid, from, to, midX, midY, swept, projRadius, contactedTargets)) {
                damaged = true
            }
        }

        return damaged
    }

    // 装甲格分摊由原版 applyDamage 的装甲池机制承担，禁止逐格手动结算（每格会各吃一整拍伤害）。
    internal fun settleHullPierce(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        ship: ShipAPI,
    ) {
        engine.applyDamage(
            ship, Vector2f(proj.location), StarfallWingTuning.pierceTickDamage(proj.damageAmount),
            DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(proj.empAmount),
            true, false, proj.source, true,
        )
        attachAdaptationOnce(engine, proj, state, ship)
    }

    internal fun pierceSimpleTarget(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        target: CombatEntityAPI,
        from: Vector2f,
        to: Vector2f,
        midX: Float,
        midY: Float,
        swept: Float,
        projRadius: Float,
        contactedTargets: MutableSet<CombatEntityAPI>,
    ): Boolean {
        val reach = target.collisionRadius + swept * 0.5f + projRadius + CONTACT_MARGIN
        val mdx = target.location.x - midX
        val mdy = target.location.y - midY
        if (mdx * mdx + mdy * mdy > reach * reach) return false
        if (StarfallWingTuning.distanceToSegment(target.location, from, to) > target.collisionRadius + projRadius) {
            return false
        }
        contactedTargets.add(target)
        if (!state.passContacts.trySettleOnce(target)) return false
        val contact = surfaceContactPoint(target.location, target.collisionRadius, from, to)
        engine.applyDamage(
            target, contact, StarfallWingTuning.pierceTickDamage(proj.damageAmount),
            DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(proj.empAmount),
            false, false, proj.source, true,
        )
        return true
    }

    internal fun resolveShieldContact(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        ship: ShipAPI,
        contactPoint: Vector2f,
        tickDue: Boolean,
    ): Boolean {
        if (!tickDue) return false
        val damagePoint = shieldSurfacePoint(ship, contactPoint)
        engine.applyDamage(
            ship, damagePoint, StarfallWingTuning.pierceTickDamage(proj.damageAmount),
            DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(proj.empAmount),
            false, false, proj.source, true,
        )
        attachAdaptationOnce(engine, proj, state, ship)
        spawnShieldSpark(engine, contactPoint)
        return true
    }

    private fun spawnMote(engine: CombatEngineAPI, weapon: WeaponAPI, proj: DamagingProjectileAPI) {
        val source = weapon.ship ?: proj.source ?: return
        val sideSign = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) 1f else -1f
        val dir = proj.facing + 90f * sideSign
        val launchVel = MathUtils.getPointOnCircumference(
            Vector2f(proj.velocity), StarfallWingTuning.MOTE_SIDE_SPEED, dir,
        )
        val spawned = engine.spawnProjectile(
            source, null, StarfallWingTuning.MOTE_WEAPON_ID, Vector2f(proj.location), dir, launchVel,
        ) as? MissileAPI
        if (spawned == null) {
            log.warn("[ASTD] 坠星残翼子射弹生成失败：spawnProjectile 未产出 MissileAPI: weapon=${StarfallWingTuning.MOTE_WEAPON_ID}")
            return
        }
        spawned.source = source
        spawned.missileAI = StarfallWingMoteAi(
            spawned, StarfallWingMoteAi.nearestEnemyShip(engine, source, spawned.location, MOTE_INITIAL_TARGET_RANGE),
        )
        spawned.empResistance = 10000
        spawned.armingTime = 0f
        spawned.damageAmount = StarfallWingTuning.moteDamage(proj.damageAmount)
        spawned.maxFlightTime = MOTE_FLIGHT_TIME
        spawned.setNoGlowTime(999f)
        spawned.isNoFlameoutOnFizzling = true
        spawned.interruptContrail()
        spawned.spriteAlphaOverride = 0f
        spawned.glowRadius = 0f
        // 脚本 spawn 的弹体不触发 onFireEffect，拖尾必须显式登记。
        ProjectileVfxDriverPlugin.track(engine, spawned, StarfallWingTuning.MOTE_SPEC_ID)
        StarfallWingVfx.spawnMoteSplitFlare(engine, Vector2f(spawned.location))
    }

    private fun attachAdaptationOnce(engine: CombatEngineAPI, proj: DamagingProjectileAPI, state: ProjectileState, ship: ShipAPI) {
        if (!state.passContacts.tryLatchAdaptation()) return
        StarfallWingAdaptationStacks.attachStacks(ship, engine, 1f, proj.source)
    }

    private fun shieldCoversAt(ship: ShipAPI, point: Vector2f, touchRadius: Float): Boolean {
        val shield = ship.shield ?: return false
        if (!shield.isOn) return false
        if (MathUtils.getDistance(point, ship.location) > shield.radius + touchRadius) return false
        return shield.isWithinArc(point)
    }

    private fun touchesHull(ship: ShipAPI, point: Vector2f, touchRadius: Float, hullSegments: List<Pair<Vector2f, Vector2f>>?): Boolean {
        val limit = touchRadius + CONTACT_MARGIN
        if (hullSegments == null) {
            return MathUtils.getDistance(point, ship.location) <= ship.collisionRadius + limit
        }
        if (StarfallWingTuning.pointInPolygon(point, hullSegments)) return true
        for ((a, b) in hullSegments) {
            if (StarfallWingTuning.distanceToSegment(point, a, b) <= limit) return true
        }
        return false
    }

    private fun surfaceContactPoint(center: Vector2f, radius: Float, from: Vector2f, to: Vector2f): Vector2f {
        val closest = StarfallWingTuning.closestPointOnSegment(center, from, to)
        val dx = closest.x - center.x
        val dy = closest.y - center.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist <= radius || dist <= 1e-3f) return closest
        val scale = radius / dist
        return Vector2f(center.x + dx * scale, center.y + dy * scale)
    }

    private fun shieldSurfacePoint(ship: ShipAPI, contactPoint: Vector2f): Vector2f {
        val shield = ship.shield ?: return Vector2f(ship.location)
        val radius = shield.radius
        if (radius <= 0f) return Vector2f(ship.location)
        // Misc 的静态初始化依赖游戏运行时（单测直接 ExceptionInInitializerError），角度用 atan2 直算。
        val angle = Math.toDegrees(
            kotlin.math.atan2(
                (contactPoint.y - ship.location.y).toDouble(),
                (contactPoint.x - ship.location.x).toDouble(),
            ),
        ).toFloat()
        return MathUtils.getPointOnCircumference(ship.location, radius, angle)
    }

    private fun spawnShieldSpark(engine: CombatEngineAPI, point: Vector2f) {
        repeat(2) {
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val to = MathUtils.getPointOnCircumference(
                point, MathUtils.getRandomNumberInRange(24f, 56f), dir,
            )
            engine.spawnEmpArcVisual(
                point, null, to, null,
                MathUtils.getRandomNumberInRange(3f, 5f),
                Color(170, 110, 255, 210), Color(240, 225, 255, 235),
            ).setFadedOutAtStart(true)
        }
    }

    companion object {
        private const val SWEEP_SAMPLE_SPACING = 20f

        private const val CONTACT_MARGIN = 4f

        private const val MOTE_INITIAL_TARGET_RANGE = 1400f

        private const val MOTE_FLIGHT_TIME = 2.0f

        private val log = AstdLog.logger
    }
}
