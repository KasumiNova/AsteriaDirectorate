package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.api.combat.StellarMrmStrike
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * [StellarMrmStrike] 的无状态实现（规格 08 §2.2）：撞线者死 → 猎机本能 → 辉星爆炸
 * （爆炸恒执行）的一次性结算执行体。数值全部经 [StellarMrmStrikeMath] 与 [StellarMrmDifficulty]。
 *
 * 玩家可见反馈（规格 08 §2.3 机制可视化铁律）：增伤/EMP/AOE 均 `showDamageFloaty=true` 浮字；
 * 战机全部武器 EMP 逐武器一道紫色电弧（`spawnEmpArcVisual`，Vector2f 端点重载——WeaponAPI
 * 非 CombatEntityAPI 不能作电弧锚点实体，规格 §0 已核实）；辉星爆炸为模组通用裂隙爆炸
 * [RiftExplosionVfx]（需求定案：与七星同款裂隙洪流发射极式裂隙爆炸，60% 相对缩放
 * 保持辉星较小观感）。
 *
 * 脚本 `applyDamage` 落点与 bypassShields 走七星实机判例同款口径
 * （[cn.kasuminova.astd.combat.effect.arc.SevenStarsDamageHandler] 注记：盾覆盖 → 盾面落点 +
 * bypass=false；未覆盖 → 舰心落点 + bypass=true，否则盾关闭的带盾舰船全额无伤害、
 * 界内边缘点恒 0）——规格字面调用参数与该判例冲突，按设计意图「爆炸恒有范围伤害」落判例口径。
 * 战机 EMP 不走引擎伤害通路：三轮烟测 + 探针实证 0.98 的 emp-only applyDamage 与
 * spawnEmpArc 对战机武器组件均无法送达，改走逐武器 setCurrHealth 直接结算
 * （原版组件熄火/自修终态一致，见步骤 2b 注记）。
 */
object StellarMrmStrikeImpl : StellarMrmStrike {
    private val log = Global.getLogger(StellarMrmStrikeImpl::class.java)

    /** 弹体 maxHitpoints ≤ 0 的每引擎 WARN 去重键（engine.customData，规格 08 §2.4）。 */
    private const val WARN_KEY_ZERO_MAX_HP = "astd_stellar_mrm_warn_zero_maxhp"

    /** 逐武器 EMP 电弧粗细（su）。 */
    private const val EMP_ARC_THICKNESS = 6f

    /** 辉星裂隙爆炸相对缩放（裂隙组件默认半径 × 0.6，保持原「辉星 60% 缩放」观感口径）。 */
    private const val RIFT_RADIUS_SCALE = 0.6f

    /** EMP 电弧缘色（LENS 紫）。 */
    private val EMP_ARC_FRINGE = Color(170, 110, 255)

    /** EMP 电弧芯色（白）。 */
    private val EMP_ARC_CORE = Color(255, 255, 255)

    /**
     * 默认 AOE 粗筛：LazyLib 空间网格查询（GCP/七星已验证路径）。抽成可注入函数值供
     * 桩引擎单测注入候选清单（对齐 ConeImpactHandler 同型注记）。
     */
    internal val LAZYLIB_COARSE_QUERY: (Vector2f, Float) -> List<CombatEntityAPI> =
        { origin, range -> CombatUtils.getEntitiesWithinRange(origin, range) }

    override fun strike(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
    ) = strike(engine, projectile, target, point, shieldHit, LAZYLIB_COARSE_QUERY)

