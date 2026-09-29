package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnFireEffect.Companion.FINAL_SHOT_MARK_KEY
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

/**
 * 坠星残响的命中路由（挂普通弹 `.proj` 的 `onHitEffect`；第 5 发与普通弹同 spec，
 * 由 [FINAL_SHOT_MARK_KEY] 标记区分）：
 *
 * - 普通弹：命中舰船（护盾/船体均可，设计案未区分）叠 1 层「结构谐振」
 *   （[StarfallEchoResonanceStacks]，至多 4 层，不随时间消散）；
 * - 第 5 发：命中恒爆炸（无论目标有无谐振层）——半径 150su×(层数+1)（0 层 150su 纯视觉、
 *   4 层封顶 750su）；范围结算等额能量伤害（设计案「等额规模」口径：伤害 = 第 5 发面板 × 层数
 *   × 难度倍率，即面板的 100%~400%，层数 0 即 0，无 AOE），每层被消耗的谐振使第 5 发伤害
 *   +50%（直击额外部分由脚本 applyDamage 补给直击目标，不计入 AOE 基数），后消耗全部层数，
 *   最后播放爆炸特效（[StarfallEchoVfx.explosion]，十字辉星跟随「目标舰心 → 命中点」方位交叉）。
 *
 * AOE 口径（摧锋同款裁定）：存活直击目标豁免 AOE（直击面板已由引擎原生结算，重复计入会双倍）；
 * 同阵营目标豁免；舰船遮挡豁免——爆心到目标舰心的视线被其他存活舰船（含被直击船、不分阵营，
 * 不含残骸与相位单位）的碰撞圆截断时该目标不受波及（完全遮挡即免伤，见 [isOccluded]）。
 * 脚本 `applyDamage` 落点与 bypassShields 走七星/辉星/摧锋实机判例同款口径
 * （盾覆盖 → 盾面落点 + bypass=false；未覆盖 → 舰心落点 + bypass=true）。
 *
 * 难度取值每次命中调用 [StarfallEchoTuning.resolve] 一次（不缓存）。
 */
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

        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isPhased) return
        val hitPoint = point ?: projectile.location ?: return

        val values = StarfallEchoTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0)

        if (projectile.customData[FINAL_SHOT_MARK_KEY] == true) {
            onFinalHit(projectile, ship, hitPoint, values, engine)
        } else {
            onNormalHit(projectile, ship, values, engine)
        }
    }

    /**
     * 普通弹命中：叠 1 层结构谐振（上限 4），每层易伤按难度查表。
     * 同阵营目标不叠层（与第 5 发 AOE 的同阵营豁免同口径；source 缺失时按 0 处理）。
     */
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

    /**
     * 第 5 发命中：恒爆炸（无论目标有无谐振层）——直击补伤 → AOE（层数 0 即 0，跳过）→
     * 消层 → 特效。十字辉星跟随「目标舰心 → 命中点」方位交叉。
     */
    private fun onFinalHit(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        hitPoint: Vector2f,
        values: StarfallEchoTuning.Values,
        engine: CombatEngineAPI,
    ) {
        val buff = ship.starfallEchoResonanceStacks()
        val stacks = buff?.stacks ?: 0

        val panel = projectile.damageAmount
        if (!panel.isFinite() || panel <= 0f) return

        val damage = StarfallEchoTuning.explosionDamage(panel, stacks, values.explosionDamageMult)
        val radius = StarfallEchoTuning.explosionRadius(stacks)
        val source = projectile.source
        val owner = source?.owner ?: 0

        // 直击补伤：每层被消耗的谐振使第 5 发伤害 +50%，提升部分由脚本补给直击目标
        // （盾覆盖 → 盾面落点 + bypass=false；未覆盖 → 舰心落点 + bypass=true，与 AOE 同判例口径）
        val bonus = StarfallEchoTuning.finalShotBonusDamage(panel, stacks)
        if (bonus > 0f && engine.isEntityInPlay(ship)) {
            val covered = shieldCovers(ship, hitPoint)
            engine.applyDamage(
                ship, resolveShipDamagePoint(ship, hitPoint), bonus,
                DamageType.ENERGY, 0f,
                !covered, false, source, true,
            )
        }

        // 范围能量结算（存活直击目标与同阵营豁免，摧锋同款裁定）；0 层伤害为 0 直接跳过。
        // 模块舰（空间站）按站去重选举一名代表结算——逐模块全额叠加会把空间站按模块数倍数
        // 击穿（实机判例：750su 半径全覆盖模块群，总伤害 = 单发 × 模块数 + 主舰体）。
        // 舰船遮挡豁免：爆心 → 目标舰心的视线被其他存活舰船碰撞圆截断的目标不受波及。
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
                val covered = (victim as? ShipAPI)?.let { shieldCovers(it, hitPoint) } == true
                val dmgPoint = (victim as? ShipAPI)?.let { resolveShipDamagePoint(it, hitPoint) } ?: Vector2f(hitPoint)
                engine.applyDamage(
                    victim, dmgPoint, damage,
                    DamageType.ENERGY, 0f,
                    victim is ShipAPI && !covered, false, source, true,
                )
            }
        }

        // 后消层
        buff?.consume()

        // 特效恒执行（十字辉星跟随受击点方位 + 星云 + 三角碎片 + 径向电弧）
        StarfallEchoVfx.explosion(engine, hitPoint, stacks, radius, hitFacingDeg(ship, hitPoint))
    }

    /**
     * 受击点方位角（度）：目标舰心 → 命中点。不取 Misc.getAngleInDegrees：Misc 类初始化依赖
     * 游戏运行时（无头/单测直接 ExceptionInInitializerError），LazyLib MathUtils 无等价函数，
     * 此处语义等价于 atan2 直出角度。允许负值（绽放辉星实现侧归一化到 [0,360)）。
     */
    private fun hitFacingDeg(ship: ShipAPI, hitPoint: Vector2f): Float {
        val origin = ship.location ?: return 0f
        return Math.toDegrees(
            kotlin.math.atan2(
                (hitPoint.y - origin.y).toDouble(),
                (hitPoint.x - origin.x).toDouble(),
            ),
        ).toFloat()
    }

    /**
     * 盾覆盖判定（七星/辉星/摧锋同名实现同型注记）：盾开启且爆心在盾弧内。
     * 覆盖时 bypassShields=false（尊重护盾）；未覆盖时必须 true（实机判例：盾关闭的
     * 带盾舰船 bypass=false 全额无伤害）。
     */
    private fun shieldCovers(ship: ShipAPI, explosionPoint: Vector2f): Boolean {
        val shield = ship.shield ?: return false
        return shield.isOn && shield.isWithinArc(explosionPoint)
    }

    /**
     * 舰船伤害落点（七星/辉星/摧锋同名实现同型注记）：盾覆盖 → 盾面落点；未覆盖 → 恒舰心
     * （实机判例：脚本 applyDamage 的界内边缘点恒 0 伤害，舰心点正常；落点仅影响
     * 装甲格选择与浮字位置，不影响伤害量）。
     */
    private fun resolveShipDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f {
        val shield = ship.shield
        if (shield != null && shield.isOn && shield.isWithinArc(explosionPoint)) {
            val shieldLoc = shield.location ?: return Vector2f(ship.location)
            val radius = shield.radius
            if (radius <= 0f) return Vector2f(ship.location)
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
        return Vector2f(ship.location)
    }

    /** AOE 目标分配结果：[regular] 逐个全额结算；[stationRepresentatives] 每座空间站一名代表。 */
    internal data class AoEVictimPlan(
        val regular: List<CombatEntityAPI>,
        val stationRepresentatives: List<ShipAPI>,
    )

    /**
     * 模块舰（空间站）按站选举（纯逻辑，供单元测试直接驱动）：
     * 同一站（模块的 parentStation 组 + 模块舰主舰体自身）只保留距爆心最近的成员作为
     * 结算代表；成员中心距必须 ≤ [radius]——CombatUtils.getEntitiesWithinRange 的粗筛
     * 把碰撞半径计入判定，空间站巨模块会把判定圈虚扩近一倍，此处按中心距复判。
     * 非模块舰目标与导弹原样进 [AoEVictimPlan.regular]。
     */
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

    /**
     * AOE 遮挡船收集：存活、非残骸、非相位的舰船（含被直击船、不分阵营——
     * 爆炸的视线遮挡是物理判定，同阵营船体一样挡爆炸）。
     */
    private fun collectOcclusionBlockers(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { it != null && it.isAlive && !it.isHulk && !it.isPhased }

    /** 单个目标的遮挡圆面：剔除目标自身与同站成员（同一座模块舰互为整体，不互相遮挡）。 */
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

    /** 两船是否同属一座模块舰（模块 parentStation 组 + 主舰体自身；同站成员不互相遮挡）。 */
    internal fun isSameStationGroup(a: ShipAPI, b: ShipAPI): Boolean {
        if (a === b) return true
        val rootA = a.parentStation ?: if (a.isShipWithModules) a else null
        val rootB = b.parentStation ?: if (b.isShipWithModules) b else null
        return rootA != null && rootA === rootB
    }

    /** 遮挡圆（爆心 → 目标舰心线段与舰船碰撞圆的纯几何判定输入）。 */
    internal data class BlockerCircle(val center: Vector2f, val radius: Float)

    /**
     * 舰船遮挡判定（纯函数，供单元测试直接驱动）：爆心 [from] 到目标舰心 [to] 的线段被任一
     * 遮挡圆截断即视为被船体遮挡（完全遮挡免伤）。碰撞圆按 [OCCLUSION_RADIUS_FRACTION] 收敛：
     * 碰撞半径本身含视觉余量，且护盾命中时爆心恒在直击船碰撞圆表面——不收敛时近侧目标的
     * 线段端点恰压圆面（圆心到线段的最短距离恒为整半径）会被误判遮挡。
     * 爆心落在某遮挡船收敛圆内部时（船体命中的直击船自身，宽扁舰体侧向命中爆心可深入圆内），
     * 该船不构成遮挡——否则端点钳制会让半径内所有目标被静默免伤。
     */
    internal fun isOccluded(from: Vector2f, to: Vector2f, blockers: List<BlockerCircle>): Boolean =
        blockers.any { blocker ->
            val radius = blocker.radius * OCCLUSION_RADIUS_FRACTION
            val ox = from.x - blocker.center.x
            val oy = from.y - blocker.center.y
            if (ox * ox + oy * oy <= radius * radius) return@any false
            segmentIntersectsCircle(from, to, blocker.center, radius)
        }

    /** 线段-圆相交（纯函数）：圆心到线段（端点钳制）的最短距离平方 ≤ 半径平方即相交。 */
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
        /** 遮挡判定碰撞圆收敛系数（消除爆心压圆面的近侧误判，见 [isOccluded]）。 */
        private const val OCCLUSION_RADIUS_FRACTION = 0.9f
    }
}
