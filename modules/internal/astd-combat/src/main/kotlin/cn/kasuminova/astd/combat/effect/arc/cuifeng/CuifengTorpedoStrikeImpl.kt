package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.api.combat.CuifengTorpedoStrike
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * [CuifengTorpedoStrike] 的无状态实现（blue/30-superlative.md §机制）：
 * 直击自适应增伤 → 硬辐推进 → 全额面板 AOE → 特效（恒执行）的一次性结算执行体。
 * 数值全部经 [CuifengTorpedoMath] 与 [CuifengTorpedoDifficulty]。
 *
 * 玩家可见反馈（机制可视化铁律）：自适应增伤并入 `showDamageFloaty=true` 浮字；
 * 硬辐推进为紫色浮字（设计案「显示为紫色值」）；AOE 逐目标浮字；特效见
 * [CuifengTorpedoVfx]（十字辉星 + 星云，恒执行）。
 *
 * 脚本 `applyDamage` 落点与 bypassShields 走七星/辉星实机判例同款口径（盾覆盖 → 盾面落点 +
 * bypass=false；未覆盖 → 舰心落点 + bypass=true，否则盾关闭的带盾舰船全额无伤害、
 * 界内边缘点恒 0）。
 *
 * AOE 口径裁定（设计案「打中造成的伤害本身即为范围伤害，全额面板作用于爆炸范围内所有目标」）：
 * 存活的直击目标豁免 AOE——直击面板已由引擎原生结算，重复计入会让直击目标实吃两倍面板；
 * 直击目标本帧已死（被直击面板击毁）时不再豁免（残骸不参与结算）。
 */
object CuifengTorpedoStrikeImpl : CuifengTorpedoStrike {
    private val log = Global.getLogger(CuifengTorpedoStrikeImpl::class.java)

    /**
     * 默认 AOE 粗筛：LazyLib 空间网格查询（GCP/七星已验证路径）。抽成可注入函数值供
     * 桩引擎单测注入候选清单（对齐 StellarMrmStrikeImpl 同型注记）。
     */
    internal val LAZYLIB_COARSE_QUERY: (Vector2f, Float) -> List<CombatEntityAPI> =
        { origin, range -> CombatUtils.getEntitiesWithinRange(origin, range) }

    /** 硬辐推进紫色浮字色（设计案「显示为紫色值」）。 */
    private val HARD_FLUX_TEXT_COLOR = Color(200, 130, 255)

    override fun strike(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
    ) = strike(engine, projectile, target, point, shieldHit, LAZYLIB_COARSE_QUERY)

