package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.combat.CombatUtils
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
/**
 * “七星”折跃发射器的伤害结算薄层（规格 07 §2.2）：
 * 一次闪光爆炸的圆形区域结算（粗筛 → 逐目标 applyDamage → 摧毁统计）；
 * 对舰终结单段/多段的 applyDamage + 沿舰体取点。
 *
 * 动机：所有 `applyDamage` 均为一次性调用——弹体本体 collisionClass = NONE 无第二伤害源，
 * 脚本结算不触发 onHitEffect，无回环（规格 §2.2 结算顺序总表）。
 * 直击目标与区域内目标同额（规格 §0-3「hit 爆炸」整体缩放裁定）。
 */
object SevenStarsDamageHandler {
    private val log = Global.getLogger(SevenStarsDamageHandler::class.java)

    /**
     * 一次闪光十字爆炸的区域结算（规格 §2.2）：
     * 1. 粗筛：LazyLib 空间网格 [CombatUtils.getEntitiesWithinRange]（GCP 已验证路径）；
     * 2. 逐目标：剔除同方、hulk、过期导弹与伤害来源自身；[direct] 直击目标强制纳入
     *    （与区域内目标同额，规格 §0-3）；
     * 3. 结算：`applyDamage(面板 × 倍率, ENERGY, emp=0)`——舰船落点与 bypassShields 走
     *    [resolveShipDamagePoint]（实机判例，见该方法注）；带盾覆盖时落点吸附盾面
     *    （思路出处 GravityCollapseOnHitHandler 238 行同名私有实现——其可见性为 private
     *    不可复用，按规格 §2.2 注记在本类内落同款实现）；
     * 4. 摧毁统计：applyDamage 为同步结算，结算后同帧判定 hulk/不在役/过期，kills 本帧内准确。
     *
     * @return 本轮摧毁数（连跳续段判定的唯一依据）。
     */
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

            // 舰船：盾覆盖 → 盾面落点 + bypass=false（盾吸收，尊重护盾）；否则 → 船体落点 +
            // bypass=true（绕开「盾关闭时 bypass=false 全额无伤害」的引擎行为）。导弹无盾，原样。
            val shieldCovers = (target as? ShipAPI)?.let { shieldCovers(it, at) } == true
            val point = (target as? ShipAPI)?.let { resolveShipDamagePoint(it, at) } ?: Vector2f(at)
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

