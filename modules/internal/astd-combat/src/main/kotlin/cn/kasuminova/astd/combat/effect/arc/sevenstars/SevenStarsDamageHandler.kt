package cn.kasuminova.astd.combat.effect.arc.sevenstars

import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsDamageHandler.TERMINAL_CORE_RADIUS
import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsDamageHandler.TERMINAL_SPACING
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

object SevenStarsDamageHandler {

    private const val TERMINAL_SPACING = 175f * 0.7f

    private const val SHIELD_SPACING_MULT = 0.67f

    private const val TARGETING_RADIUS_PAD_MIN = 30f
    private const val TARGETING_RADIUS_PAD_SPAN = 50f

    private const val TERMINAL_CORE_RADIUS = 50f

    private const val BOUNDS_PULLBACK_RATIO = 0.9f

    fun flashExplosion(
        engine: CombatEngineAPI,
        at: Vector2f,
        direct: CombatEntityAPI?,
        source: ShipAPI?,
        owner: Int,
        panelDamage: Float,
        mult: Float,
        aoeRadius: Float,
    ): Int {
        val damage = panelDamage * mult
        val targets = LinkedHashSet<CombatEntityAPI>()
        targets += CombatUtils.getEntitiesWithinRange(at, aoeRadius)
        if (direct != null) targets += direct

        var kills = 0
        for (target in targets) {
            if (target === source) continue
            if (target.owner == owner) continue
            if (target is ShipAPI && target.isHulk) continue
            if (target is MissileAPI && target.isExpired) continue
            if (target !is ShipAPI && target !is MissileAPI) continue

            // 盾关闭时 bypassShields=false 会被引擎结算为无伤害，爆心未被盾覆盖必须 bypass=true 压点。
            val shieldCovers = (target as? ShipAPI)?.let { ExplosionStrikeImpl.shieldCovers(it, at) } == true
            val point = (target as? ShipAPI)?.let { ExplosionStrikeImpl.resolveDamagePoint(it, at) } ?: Vector2f(at)
            engine.applyDamage(
                target,
                point,
                damage,
                DamageType.ENERGY,
                0f,
                target is ShipAPI && !shieldCovers,
                false,
                source,
                true,
            )
            if ((target as? ShipAPI)?.isHulk == true ||
                !engine.isEntityInPlay(target) ||
                (target as? MissileAPI)?.isExpired == true
            ) {
                kills++
            }
        }
        return kills
    }

    fun terminalStrike(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        at: Vector2f,
        damage: Float,
        emp: Float,
        source: ShipAPI?,
    ) {
        engine.applyDamage(
            ship,
            ExplosionStrikeImpl.resolveDamagePoint(ship, at),
            damage,
            DamageType.ENERGY,
            emp,
            !ExplosionStrikeImpl.shieldCovers(ship, at),
            false,
            source,
            true,
        )
    }

    // 走位取点沿用原版 RiftCascadeEffect.getNextArcLoc 的碰撞箱贴边算法。
    fun sampleRiftCascadePoints(ship: ShipAPI, count: Int, anchor: Vector2f): List<Vector2f> {
        if (count <= 0) return emptyList()
        val center = Vector2f(ship.location)
        val targetRadius = ship.collisionRadius.coerceAtLeast(1f)
        val shieldOn = ship.shield?.isOn == true
        val spacing = if (shieldOn) TERMINAL_SPACING * SHIELD_SPACING_MULT else TERMINAL_SPACING
        val anglePerSegment = (360f * spacing / (2f * Math.PI.toFloat() * targetRadius)).coerceAtMost(90f)

        val startAngle = Misc.getAngleInDegrees(center, anchor)
        val firstCircle = MathUtils.getPointOnCircumference(center, targetRadius, startAngle)
        val spawnDir = Misc.getClosestTurnDirection(
            Misc.getAngleInDegrees(anchor, firstCircle),
            Misc.getAngleInDegrees(anchor, center),
        ).let { if (it == 0f) 1f else it }

        val points = ArrayList<Vector2f>(count)
        var angle = startAngle
        repeat(count) {
            val circlePoint = MathUtils.getPointOnCircumference(center, targetRadius, angle)
            val actualRadius = Global.getSettings().getTargetingRadius(circlePoint, ship, shieldOn) +
                    TARGETING_RADIUS_PAD_MIN + Misc.random.nextFloat() * TARGETING_RADIUS_PAD_SPAN
            var point = MathUtils.getPointOnCircumference(center, actualRadius, angle)
            if (!shieldOn) {
                val bounds = ship.exactBounds
                if (bounds != null) {
                    var best: Vector2f? = null
                    var bestDist = Float.MAX_VALUE
                    for (segment in bounds.segments) {
                        val dist = MathUtils.getDistance(segment.p1, point)
                        if (dist < bestDist) {
                            bestDist = dist
                            best = segment.p1
                        }
                    }
                    best?.let { vertex ->
                        val pullDir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(vertex, point))
                        pullDir.scale(TERMINAL_CORE_RADIUS * BOUNDS_PULLBACK_RATIO)
                        point = Vector2f.add(vertex, pullDir, null)
                    }
                }
            }
            points += point
            angle += anglePerSegment * spawnDir
        }
        return points
    }
}