    /**
     * 结算执行体（[coarseQuery] 仅测试注入；游戏内恒走 [LAZYLIB_COARSE_QUERY]）。
     * 顺序：直击自适应 → 硬辐推进 → 全额面板 AOE → 特效（恒执行）。
     */
    internal fun strike(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        coarseQuery: (Vector2f, Float) -> List<CombatEntityAPI>,
    ) {
        // ---- 步骤 0：面板 sanitize + 难度取值（唯一入口，命中时取值）----
        val panel = projectile.damageAmount
        if (!panel.isFinite() || panel <= 0f) {
            log.warn("摧锋命中结算面板值异常（$panel），属配置错误，附加机制全部跳过: spec=${projectile.projectileSpecId}")
            return
        }
        val source = projectile.source
        val owner = projectile.owner
        val x = CuifengTorpedoDifficulty.resolve(CuifengTorpedoDifficulty.FLUX_ADAPTIVE_X, owner)
        val y = CuifengTorpedoDifficulty.resolve(CuifengTorpedoDifficulty.HARD_FLUX_Y, owner)
        val dpPct = CuifengTorpedoDifficulty.resolve(CuifengTorpedoDifficulty.DP_PCT, owner)

        // ---- 步骤 1：直击自适应（仅存活舰船目标；战机吃辐能自适应、无舰体自适应）----
        if (target is ShipAPI && !target.isHulk) {
            val fluxTracker = target.fluxTracker
            val maxFlux = fluxTracker.maxFlux
            val fluxLevel = if (maxFlux > 0f) fluxTracker.currFlux / maxFlux else 0f
            val fluxBonus = CuifengTorpedoMath.fluxAdaptiveBonus(panel, x, fluxLevel)

            var hullBonus = 0f
            if (!target.isFighter) {
                val moduleParent = target.parentStation
                val dpSource = moduleParent ?: target
                val hullSize = dpSource.hullSize
                val sizePct = resolveHullSizePct(hullSize, owner)
                val baseline = resolveDpBaseline(hullSize)
                // 基础部署点：不受部署点减免影响（unmodified）；fleetMember 缺失时退回基准值（差值 0）
                val dp = dpSource.fleetMember?.unmodifiedDeploymentPointsCost ?: baseline
                hullBonus = CuifengTorpedoMath.hullAdaptiveBonus(panel, sizePct, dp, baseline, dpPct)
                if (moduleParent != null) hullBonus *= MODULE_BONUS_MULT
            }

            val totalBonus = fluxBonus + hullBonus
            if (totalBonus > 0f) {
                val covered = shieldCovers(target, point)
                val dmgPoint = resolveShipDamagePoint(target, point)
                engine.applyDamage(
                    target, dmgPoint, totalBonus,
                    DamageType.ENERGY, 0f, !covered, false, source, true,
                )
                bump(engine, TELE_ADAPTIVE_HITS)
                engine.customData[TELE_LAST_ADAPTIVE_BONUS + ownerSuffix(owner)] = totalBonus
            }
        }

        // ---- 步骤 2：硬辐推进（命中护盾：强制抬升目标最大辐能 y% 的硬辐能，紫色浮字）----
        if (shieldHit && target is ShipAPI && !target.isHulk) {
            val maxFlux = target.fluxTracker.maxFlux
            if (maxFlux > 0f) {
                val amount = maxFlux * y
                target.fluxTracker.increaseFlux(amount, true)
                engine.addFloatingDamageText(Vector2f(point), amount, HARD_FLUX_TEXT_COLOR, target, source)
                bump(engine, TELE_HARD_FLUX_PUSHES)
                engine.customData[TELE_LAST_HARD_FLUX + ownerSuffix(owner)] = amount
            }
        }

        // ---- 步骤 3：全额面板 AOE（150su；存活直击目标豁免，见类头裁定）----
        for (victim in coarseQuery(point, CuifengTorpedoDifficulty.AOE_RADIUS)) {
            if (victim === projectile) continue
            if (victim.owner == owner) continue
            if (victim !is ShipAPI && victim !is MissileAPI) continue
            if (victim is ShipAPI && (victim.isHulk || victim.isPhased)) continue
            if (victim is MissileAPI && victim.isExpired) continue
            if (victim === target && engine.isEntityInPlay(victim)) continue

            val covered = (victim as? ShipAPI)?.let { shieldCovers(it, point) } == true
            val dmgPoint = (victim as? ShipAPI)?.let { resolveShipDamagePoint(it, point) } ?: Vector2f(point)
            engine.applyDamage(
                victim, dmgPoint, panel,
                DamageType.ENERGY, 0f,
                victim is ShipAPI && !covered, false, source, true,
            )
            bump(engine, TELE_AOE_HITS)
            if (victim is ShipAPI && !victim.isFighter) bump(engine, TELE_AOE_SHIP_HITS)
            // 受害目标身份遥测（实机场景定位 AOE 误伤/豁免口径用）：类名@规格或舰体 id
            engine.customData[TELE_LAST_AOE_VICTIM + ownerSuffix(owner)] =
                "${victim.javaClass.simpleName}@${(victim as? ShipAPI)?.hullSpec?.hullId ?: (victim as? MissileAPI)?.projectileSpecId}"
        }

        // ---- 步骤 4：特效恒执行（十字辉星 ×2 + 爆炸星云 ×10）----
        CuifengTorpedoVfx.spawnImpact(engine, point)
        bump(engine, TELE_IMPACT_VFX)
    }

