package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil

/**
 * 坠星残翼弹体驱动：碰撞类别 NONE 下由脚本结算全部命中。
 * 普通光梭接触首个目标即结算全额伤害并消散；断熵贯通弹每 0.05s 对贴面目标结算 25% 面板伤害并全程穿透。
 */
class StarfallWingWeaponEffect : EveryFrameWeaponEffectPlugin {

    class ProjectileState(
        val empowered: Boolean,
        val ownerWeapon: WeaponAPI,
        var pierceTimer: Float = 0f,
        var lastPierceLocation: Vector2f? = null,
        val passContacts: PiercePassTracker = PiercePassTracker(),
    )

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        // .wpn 只有一个 everyFrameEffect 槽，bootstrap 必须由此代行。
        CombatVfxBootstrap.ensureInstalled(engine)
        // VFX 全局共享，多武器实例同帧推进会加速寿命，按帧去重
        if (engine.customData[KEY_VFX_ADVANCED_AT] != engine.getTotalElapsedTime(false)) {
            engine.customData[KEY_VFX_ADVANCED_AT] = engine.getTotalElapsedTime(false)
            StarfallWingVfx.advance(engine, amount)
        }

        val states = projectileStates(engine)
        for ((proj, state) in states.entries.toList()) {
            // 失效弹体不论归属都要清，武器被毁后其状态不再有人驱动
            if (!engine.isEntityInPlay(proj)) {
                states.remove(proj)
                continue
            }
            // 弹体状态表按战斗全局共享，多舰同装时只驱动本武器的弹体，避免重复结算
            if (state.ownerWeapon !== weapon) continue
            val from = state.lastPierceLocation ?: Vector2f(proj.location)
            val to = Vector2f(proj.location)
            state.lastPierceLocation = to
            if (state.empowered) {
                advanceEmpowered(engine, proj, state, from, to, amount)
            } else {
                if (advanceNormal(engine, proj, from, to)) states.remove(proj)
            }
        }
    }

    // ---------- 普通光梭：接触结算全额伤害并消散 ----------

    private fun advanceNormal(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        from: Vector2f,
        to: Vector2f,
    ): Boolean {
        val source = proj.source
        val owner = source?.owner ?: 0

        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == owner) continue
            if (!ship.isAlive && !ship.isHulk) continue
            val contact = sweepContact(ship, from, to, proj.collisionRadius) ?: continue
            val shieldContact = shieldCoversAt(ship, contact, proj.collisionRadius)
            if (shieldContact) {
                engine.applyDamage(
                    ship, shieldSurfacePoint(ship, contact), proj.damageAmount,
                    DamageType.ENERGY, proj.empAmount, false, false, source, true,
                )
                spawnShieldSpark(engine, contact)
            } else {
                engine.applyDamage(
                    ship, contact, proj.damageAmount,
                    DamageType.ENERGY, proj.empAmount, true, false, source, true,
                )
            }
            creditMainGunHit(ship, proj, source)
            consumeProjectile(engine, proj)
            return true
        }

        for (missile in engine.missiles) {
            if (missile === proj || missile.owner == owner || missile.isExpired) continue
            if (!touchesSimpleTarget(missile, from, to, proj.collisionRadius)) continue
            engine.applyDamage(
                missile, surfaceContactPoint(missile.location, missile.collisionRadius, from, to),
                proj.damageAmount, DamageType.ENERGY, proj.empAmount, false, false, source, true,
            )
            consumeProjectile(engine, proj)
            return true
        }

        for (asteroid in engine.asteroids) {
            if (asteroid.owner == owner || !engine.isEntityInPlay(asteroid)) continue
            if (!touchesSimpleTarget(asteroid, from, to, proj.collisionRadius)) continue
            engine.applyDamage(
                asteroid, surfaceContactPoint(asteroid.location, asteroid.collisionRadius, from, to),
                proj.damageAmount, DamageType.ENERGY, proj.empAmount, false, false, source, true,
            )
            consumeProjectile(engine, proj)
            return true
        }

        return false
    }

    private fun consumeProjectile(engine: CombatEngineAPI, proj: DamagingProjectileAPI) {
        // API 层无 setDidDamage（仅 MissileAPI 暴露），脚本命中按超时消散路径淡出，视觉差异可忽略
        engine.removeEntity(proj)
    }

    // ---------- 断熵贯通弹：贴面 tick 穿透 ----------

    private fun advanceEmpowered(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        from: Vector2f,
        to: Vector2f,
        amount: Float,
    ) {
        state.pierceTimer += amount
        val contacted = Collections.newSetFromMap<CombatEntityAPI>(IdentityHashMap())
        if (pierceSweep(engine, proj, state, from, to, contacted)) {
            state.pierceTimer = 0f
        }
        state.passContacts.retainContacts(contacted)
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
        val swept = MathUtils.getDistance(from, to)
        val tickDueGlobal = state.pierceTimer >= StarfallWingTuning.PIERCE_TICK_SECONDS
        val midX = (from.x + to.x) * 0.5f
        val midY = (from.y + to.y) * 0.5f

        var damaged = false

        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == owner) continue
            if (!ship.isAlive && !ship.isHulk) continue
            val shieldRadius = ship.shield?.radius ?: 0f
            val reach = maxOf(shieldRadius, ship.collisionRadius) + swept * 0.5f + projRadius + CONTACT_MARGIN
            val mdx = ship.location.x - midX
            val mdy = ship.location.y - midY
            if (mdx * mdx + mdy * mdy > reach * reach) continue

            val contact = sweepContact(ship, from, to, projRadius) ?: continue
            contactedTargets.add(ship)
            val firstContact = state.passContacts.isFirstContact(ship)
            state.passContacts.touch(ship)
            val tickDue = tickDueGlobal || firstContact
            if (!tickDue) continue

            if (shieldCoversAt(ship, contact, projRadius)) {
                engine.applyDamage(
                    ship, shieldSurfacePoint(ship, contact), StarfallWingTuning.pierceTickDamage(proj.damageAmount),
                    DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(StarfallWingTuning.EMPOWERED_EMP),
                    false, false, source, true,
                )
                spawnShieldSpark(engine, contact)
            } else {
                engine.applyDamage(
                    ship, Vector2f(proj.location), StarfallWingTuning.pierceTickDamage(proj.damageAmount),
                    DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(StarfallWingTuning.EMPOWERED_EMP),
                    true, false, source, true,
                )
            }
            latchFirstShipHit(engine, proj, state, ship)
            damaged = true
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

    /** 断熵首次命中敌舰：+10% 虚数充能，并从双翼免费激发 2 枚星羽追踪光矢。 */
    private fun latchFirstShipHit(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        ship: ShipAPI,
    ) {
        if (ship.isFighter || !ship.isAlive || ship.isHulk) return
        if (!state.passContacts.tryLatchFirstShip()) return
        val source = proj.source ?: return
        val pool = ImaginaryCharge.of(source)
        pool.add(ImaginaryWingsTuning.EMPOWERED_HIT_GAIN)
        pool.tryHitGain(ImaginaryWingsTuning.HIT_GAIN)
        pool.lastMainGunHit = ship
        for (side in intArrayOf(1, -1)) {
            val anchor = ImaginaryWingsTuning.WING_ANCHORS[1]
            StarfallWingMotes.spawn(
                engine, source,
                ImaginaryWingsTuning.anchorWorld(source, anchor, side),
                ImaginaryWingsTuning.launchAngleDeg(source.facing, side),
                ship,
            )
        }
    }

    private fun pierceSimpleTarget(
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
        engine.applyDamage(
            target, surfaceContactPoint(target.location, target.collisionRadius, from, to),
            StarfallWingTuning.pierceTickDamage(proj.damageAmount),
            DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(StarfallWingTuning.EMPOWERED_EMP),
            false, false, proj.source, true,
        )
        return true
    }

    // ---------- 命中判定与工具 ----------

    /** 沿本帧位移段采样，返回首个接触点（护盾优先于船体）。 */
    private fun sweepContact(ship: ShipAPI, from: Vector2f, to: Vector2f, touchRadius: Float): Vector2f? {
        val swept = MathUtils.getDistance(from, to)
        val samples = ceil(swept / SWEEP_SAMPLE_SPACING).toInt().coerceAtLeast(1)
        val bounds = ship.exactBounds
        bounds?.update(ship.location, ship.facing)
        val hullSegments = bounds?.segments?.map { Vector2f(it.p1) to Vector2f(it.p2) }

        var hullContact: Vector2f? = null
        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val p = Vector2f(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
            if (shieldCoversAt(ship, p, touchRadius)) return p
            if (hullContact == null && touchesHull(ship, p, touchRadius, hullSegments)) hullContact = p
        }
        return hullContact
    }

    private fun touchesSimpleTarget(target: CombatEntityAPI, from: Vector2f, to: Vector2f, projRadius: Float): Boolean {
        return StarfallWingTuning.distanceToSegment(target.location, from, to) <= target.collisionRadius + projRadius
    }

    private fun creditMainGunHit(ship: ShipAPI, proj: DamagingProjectileAPI, source: ShipAPI?) {
        if (source == null) return
        val pool = ImaginaryCharge.of(source)
        pool.tryHitGain(ImaginaryWingsTuning.HIT_GAIN)
        if (!ship.isFighter && ship.isAlive) pool.lastMainGunHit = ship
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

        private const val KEY_PROJECTILE_STATES = "astd_starfall_wing_projectile_states"

        private const val KEY_VFX_ADVANCED_AT = "astd_starfall_wing_vfx_advanced_at"

        @Suppress("UNCHECKED_CAST")
        fun projectileStates(engine: CombatEngineAPI): IdentityHashMap<DamagingProjectileAPI, ProjectileState> {
            return engine.customData.getOrPut(KEY_PROJECTILE_STATES) {
                IdentityHashMap<DamagingProjectileAPI, ProjectileState>()
            } as IdentityHashMap<DamagingProjectileAPI, ProjectileState>
        }
    }
}