    /**
     * 对舰终结的一段结算（单段终结与 v5 多段逐段共用）：
     * `applyDamage(面板 × 段倍率, ENERGY, emp)`——玩家单段 emp=0（设计案按字面解读：单段不附带 EMP）；
     * v5 多段每段 emp = 面板等值（设计案 v5 终结栏）。
     * 落点与 bypassShields 走 [resolveShipDamagePoint]/[shieldCovers] 同一口径（实机判例见两方法注）；
     * [at] 仅为十字闪光等视觉落点，伤害落点在盾未覆盖时收敛舰心（界内边缘点结算不可靠，
     * 烟测第 4/5 轮对照：同船同盾态舰心点正常掉血、边缘点恒 0）。
     */
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
            resolveShipDamagePoint(ship, at),
            damage,
            DamageType.ENERGY,
            emp,
            !shieldCovers(ship, at),
            false,
            source,
            true,
        )
    }

    /**
     * 沿舰体取点（规格 §2.2 sampleHullPoints，替代"参考裂隙洪流发射极"的可落地算法）：
     * 从舰心向 [count] 个均布方向（叠加 [Misc.random] 抖动，纯视觉散布，非结算随机，
     * 符合 00-共享基建 §4.1-2）做射线二分，找该方向仍在 [ShipAPI.isPointInBounds] 内的
     * 最远半径并取 80% 处为落点——落点恒在舰体上且永不退化为精确舰心（实机判例：
     * 碰撞圆盘拒绝采样对宽碰撞半径舰体命中率仅 ~3%，64 次仅得 2/7 点；且 applyDamage
     * 落在精确舰心点时全额无效）；射线极端退化（有效半径 < 5% 碰撞半径）记 INFO（可观测降级，
     * 非静默兜底，规格 §2.4）；最后按舰首方向投影排序，使爆炸点「沿舰体次第绽开」。
     */
    fun sampleHullPoints(ship: ShipAPI, count: Int): List<Vector2f> {
        if (count <= 0) return emptyList()
        val center = ship.location
        val radius = ship.collisionRadius.coerceAtLeast(1f)
        val points = ArrayList<Vector2f>(count)
        for (i in 0 until count) {
            val baseAngle = (Math.PI * 2.0 * i / count) + Misc.random.nextFloat() * 0.5
            val dirX = cos(baseAngle).toFloat()
            val dirY = sin(baseAngle).toFloat()
            // 二分该方向仍在舰体内的最远半径（舰心恒在界内，前缀性质对星形界成立）。
            var lo = 0f
            var hi = radius
            repeat(10) {
                val mid = (lo + hi) * 0.5f
                if (ship.isPointInBounds(Vector2f(center.x + mid * dirX, center.y + mid * dirY))) {
                    lo = mid
                } else {
                    hi = mid
                }
            }
            if (lo < radius * 0.05f) {
                log.info(
                    "“七星”多段终结沿舰体取点射线退化（hull=${ship.hullSpec?.hullId}，方向=$baseAngle，" +
                        "有效半径=$lo），按 10% 碰撞半径偏移落点",
                )
                lo = radius * 0.1f
            }
            val r = lo * 0.8f
            points += Vector2f(center.x + r * dirX, center.y + r * dirY)
        }
        val facingRad = Math.toRadians(ship.facing.toDouble())
        val dirX = cos(facingRad).toFloat()
        val dirY = sin(facingRad).toFloat()
        return points.sortedBy { (it.x - center.x) * dirX + (it.y - center.y) * dirY }
    }

    /**
     * 盾覆盖判定：盾开启且 [explosionPoint] 在盾弧内。覆盖时 applyDamage 必须 bypassShields=false
     * （伤害落到盾，尊重护盾）；未覆盖时必须 true——实机判例（烟测第 4 轮对照实验）：
     * 带盾舰船盾关闭时，bypassShields=false 的 applyDamage 全额无伤害（舰心点/界内点同现），
     * bypassShields=true 同点正常结算船体伤害。
     */
    private fun shieldCovers(ship: ShipAPI, explosionPoint: Vector2f): Boolean {
        val shield = ship.shield ?: return false
        return shield.isOn && shield.isWithinArc(explosionPoint)
    }

    /**
     * 舰船伤害落点（思路出处：GravityCollapseOnHitHandler.resolveShieldedDamagePoint，
     * 该实现为 private 不可复用，按规格 §2.2 注记在本类内落同款并收敛非盾路径）：
     * - 盾覆盖：返回盾面落点，避免「看起来打到盾但实际穿透扣船体」；
     * - 盾未覆盖：恒返回舰心。实机判例（烟测第 4/5 轮对照实验）：脚本 applyDamage 的
     *   非舰心落点结算不可靠——`isPointInBounds=true` 的界内边缘点恒 0 伤害，同船同盾态
     *   舰心点正常掉血；落点仅影响装甲格选择与浮字位置，不影响伤害量。
     */
    private fun resolveShipDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f {
        val shield = ship.shield
        if (shield != null && shield.isOn && shield.isWithinArc(explosionPoint)) {
            val shieldLoc = shield.location ?: return Vector2f(ship.location)
            val radius = shield.radius
            if (radius <= 0f) return Vector2f(ship.location)
            val angle = Misc.getAngleInDegrees(shieldLoc, explosionPoint)
            return MathUtils.getPointOnCircumference(shieldLoc, radius, angle)
        }
        return Vector2f(ship.location)
    }
}
