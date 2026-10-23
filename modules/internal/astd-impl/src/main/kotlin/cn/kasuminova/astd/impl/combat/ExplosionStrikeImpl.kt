package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.api.combat.ExplosionFalloff
import cn.kasuminova.astd.api.combat.ExplosionStrike
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt

object FullExplosionFalloffImpl : ExplosionFalloff {
    override fun damageFor(damage: Float, surfaceDist: Float, radius: Float): Float = damage
}

class LinearExplosionFalloffImpl(
    val coreRadius: Float,
    val minDamage: Float,
) : ExplosionFalloff {
    override fun damageFor(damage: Float, surfaceDist: Float, radius: Float): Float {
        val core = coreRadius.coerceAtLeast(0f)
        if (surfaceDist <= core) return damage
        val span = radius - core
        if (span <= 0f) return minDamage
        val t = ((surfaceDist - core) / span).coerceIn(0f, 1f)
        return damage + (minDamage - damage) * t
    }
}

object ExplosionStrikeImpl : ExplosionStrike {
    private val log = Global.getLogger(ExplosionStrikeImpl::class.java)

    private const val HULL_CLAMP_FRACTION = 0.9f

    private const val CENTER_EPS = 1e-3f

    val LAZYLIB_COARSE_QUERY: (Vector2f, Float) -> List<CombatEntityAPI> =
        { origin, range -> CombatUtils.getEntitiesWithinRange(origin, range) }

    override fun strike(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        damage: Float,
        damageType: DamageType,
        emp: Float,
        source: ShipAPI?,
        owner: Int,
        falloff: ExplosionFalloff,
        victimFilter: (CombatEntityAPI) -> Boolean,
        playSound: Boolean,
    ): List<CombatEntityAPI> = strike(
        engine, center, radius, damage, damageType, emp, source, owner,
        falloff, victimFilter, playSound, LAZYLIB_COARSE_QUERY,
    )

    fun strike(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        damage: Float,
        damageType: DamageType,
        emp: Float = 0f,
        source: ShipAPI? = null,
        owner: Int,
        falloff: ExplosionFalloff,
        victimFilter: (CombatEntityAPI) -> Boolean = { true },
        playSound: Boolean = true,
        coarseQuery: (Vector2f, Float) -> List<CombatEntityAPI> = LAZYLIB_COARSE_QUERY,
    ): List<CombatEntityAPI> {
        if (radius.isNaN() || radius <= 0f) {
            log.warn("范围爆炸结算 radius 非正（$radius），属配置错误，本次不结算")
            return emptyList()
        }
        var settledDamage = damage
        if (settledDamage.isNaN() || settledDamage < 0f) {
            log.warn("范围爆炸结算 damage 非法（$damage），属配置错误，clamp 到 0")
            settledDamage = 0f
        }
        var settledEmp = emp
        if (settledEmp.isNaN() || settledEmp < 0f) {
            log.warn("范围爆炸结算 emp 非法（$emp），属配置错误，clamp 到 0")
            settledEmp = 0f
        }
        if (settledDamage <= 0f && settledEmp <= 0f) {
            log.warn("范围爆炸结算 damage 与 emp 同为 0，本次无结算量")
            return emptyList()
        }

        val victims = ArrayList<CombatEntityAPI>()
        for (victim in coarseQuery(center, radius)) {
            if (victim.owner == owner) continue
            if (victim !is ShipAPI && victim !is MissileAPI) continue
            if (victim is ShipAPI && (victim.isHulk || victim.isPhased)) continue
            if (victim is MissileAPI && victim.isExpired) continue
            if (!victimFilter(victim)) continue

            val covered = (victim as? ShipAPI)?.let { shieldCovers(it, center) } == true
            val dmgPoint = (victim as? ShipAPI)?.let { resolveDamagePoint(it, center) } ?: Vector2f(center)
            val dist = MathUtils.getDistance(center, victim.location)
            val surfaceDist = (dist - victim.collisionRadius).coerceAtLeast(0f)
            engine.applyDamage(
                victim, dmgPoint, falloff.damageFor(settledDamage, surfaceDist, radius),
                damageType, settledEmp,
                victim is ShipAPI && !covered, false, source, playSound,
            )
            victims += victim
        }
        return victims
    }

    override fun resolveDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f {
        val shield = ship.shield
        if (shield != null && shield.isOn && shield.isWithinArc(explosionPoint)) {
            val shieldLoc = shield.location
            val radius = shield.radius
            if (shieldLoc == null || radius <= 0f) {
                log.warn("范围爆炸落点解析护盾数据异常（loc=$shieldLoc radius=$radius），退回舰体压点: ship=${ship.hullSpec?.hullId}")
                return clampIntoHull(ship.location, ship.collisionRadius, explosionPoint)
            }
            // `Misc.getAngleInDegrees` 会在无头测试中触发 Misc 静态初始化失败，此处直接计算角度
            val angle = Math.toDegrees(
                kotlin.math.atan2(
                    (explosionPoint.y - shieldLoc.y).toDouble(),
                    (explosionPoint.x - shieldLoc.x).toDouble(),
                ),
            ).toFloat()
            return MathUtils.getPointOnCircumference(shieldLoc, radius, angle)
        }
        return clampIntoHull(ship.location, ship.collisionRadius, explosionPoint)
    }

    override fun shieldCovers(ship: ShipAPI, explosionPoint: Vector2f): Boolean {
        val shield = ship.shield ?: return false
        return shield.isOn && shield.isWithinArc(explosionPoint)
    }

    fun clampIntoHull(shipCenter: Vector2f, collisionRadius: Float, explosionPoint: Vector2f): Vector2f {
        val dx = explosionPoint.x - shipCenter.x
        val dy = explosionPoint.y - shipCenter.y
        val dist = sqrt(dx * dx + dy * dy)
        val limit = collisionRadius.coerceAtLeast(0f) * HULL_CLAMP_FRACTION
        if (dist <= limit || dist <= CENTER_EPS) return Vector2f(explosionPoint)
        val scale = limit / dist
        return Vector2f(shipCenter.x + dx * scale, shipCenter.y + dy * scale)
    }
}
