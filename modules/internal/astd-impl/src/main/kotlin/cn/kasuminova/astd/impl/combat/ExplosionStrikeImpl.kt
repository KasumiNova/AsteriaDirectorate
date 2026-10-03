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

/**
 * 全额衰减：半径内所有目标吃全额面板伤害，无距离衰减。
 * ASTD 现役爆炸站点（摧锋/辉星/七星/坠星残响/湮灭涡旋）的用户裁定口径。
 */
object FullExplosionFalloffImpl : ExplosionFalloff {
    override fun damageFor(damage: Float, surfaceDist: Float, radius: Float): Float = damage
}

/**
 * 线性衰减（对齐原版 DamagingExplosion 语义）：表面距离 ≤ [coreRadius] 全额，
 * 到爆炸半径线性降到 [minDamage]（绝对伤害量），半径外 clamp 到 [minDamage] 不反推。
 */
class LinearExplosionFalloffImpl(
    /** 核心区半径（su）：表面距离不逾此值吃全额面板。 */
    val coreRadius: Float,
    /** 半径边缘的最低伤害（绝对量）。 */
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

/**
 * [ExplosionStrike] 的无状态实现：范围爆炸统一结算 + 统一落点解析。
 *
 * 结算流程（§接口契约）：粗筛（[LAZYLIB_COARSE_QUERY]）→ 内置通用过滤（同 owner 跳过、
 * 仅舰船/导弹、hulk/相位/过期跳过）→ [ExplosionStrike.strike] 的 victimFilter 终判 →
 * 逐目标落点解析（舰船 [resolveDamagePoint]、导弹取爆心点）→ applyDamage
 * （bypassShields = 舰船且盾未覆盖；dealsSoftFlux=false；末参 playSound）。
 *
 * 0 值防线（全部记 WARN，不静默）：radius 非正不结算、damage/emp 非法 clamp 到 0、
 * 护盾数据异常（盾心缺失/盾半径非正）退回舰体压点。
 */
object ExplosionStrikeImpl : ExplosionStrike {
    private val log = Global.getLogger(ExplosionStrikeImpl::class.java)

    /** 舰体压点截断系数：爆心到舰心距离截断到 0.9×碰撞半径（点在舰体内、浮字落命中侧）。 */
    private const val HULL_CLAMP_FRACTION = 0.9f

    /** 爆心与舰心重合的除零防线（su）：距离不逾此值原样返回爆心。 */
    private const val CENTER_EPS = 1e-3f

    /**
     * 默认粗筛：LazyLib 空间网格查询。抽成可注入的函数值供桩引擎单测注入候选清单
     * （对齐 ConeImpactHandler 同型注记）；游戏内恒走本默认实现。
     */
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

    /**
     * 结算执行体（[coarseQuery] 仅测试注入；游戏内恒走 [LAZYLIB_COARSE_QUERY]）。
     */
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
            // 不取 Misc.getAngleInDegrees：Misc 类初始化依赖游戏运行时（无头/单测直接
            // ExceptionInInitializerError），此处语义等价于 atan2 直出角度，就地计算
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

    /**
     * 舰体压点（纯函数，供单测直接驱动）：爆心到舰心距离 ≤ 0.9×碰撞半径原样返回爆心，
     * 否则沿「舰心 → 爆心」方向截断到 0.9×碰撞半径。落点仅影响装甲格选择与浮字位置，
     * 不影响伤害量。
     */
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
