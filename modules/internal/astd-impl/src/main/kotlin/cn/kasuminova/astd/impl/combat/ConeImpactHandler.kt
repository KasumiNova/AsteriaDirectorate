package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.impl.combat.ConeImpactHandler.ANGLE_EPS
import cn.kasuminova.astd.impl.combat.ConeImpactHandler.DIST_EPS
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.sqrt

object ConeImpactHandler {
    private val log = Global.getLogger(ConeImpactHandler::class.java)

    const val DIST_EPS = 0.5f

    const val ANGLE_EPS = 1e-3f
    const val RANGE_EPS = 1e-3f

    private const val UNIT_TOLERANCE = 1e-3f

    val LAZYLIB_COARSE_QUERY: (Vector2f, Float) -> List<CombatEntityAPI> =
        { origin, range -> CombatUtils.getEntitiesWithinRange(origin, range) }

    fun resolve(
        engine: CombatEngineAPI,
        spec: ConeImpactSpec,
        coarseQuery: (Vector2f, Float) -> List<CombatEntityAPI> = LAZYLIB_COARSE_QUERY,
    ): List<CombatEntityAPI> {
        val dirLen = sqrt(spec.direction.x * spec.direction.x + spec.direction.y * spec.direction.y)
        if (dirLen.isNaN() || dirLen < 1e-6f) {
            log.warn("锥状冲击 direction 为零长度/非法矢量（${spec.direction}），锥形未定义，本次不结算")
            return emptyList()
        }
        if (kotlin.math.abs(dirLen - 1f) > UNIT_TOLERANCE) {
            log.warn("锥状冲击 direction 非单位矢量（|d|=$dirLen），归一化处理（调用方应传入单位矢量）")
        }
        val dirX = spec.direction.x / dirLen
        val dirY = spec.direction.y / dirLen

        var halfAngle = spec.halfAngleDeg
        if (halfAngle.isNaN() || halfAngle < 0f || halfAngle > 180f) {
            val clamped = if (halfAngle.isNaN()) 0f else halfAngle.coerceIn(0f, 180f)
            log.warn("锥状冲击 halfAngleDeg 越界（$halfAngle），属配置错误，clamp 到 $clamped")
            halfAngle = clamped
        }

        val range = spec.range
        if (range.isNaN() || range <= 0f) {
            log.warn("锥状冲击 range 非正（$range），属配置错误，本次不结算")
            return emptyList()
        }

        var damage = spec.damage
        if (damage.isNaN() || damage < 0f) {
            log.warn("锥状冲击 damage 非法（${spec.damage}），属配置错误，clamp 到 0")
            damage = 0f
        }
        var emp = spec.empDamage
        if (emp.isNaN() || emp < 0f) {
            log.warn("锥状冲击 empDamage 非法（${spec.empDamage}），属配置错误，clamp 到 0")
            emp = 0f
        }
        if (damage <= 0f && emp <= 0f) {
            log.warn("锥状冲击 damage 与 empDamage 同为 0，本次无结算量（仍返回命中清单供 VFX 使用）")
        }

        val candidates = coarseQuery(spec.origin, range)

        val hits = ArrayList<CombatEntityAPI>()
        for (target in candidates) {
            if (!acceptType(spec, target)) continue
            if (target.owner == spec.owner) continue
            if ((target as? ShipAPI)?.isHulk == true) continue
            if (!isInsideCone(spec.origin, dirX, dirY, halfAngle, range, target.location, target.collisionRadius)) continue
            if (!spec.filter.accept(target)) continue
            hits += target
        }

        val settledDamage = when (val adjust = spec.damageAdjust) {
            null -> damage
            else -> {
                val adjusted = adjust.adjust(hits)
                if (adjusted.isNaN() || adjusted < 0f) {
                    log.warn("锥状冲击 damageAdjust 返回非法值（$adjusted），属配置错误，clamp 到 0")
                    0f
                } else {
                    adjusted
                }
            }
        }
        if (settledDamage > 0f || emp > 0f) {
            for (target in hits) {
                engine.applyDamage(
                    target,
                    surfacePoint(spec.origin, target.location, target.collisionRadius),
                    settledDamage,
                    spec.damageType,
                    emp,
                    false,
                    false,
                    spec.source,
                    true,
                )
            }
        }
        return hits
    }

    private fun acceptType(spec: ConeImpactSpec, target: CombatEntityAPI): Boolean = when {
        target is MissileAPI -> spec.hitMissiles
        target is ShipAPI && target.isFighter -> spec.hitFighters
        target is ShipAPI -> spec.hitShips
        else -> false
    }

    fun isInsideCone(
        origin: Vector2f,
        dirX: Float,
        dirY: Float,
        halfAngleDeg: Float,
        range: Float,
        targetLoc: Vector2f,
        collisionRadius: Float,
    ): Boolean {
        val dx = targetLoc.x - origin.x
        val dy = targetLoc.y - origin.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist <= DIST_EPS) return true

        val radius = collisionRadius.coerceAtLeast(0f)
        if (dist - radius > range + RANGE_EPS) return false

        val dot = ((dx * dirX + dy * dirY) / dist).coerceIn(-1f, 1f)
        val angleDeg = Math.toDegrees(acos(dot).toDouble()).toFloat()
        val allowance = Math.toDegrees(atan(radius / dist).toDouble()).toFloat()
        return angleDeg <= halfAngleDeg + allowance + ANGLE_EPS
    }

    fun surfacePoint(origin: Vector2f, targetLoc: Vector2f, collisionRadius: Float): Vector2f {
        val dx = targetLoc.x - origin.x
        val dy = targetLoc.y - origin.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist <= DIST_EPS) return Vector2f(targetLoc)
        val t = ((dist - collisionRadius.coerceAtLeast(0f)) / dist).coerceIn(0f, 1f)
        return Vector2f(origin.x + dx * t, origin.y + dy * t)
    }
}
