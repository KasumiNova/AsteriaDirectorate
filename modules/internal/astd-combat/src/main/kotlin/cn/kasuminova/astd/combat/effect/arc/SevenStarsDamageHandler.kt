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

    /** 终结落点周向间距（su）：原版裂隙洪流 SPAWN_SPACING 175 × 裂隙组件尺寸倍率 0.7。 */
    private const val TERMINAL_SPACING = 175f * 0.7f

    /** 盾开启时的间距折减（原版口径：hitShield 时 perSpawn ×0.67）。 */
    private const val SHIELD_SPACING_MULT = 0.67f

    /** 贴碰撞箱后的外扩抖动下限/幅度（su，原版口径 30 + 50×rand）。 */
    private const val TARGETING_RADIUS_PAD_MIN = 30f
    private const val TARGETING_RADIUS_PAD_SPAN = 50f

    /** 吸附顶点后回拉基准（su，原版 mine explosionSpec coreRadius 50）。 */
    private const val TERMINAL_CORE_RADIUS = 50f

    /** 顶点回拉比例（原版口径 ×0.9）。 */
    private const val BOUNDS_PULLBACK_RATIO = 0.9f

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
     * 对舰终结落点取点（需求定案：直接抄原版裂隙洪流发射极 RiftCascadeEffect.getNextArcLoc
     * 的碰撞箱贴边走位，替代原射线二分取点）：
     * 1. 首点：舰心→[anchor]（来向）方向的碰撞圆周点；
     * 2. 走位方向 spawnDir = 来向视角下「首点方位角 → 舰心方位角」的最近转向（原版口径，
     *    恒 0 时取 +1）；
     * 3. 逐点：绕舰心角步进 360°×[TERMINAL_SPACING]/(2π×碰撞半径)（cap 90°，盾开启时
     *    间距 ×0.67），落点先取 `getTargetingRadius`（吃精确碰撞箱/盾面半径）再外扩
     *    30~80su 抖动；盾未开启且精确碰撞箱可用时吸附最近顶点并沿顶点→落点方向回拉
     *    [TERMINAL_CORE_RADIUS]×0.9（让爆炸心悬在舰缘外侧、爆风盖住舰缘——原版地雷
     *    同款处理）。
     *
     * 与原版差异：原版从 beam 读来向与目标，本实现显式传 [ship]/[anchor]；尺寸倍率
     * 恒 1（原版随已生成数递减的 sizeMult 与七星段表语义无关，不引入）。
     * 返回点沿舰体周向依走位方向次第排布，天然满足「多段沿舰体次第绽开」。
     */
    fun sampleRiftCascadePoints(ship: ShipAPI, count: Int, anchor: Vector2f): List<Vector2f> {
        if (count <= 0) return emptyList()
        val center = Vector2f(ship.location)
        val targetRadius = ship.collisionRadius.coerceAtLeast(1f)
        val shieldOn = ship.shield?.isOn == true
        val spacing = if (shieldOn) TERMINAL_SPACING * SHIELD_SPACING_MULT else TERMINAL_SPACING
        val anglePerSegment = (360f * spacing / (2f * Math.PI.toFloat() * targetRadius)).coerceAtMost(90f)

        // 走位方向（原版 spawnDir 口径）：来向锚点视角下首点方位 → 舰心方位的最近转向。
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