    /** 舰体等级档位增伤（面板倍率）的难度取值。 */
    private fun resolveHullSizePct(hullSize: ShipAPI.HullSize?, owner: Int): Float = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> CuifengTorpedoDifficulty.HULL_FRIGATE
        ShipAPI.HullSize.DESTROYER -> CuifengTorpedoDifficulty.HULL_DESTROYER
        ShipAPI.HullSize.CRUISER -> CuifengTorpedoDifficulty.HULL_CRUISER
        ShipAPI.HullSize.CAPITAL_SHIP -> CuifengTorpedoDifficulty.HULL_CAPITAL
        else -> null
    }?.let { CuifengTorpedoDifficulty.resolve(it, owner) } ?: 0f

    /** 同级部署点基准（5/10/20/40）；未知等级退回 0（差值增伤全额生效，档位增伤已为 0 的场景不出现）。 */
    private fun resolveDpBaseline(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> CuifengTorpedoDifficulty.DP_BASELINE_FRIGATE
        ShipAPI.HullSize.DESTROYER -> CuifengTorpedoDifficulty.DP_BASELINE_DESTROYER
        ShipAPI.HullSize.CRUISER -> CuifengTorpedoDifficulty.DP_BASELINE_CRUISER
        ShipAPI.HullSize.CAPITAL_SHIP -> CuifengTorpedoDifficulty.DP_BASELINE_CAPITAL
        else -> 0f
    }

    /**
     * 盾覆盖判定（七星/辉星同名实现同型注记）：盾开启且 [explosionPoint] 在盾弧内。
     * 覆盖时 bypassShields=false（尊重护盾）；未覆盖时必须 true（实机判例：盾关闭的
     * 带盾舰船 bypass=false 全额无伤害）。
     */
    private fun shieldCovers(ship: ShipAPI, explosionPoint: Vector2f): Boolean {
        val shield = ship.shield ?: return false
        return shield.isOn && shield.isWithinArc(explosionPoint)
    }

    /**
     * 舰船伤害落点（七星/辉星同名实现同型注记）：盾覆盖 → 盾面落点；未覆盖 → 恒舰心
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

    // ---- dev 自动化烟测遥测键（engine.customData 证据计数，辉星同型惯例） ----

    /** 直击自适应增伤结算次数。 */
    const val TELE_ADAPTIVE_HITS = "astd_cuifeng_tele_adaptive_hits"

    /** 硬辐推进触发次数。 */
    const val TELE_HARD_FLUX_PUSHES = "astd_cuifeng_tele_hard_flux_pushes"

    /** AOE 受害目标结算总数。 */
    const val TELE_AOE_HITS = "astd_cuifeng_tele_aoe_hits"

    /** AOE 波及舰船（非战机）结算总数。 */
    const val TELE_AOE_SHIP_HITS = "astd_cuifeng_tele_aoe_ship_hits"

    /** 命中特效触发次数（VFX 恒执行口径）。 */
    const val TELE_IMPACT_VFX = "astd_cuifeng_tele_impact_vfx"

    /** 最近一次结算的自适应增伤总量（后缀 [_p]/[_e] 按攻击方归属分键）。 */
    const val TELE_LAST_ADAPTIVE_BONUS = "astd_cuifeng_tele_last_adaptive_bonus"

    /** 最近一次硬辐推进量（后缀 [_p]/[_e] 按攻击方归属分键）。 */
    const val TELE_LAST_HARD_FLUX = "astd_cuifeng_tele_last_hard_flux"

    /** 最近一次 AOE 受害目标身份（类名@规格 id，后缀 [_p]/[_e] 按攻击方归属分键）。 */
    const val TELE_LAST_AOE_VICTIM = "astd_cuifeng_tele_last_aoe_victim"

    /** 遥测归属后缀：玩家（owner==0）。 */
    const val TELE_OWNER_PLAYER = "_p"

    /** 遥测归属后缀：非玩家。 */
    const val TELE_OWNER_ENEMY = "_e"

    /** 硬辐推进浮字字号。 */
    private const val HARD_FLUX_TEXT_SIZE = 24f

    /** 模块舰舰体自适应减半系数（设计案「额外伤害减半」）。 */
    private const val MODULE_BONUS_MULT = 0.5f

    /** 遥测归属后缀选择。 */
    private fun ownerSuffix(owner: Int): String = if (owner == 0) TELE_OWNER_PLAYER else TELE_OWNER_ENEMY

    /** 遥测计数自增（缺省 0 起）。 */
    private fun bump(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }
}
