package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnFireEffect.Companion.FINAL_SHOT_MARK_KEY
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

class StarfallEchoOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        // 命中回调的 point 可能为 null，回退到弹体当前位置。
        val hitPoint = point ?: projectile.location ?: return
        val values = StarfallEchoTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0)

        if (projectile.customData[FINAL_SHOT_MARK_KEY] == true) {
            val ship = (target as? ShipAPI)?.takeUnless { it.isHulk || it.isPhased }
            onFinalHit(projectile, ship, hitPoint, values, engine)
            return
        }

        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isPhased) return
        onNormalHit(projectile, ship, values, engine)
    }

    private fun onNormalHit(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        values: StarfallEchoTuning.Values,
        engine: CombatEngineAPI,
    ) {
        if (ship.owner == (projectile.source?.owner ?: 0)) return
        val host = ship.buffHost()
        val buff = host.find(StarfallEchoResonanceStacks.BUFF_ID) as? StarfallEchoResonanceStacks
            ?: StarfallEchoResonanceStacks(ship, engine, host).also { host.register(it) }
        buff.perStack = values.vulnPerStack
        buff.addStacks(1)
        if (projectile.source != null && projectile.source == engine.playerShip) buff.showOnPlayerHud = true
    }

    private fun onFinalHit(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI?,
        hitPoint: Vector2f,
        values: StarfallEchoTuning.Values,
        engine: CombatEngineAPI,
    ) {
        val buff = ship?.starfallEchoResonanceStacks()
        val stacks = buff?.stacks ?: 0

        val panel = projectile.damageAmount
        if (!panel.isFinite() || panel <= 0f) return

        val damage = StarfallEchoTuning.explosionDamage(panel, stacks, values.explosionDamageMult)
        val radius = StarfallEchoTuning.explosionRadius(stacks)
        val source = projectile.source
        val owner = source?.owner ?: 0

        // 直击面板已由引擎结算，谐振加成的差额由脚本补给直击目标。
        val bonus = StarfallEchoTuning.finalShotBonusDamage(panel, stacks)
        if (ship != null && bonus > 0f && engine.isEntityInPlay(ship)) {
            val covered = ExplosionStrikeImpl.shieldCovers(ship, hitPoint)
            engine.applyDamage(
                ship, ExplosionStrikeImpl.resolveDamagePoint(ship, hitPoint), bonus,
                DamageType.ENERGY, 0f,
                !covered, false, source, true,
            )
        }

        // 直击目标豁免 AOE 避免重复结算；模块舰（空间站）逐模块全额结算会按模块数倍数击穿，按站选举一名代表。
        if (damage > 0f) {
            val victims = CombatUtils.getEntitiesWithinRange(hitPoint, radius).filter { victim ->
                when {
                    victim === projectile -> false
                    victim.owner == owner -> false
                    victim !is ShipAPI && victim !is MissileAPI -> false
                    victim is ShipAPI && (victim.isHulk || victim.isPhased) -> false
                    victim is MissileAPI && victim.isExpired -> false
                    victim === ship && engine.isEntityInPlay(victim) -> false
                    else -> true
                }
            }
            val plan = planStationElection(victims, hitPoint, radius)
            val blockers = collectOcclusionBlockers(engine)
            for (victim in plan.regular + plan.stationRepresentatives) {
                val victimLoc = victim.location ?: continue
                if (isOccluded(hitPoint, victimLoc, blockerCircles(blockers, victim))) continue
                val covered = (victim as? ShipAPI)?.let { ExplosionStrikeImpl.shieldCovers(it, hitPoint) } == true
                val dmgPoint = (victim as? ShipAPI)?.let { ExplosionStrikeImpl.resolveDamagePoint(it, hitPoint) } ?: Vector2f(hitPoint)
                engine.applyDamage(
                    victim, dmgPoint, damage,
                    DamageType.ENERGY, 0f,
                    victim is ShipAPI && !covered, false, source, true,
                )
            }
        }

        buff?.consume()

        StarfallEchoVfx.explosion(engine, hitPoint, stacks, radius, hitFacingDeg(ship, hitPoint))
    }

    // Misc 的静态初始化依赖游戏运行时（单测直接 ExceptionInInitializerError），角度用 atan2 直算。
    private fun hitFacingDeg(ship: ShipAPI?, hitPoint: Vector2f): Float {
        val origin = ship?.location ?: return 0f
        return Math.toDegrees(
            kotlin.math.atan2(
                (hitPoint.y - origin.y).toDouble(),
                (hitPoint.x - origin.x).toDouble(),
            ),
        ).toFloat()
    }

    internal data class AoEVictimPlan(
        val regular: List<CombatEntityAPI>,
        val stationRepresentatives: List<ShipAPI>,
    )

    internal fun planStationElection(
        victims: List<CombatEntityAPI>,
        hitPoint: Vector2f,
        radius: Float,
    ): AoEVictimPlan {
        val regular = ArrayList<CombatEntityAPI>(victims.size)
        val elect = LinkedHashMap<ShipAPI, Pair<ShipAPI, Float>>()
        for (victim in victims) {
            val ship = victim as? ShipAPI
            val groupKey = when {
                ship == null -> null
                ship.parentStation != null -> ship.parentStation
                ship.isShipWithModules -> ship
                else -> null
            }
            if (groupKey == null || ship == null) {
                regular.add(victim)
                continue
            }
            val dist = MathUtils.getDistance(ship.location, hitPoint)
            if (dist > radius) continue
            val prev = elect[groupKey]
            if (prev == null || dist < prev.second) elect[groupKey] = ship to dist
        }
        return AoEVictimPlan(regular, elect.values.map { it.first })
    }

    private fun collectOcclusionBlockers(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { it != null && it.isAlive && !it.isHulk && !it.isPhased }

    private fun blockerCircles(blockers: List<ShipAPI>, victim: CombatEntityAPI): List<BlockerCircle> {
        val victimShip = victim as? ShipAPI
        val circles = ArrayList<BlockerCircle>(blockers.size)
        for (blocker in blockers) {
            if (victimShip != null && isSameStationGroup(victimShip, blocker)) continue
            val loc = blocker.location ?: continue
            circles += BlockerCircle(loc, blocker.collisionRadius)
        }
        return circles
    }

    internal fun isSameStationGroup(a: ShipAPI, b: ShipAPI): Boolean {
        if (a === b) return true
        val rootA = a.parentStation ?: if (a.isShipWithModules) a else null
        val rootB = b.parentStation ?: if (b.isShipWithModules) b else null
        return rootA != null && rootA === rootB
    }

    internal data class BlockerCircle(val center: Vector2f, val radius: Float)

    internal fun isOccluded(from: Vector2f, to: Vector2f, blockers: List<BlockerCircle>): Boolean =
        blockers.any { blocker ->
            val radius = blocker.radius * OCCLUSION_RADIUS_FRACTION
            val ox = from.x - blocker.center.x
            val oy = from.y - blocker.center.y
            if (ox * ox + oy * oy <= radius * radius) return@any false
            segmentIntersectsCircle(from, to, blocker.center, radius)
        }

    internal fun segmentIntersectsCircle(from: Vector2f, to: Vector2f, center: Vector2f, radius: Float): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 0f) {
            0f
        } else {
            (((center.x - from.x) * dx + (center.y - from.y) * dy) / lenSq).coerceIn(0f, 1f)
        }
        val px = from.x + t * dx - center.x
        val py = from.y + t * dy - center.y
        return px * px + py * py <= radius * radius
    }

    private companion object {
        private const val OCCLUSION_RADIUS_FRACTION = 0.9f
    }
}