    /**
     * 结算执行体（[coarseQuery] 仅测试注入；游戏内恒走 [LAZYLIB_COARSE_QUERY]）。
     * 顺序：撞线者死 → 猎机本能 → 辉星爆炸（恒执行）。
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
            log.warn("辉星命中结算面板值异常（$panel），属配置错误，附加机制全部跳过: spec=${projectile.projectileSpecId}")
            return
        }
        val source = projectile.source
        val owner = projectile.owner
        val fBonus = StellarMrmDifficulty.resolve(StellarMrmDifficulty.FIGHTER_BONUS, owner)
        val wEmp = StellarMrmDifficulty.resolve(StellarMrmDifficulty.WEAPON_EMP, owner)
        val expMult = StellarMrmDifficulty.resolve(StellarMrmDifficulty.EXPLOSION_MULT, owner)
        val h = StellarMrmDifficulty.resolve(StellarMrmDifficulty.LINE_CROSS_H, owner)

        // 难度取值遥测（dev 自动化烟测证据：敌版三档观测面，按攻击方归属分键）。
        val ownerSuffix = if (owner == 0) TELE_OWNER_PLAYER else TELE_OWNER_ENEMY
        engine.customData[TELE_LAST_F_BONUS + ownerSuffix] = fBonus
        engine.customData[TELE_LAST_W_EMP + ownerSuffix] = wEmp
        engine.customData[TELE_LAST_EXP_MULT + ownerSuffix] = expMult
        if (shieldHit) bump(engine, TELE_SHIELD_HITS)

        // ---- 步骤 1：撞线者死（隐性机制，不进文案，视觉即爆炸本身）----
        if (target is MissileAPI && target.owner != owner && engine.isEntityInPlay(target)) {
            val projMaxHp = projectile.maxHitpoints
            if (projMaxHp <= 0f && engine.customData[WARN_KEY_ZERO_MAX_HP] != true) {
                engine.customData[WARN_KEY_ZERO_MAX_HP] = true
                log.warn("辉星弹体 maxHitpoints 异常（$projMaxHp），撞线者死机制失效（阈值恒 0）: spec=${projectile.projectileSpecId}")
            }
            val threshold = StellarMrmStrikeMath.lineCrossThreshold(projMaxHp, h)
            if (StellarMrmStrikeMath.shouldCross(target.hitpoints, threshold)) {
                engine.removeEntity(target)
                bump(engine, TELE_LINE_CROSS)
            }
        }

        // ---- 步骤 2：猎机本能（战机机体命中：增伤 + 全部武器 EMP）----
        if (target is ShipAPI && target.isFighter && !shieldHit && !target.isHulk) {
            val covered = shieldCovers(target, point)
            val dmgPoint = resolveShipDamagePoint(target, point)
            // a. 增伤：能量伤害浮字（showDamageFloaty=true，玩家可见数字自然变大）
            engine.applyDamage(
                target, dmgPoint,
                StellarMrmStrikeMath.fighterBonusDamage(panel, fBonus),
                DamageType.ENERGY, 0f, !covered, false, source, true,
            )
            bump(engine, TELE_BONUS_HITS)
            // b. 全部武器 EMP：逐存活武器直接扣减组件耐久（weapon.setCurrHealth）。三轮烟测
            //    + 探针实证：applyDamage emp-only 与 spawnEmpArc 两条原版 EMP 通路对战机武器
            //    组件均无法送达（30 次结算：武器耐久比恒 1.0、熄火时长恒 0；同轮船体血量比
            //    0.08 证明脚本伤害整体可达，唯组件通道不生效）——规格 §0 表格的 applyDamage
            //    落位判断按实机证据修正。扣至 0 后由原版组件机制完成熄火与自修，与 EMP
            //    命中终态一致；每武器一道紫色电弧视觉照走下方 spawnEmpArcVisual 循环。
            val empDamage = StellarMrmStrikeMath.weaponEmpDamage(panel, wEmp)
            for (weapon in target.allWeapons) {
                if (weapon.isDisabled) continue
                weapon.setCurrHealth(maxOf(0f, weapon.currHealth - empDamage))
            }
            bump(engine, TELE_EMP_HITS)
            // c. 逐武器电弧视觉：存活武器槽位各锚一道紫色电弧（allWeapons 为空合法零次循环）
            for (weapon in target.allWeapons) {
                if (weapon.isDisabled) continue
                val weaponLoc = weapon.location ?: continue
                engine.spawnEmpArcVisual(
                    point, target, weaponLoc, target,
                    EMP_ARC_THICKNESS, EMP_ARC_FRINGE, EMP_ARC_CORE,
                )
                bump(engine, TELE_EMP_ARCS)
            }
        }

        // ---- 步骤 3：辉星爆炸（任意撞击恒触发；无有效受害目标时仅 VFX，合法）----
        val expDamage = StellarMrmStrikeMath.explosionDamage(panel, expMult)
        for (victim in coarseQuery(point, StellarMrmDifficulty.EXPLOSION_RADIUS)) {
            if (victim === projectile) continue
            if (victim.owner == owner) continue
            if (victim !is ShipAPI && victim !is MissileAPI) continue
            if (victim is ShipAPI && (victim.isHulk || victim.isPhased)) continue
            if (victim is MissileAPI && victim.isExpired) continue
            // 直接命中目标仅在已死（撞线移除/本帧击毁）时豁免 AOE；存活时与区域内目标同额
            // （对齐七星「直击与区域同额」裁定口径）。
            if (victim === target && !engine.isEntityInPlay(victim)) continue

            val covered = (victim as? ShipAPI)?.let { shieldCovers(it, point) } == true
            val dmgPoint = (victim as? ShipAPI)?.let { resolveShipDamagePoint(it, point) } ?: Vector2f(point)
            engine.applyDamage(
                victim, dmgPoint, expDamage,
                DamageType.ENERGY, 0f,
                victim is ShipAPI && !covered, false, source, true,
            )
            bump(engine, TELE_AOE_HITS)
            if (victim is ShipAPI && !victim.isFighter) bump(engine, TELE_AOE_SHIP_HITS)
        }

        // VFX 恒执行：裂隙爆炸（模组通用组件，蓝色族，60% 相对缩放）。
        RiftExplosionVfx.riftExplosion(engine, point, radius = RiftExplosionVfx.DEFAULT_RADIUS * RIFT_RADIUS_SCALE)
        bump(engine, TELE_EXPLOSIONS)
    }

    /**
     * 盾覆盖判定（七星同名实现同型注记）：盾开启且 [explosionPoint] 在盾弧内。
     * 覆盖时 bypassShields=false（尊重护盾）；未覆盖时必须 true（实机判例：盾关闭的
     * 带盾舰船 bypass=false 全额无伤害）。
     */
    private fun shieldCovers(ship: ShipAPI, explosionPoint: Vector2f): Boolean {
        val shield = ship.shield ?: return false
        return shield.isOn && shield.isWithinArc(explosionPoint)
    }

