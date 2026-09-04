package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.api.combat.ConeImpactSpec
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

/**
 * 锥状冲击结算器（规格 00-共享基建 §2）：object 无状态，一次性几何筛选 + 逐目标伤害结算。
 *
 * 动机：正电子冲击波/贯星之矛/摧锋（预留）三案共享同一套锥面结算语义。本结算器只做
 * 「输入一次事件 → 输出命中清单 + applyDamage」，不含每帧近炸检测循环、不含 VFX 树构建。
 *
 * 流程（§2.2）：
 * 1. 粗筛：LazyLib 空间网格圆形范围查询（[CombatUtils.getEntitiesWithinRange]，语义为
 *    「目标表面进入半径」——圆半径 + 目标碰撞半径），把候选从全图压到半径内；
 * 2. 类型与归属筛：hitShips/hitFighters/hitMissiles + owner 剔除 + hulk 剔除；
 * 3. 角度精筛：目标矢量与中轴夹角 ≤ 半角 + atan(半径/距离) 放宽（避免擦边大目标漏判）；
 * 4. 结算：逐目标 [CombatEngineAPI.applyDamage]（不触发 onHitEffect，无二次回环）；
 * 5. 命中清单返回调用方，由其触发各自 VFX（锥面原型见 impl/render 的 ConeImpactVfx）。
 *
 * 0 值防线（全部记 WARN，不静默）：direction 非单位矢量归一化、零长度方向不结算、
 * range 非正不结算、halfAngle 越界 clamp、damage/empDamage 负值或 NaN clamp 到 0、
 * damage 与 empDamage 同为 0 时本次无结算量。
 */
object ConeImpactHandler {
    private val log = Global.getLogger(ConeImpactHandler::class.java)

    /** 顶点重叠判定阈值（su）：目标中心距顶点不逾此值即视为重叠，直接纳入、不做角度与锥长判定。 */
    const val DIST_EPS = 0.5f

    /** 角度/距离比较的浮点容忍（度/su）：恰在边界的目标判定为纳入（含边界语义）。 */
    const val ANGLE_EPS = 1e-3f
    const val RANGE_EPS = 1e-3f

    /** direction 长度与 1 的偏差容忍：超出即记 WARN 并归一化。 */
    private const val UNIT_TOLERANCE = 1e-3f

    /**
     * 默认粗筛：LazyLib 空间网格查询。抽成可注入的函数值是为了「粗筛结果与全表扫结果一致性」
     * 对照测试（规格 §2.4-4）——游戏内永远走默认实现，测试注入网格语义复刻与全表扫两个变体。
     */
    val LAZYLIB_COARSE_QUERY: (Vector2f, Float) -> List<CombatEntityAPI> =
        { origin, range -> CombatUtils.getEntitiesWithinRange(origin, range) }

    /**
     * 执行一次锥状冲击结算，返回纳入结算的目标清单（无论本次是否有实际伤害量）。
     *
     * [coarseQuery] 粗筛候选提供者，默认 LazyLib 空间网格；仅测试注入其他实现。
     */
    fun resolve(
        engine: CombatEngineAPI,
        spec: ConeImpactSpec,
        coarseQuery: (Vector2f, Float) -> List<CombatEntityAPI> = LAZYLIB_COARSE_QUERY,
    ): List<CombatEntityAPI> {
        // ---- 入参防线 ----
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

        // ---- 1. 粗筛 ----
        val candidates = coarseQuery(spec.origin, range)

        // ---- 2/3. 类型归属筛 + 角度精筛 + filter 终判 ----
        val hits = ArrayList<CombatEntityAPI>()
        for (target in candidates) {
            if (!acceptType(spec, target)) continue
            if (target.owner == spec.owner) continue
            if ((target as? ShipAPI)?.isHulk == true) continue
            if (!isInsideCone(spec.origin, dirX, dirY, halfAngle, range, target.location, target.collisionRadius)) continue
            if (!spec.filter.accept(target)) continue
            hits += target
        }

        // ---- 4. 结算 ----
        if (damage > 0f || emp > 0f) {
            for (target in hits) {
                engine.applyDamage(
                    target,
                    surfacePoint(spec.origin, target.location, target.collisionRadius),
                    damage,
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

    /** 类型筛：导弹/战机/舰船按 spec 开关；其余实体（普通弹体、小行星等）永不纳入。 */
    private fun acceptType(spec: ConeImpactSpec, target: CombatEntityAPI): Boolean = when {
        target is MissileAPI -> spec.hitMissiles
        target is ShipAPI && target.isFighter -> spec.hitFighters
        target is ShipAPI -> spec.hitShips
        else -> false
    }

    /**
     * 锥形几何精筛（纯函数，供单测直接驱动）：
     * - 顶点重叠（dist ≤ [DIST_EPS]）直接纳入，不除零；
     * - 锥长按表面距离判定（dist - 碰撞半径 ≤ range），与 LazyLib 粗筛「表面进入半径」语义一致，
     *   保证精筛结果是粗筛候选的子集（规格 §2.4-4 一致性前提）；
     * - 角度按碰撞半径放宽 atan(radius/dist)，避免擦边大目标漏判；边界含端点（+[ANGLE_EPS] 浮点容忍）。
     */
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

    /**
     * 伤害落点：目标朝向爆点的表面点（顶点沿目标方向推进 dist - 半径）；
     * 顶点与目标重叠时取目标中心。护盾弧由 applyDamage 内部判定，此处不做吸附。
     */
    fun surfacePoint(origin: Vector2f, targetLoc: Vector2f, collisionRadius: Float): Vector2f {
        val dx = targetLoc.x - origin.x
        val dy = targetLoc.y - origin.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist <= DIST_EPS) return Vector2f(targetLoc)
        val t = ((dist - collisionRadius.coerceAtLeast(0f)) / dist).coerceIn(0f, 1f)
        return Vector2f(origin.x + dx * t, origin.y + dy * t)
    }
}