    /**
     * 舰船伤害落点（七星同名实现同型注记）：盾覆盖 → 盾面落点；未覆盖 → 恒舰心
     * （实机判例：脚本 applyDamage 的界内边缘点恒 0 伤害，舰心点正常；落点仅影响
     * 装甲格选择与浮字位置，不影响伤害量）。
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

    // ---- dev 自动化烟测遥测键（engine.customData 证据计数，HeavyIonPulseVfx 同型惯例） ----

    /** 撞线者死触发次数（removeEntity 必定摧毁）。 */
    const val TELE_LINE_CROSS = "astd_stellar_mrm_tele_line_cross"

    /** 战机增伤命中次数。 */
    const val TELE_BONUS_HITS = "astd_stellar_mrm_tele_bonus_hits"

    /** 战机全部武器 EMP 结算次数。 */
    const val TELE_EMP_HITS = "astd_stellar_mrm_tele_emp_hits"

    /** 逐武器电弧生成总数（视觉锚到各存活槽位）。 */
    const val TELE_EMP_ARCS = "astd_stellar_mrm_tele_emp_arcs"

    /** 辉星爆炸 AOE 受害目标结算总数。 */
    const val TELE_AOE_HITS = "astd_stellar_mrm_tele_aoe_hits"

    /** 辉星爆炸 AOE 波及舰船（非战机）结算总数（撞击舰船证据面）。 */
    const val TELE_AOE_SHIP_HITS = "astd_stellar_mrm_tele_aoe_ship_hits"

    /** 护盾命中结算次数（撞击护盾爆炸照常证据面）。 */
    const val TELE_SHIELD_HITS = "astd_stellar_mrm_tele_shield_hits"

    /** 辉星爆炸触发次数（VFX 恒执行口径，任意撞击含护盾/残骸）。 */
    const val TELE_EXPLOSIONS = "astd_stellar_mrm_tele_explosions"

    /** 最近一次结算的难度取值（后缀 [_p]/[_e] 按攻击方归属分键）：战机增伤倍率。 */
    const val TELE_LAST_F_BONUS = "astd_stellar_mrm_tele_last_fbonus"

    /** 最近一次结算的难度取值：武器 EMP 倍率。 */
    const val TELE_LAST_W_EMP = "astd_stellar_mrm_tele_last_wemp"

    /** 最近一次结算的难度取值：爆炸倍率。 */
    const val TELE_LAST_EXP_MULT = "astd_stellar_mrm_tele_last_expmult"

    /** 遥测归属后缀：玩家（owner==0）。 */
    const val TELE_OWNER_PLAYER = "_p"

    /** 遥测归属后缀：非玩家。 */
    const val TELE_OWNER_ENEMY = "_e"

    /** 遥测计数自增（缺省 0 起）。 */
    private fun bump(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }
}
