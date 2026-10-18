package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.random.Random.Default.nextDouble
import kotlin.random.Random.Default.nextFloat

/**
 * 引力坍缩炮：命中持续效果（坍缩扭曲 + 周期性范围高爆伤害 + 穿甲合并结算 + 机动抑制）。
 *
 * 机制口径（weapon_data tooltip 文案双向绑定）：
 * - 每 [GravityCollapseOnHitConfig.tickInterval] 秒在光束终点位置（可能被相位舰截断，见
 *   [GravityCollapsePhaseBeamBlocker]）的固定半径 [GravityCollapseOnHitConfig.aoeRadius] 内造成
 *   “面板总伤害 × tick 间隔 × 难度缩放比例 + 目标当前船体值 × 难度缩放比例”的范围高爆伤害
 *   （半径内全额，无边缘衰减；船体值附加部分与脉冲合并为同一结算实例）；
 * - 对战机的结算伤害翻倍（2026-10 裁定；穿甲打击强度同样基于翻倍后的单次结算伤害）；
 * - 命中装甲或船体的目标：穿甲生效——打击强度 = 单次结算伤害 × 档位穿甲倍率
 *   （大/中/小/PD = 5/4/3/2，固定口径不随难度缩放），按原版装甲公式反解输入伤害后
 *   与主结算合并为单次 applyDamage（装甲减伤只结算一次，战斗日志只跳一次数字），
 *   并施加“引力抑制”——最大航速与机动性降低，持续数秒；
 * - 命中护盾的目标只结算护盾伤害，不触发穿甲与机动抑制；
 * - 相位中的舰船（isPhased）既截断光束也承受伤害：结算伤害按
 *   [GravityCollapseOnHitConfig.phasedDamageRatio] 折算（2026-10 裁定；原版 applyDamage
 *   不检查相位状态，相位免疫仅为碰撞层行为，程序化结算可正常生效）；
 * - 伤害比例/抑制数值受难度系数线性缩放（玩家来源按我方档位系数映射，默认砺刃 v2），穿甲倍率不缩放；
 * - 友方豁免（2026-09-26 裁定）：AOE 波及友军大舰属机制设计（affectAlliesAndNeutral=true），
 *   但**友方战机与导弹一律豁免**（体量小、价值密，被波及不符合预期），与友军开关无关；
 * - 非导弹射弹（DamagingProjectileAPI 且非 MissileAPI）不参与 AOE 结算——对飞行中的弹道射弹
 *   applyDamage 会触发其 onHit 链路（实机曾因此触发其他模组 NPE），且普通射弹本就不应被伤害。
 */
internal class GravityCollapseOnHitHandler(
    private val config: GravityCollapseOnHitConfig,
) {

    companion object {
        private val log = Global.getLogger(GravityCollapseOnHitHandler::class.java)

        // 盾面吸附容差：点略微在盾外也认为被盾覆盖（避免浮点/采样误差导致“看起来打到盾但实际扣船体”）。
        private const val SHIELD_SNAP_EPS = 12f

        /** 高爆伤害对装甲的克制倍率（与原版口径一致）。 */
        private const val HE_VS_ARMOR_MULT = 2f

        /** 对战机的结算伤害倍率（2026-10 裁定：对战机伤害翻倍）。 */
        private const val FIGHTER_DAMAGE_MULT = 2f
    }

    private val hitCollapseInterval = IntervalUtil(config.tickInterval, config.tickInterval)
    private val extraDamageInterval = IntervalUtil(config.tickInterval, config.tickInterval)
    private var wasHittingLastFrame = false

    /** 难度解析结果（一次开火周期内不变；reset 时清空以便下一轮重新解析）。 */
    private var resolved: GravityCollapseDifficulty.ResolvedValues? = null

    fun reset() {
        hitCollapseInterval.forceIntervalElapsed()
        extraDamageInterval.forceIntervalElapsed()
        wasHittingLastFrame = false
        resolved = null
    }

    fun advance(
        engine: CombatEngineAPI,
        amount: Float,
        weapon: WeaponAPI,
        beam: BeamAPI?,
        /**
         * 本帧光束实际生效终点（AOE 落点与坍缩扭曲位置）。
         * 调用方负责做相位舰截断（[GravityCollapsePhaseBeamBlocker]），通常为 beam.to 或最近的相位遮挡交点。
         */
        aoePoint: Vector2f,
        /**
         * 面板总伤害（burstDamage 口径，用于换算 tick 伤害，特效秒伤 = 面板总伤害 × 伤害比例）。
         * 通常传：weapon.damage.damage × ((chargeup + chargedown) × 0.333 + burstDuration)（已包含技能/改装等加成）。
         */
        panelDamage: Float,
    ) {
        if (beam == null) {
            if (wasHittingLastFrame) {
                hitCollapseInterval.forceIntervalElapsed()
                extraDamageInterval.forceIntervalElapsed()
            }
            wasHittingLastFrame = false
            return
        }

        val target = beam.damageTarget

        val isHitting = target != null
        if (config.requireDamageTarget && !isHitting) {
            if (wasHittingLastFrame) {
                hitCollapseInterval.forceIntervalElapsed()
                extraDamageInterval.forceIntervalElapsed()
            }
            wasHittingLastFrame = false
            return
        }
        wasHittingLastFrame = true

        val point = Vector2f(aoePoint)

        // 1) 命中点持续坍缩扭曲
        hitCollapseInterval.advance(amount)
        if (hitCollapseInterval.intervalElapsed()) {
            spawnSustainedHitCollapseDistortion(engine, point)
        }

        // 2) 周期性范围高爆伤害：每 tick 一次
        extraDamageInterval.advance(amount)
        if (!extraDamageInterval.intervalElapsed()) return

        val values = resolved ?: resolveDifficulty(weapon).also { resolved = it }
        val tickDamageBase = (panelDamage.coerceAtLeast(0f) * config.tickInterval * values.aoeDamageRatio)
            .coerceAtLeast(0f)
        if (tickDamageBase <= 0f) return

        val source = weapon.ship
        val owner = source?.owner
        val radius = config.aoeRadius.coerceAtLeast(1f)

        if (config.affectNonShips) {
            val entities = CombatUtils.getEntitiesWithinRange(point, radius)
            for (other in entities) {
                if (other == null) continue
                if (source != null && other === source) continue

                // 普通弹道射弹不可被伤害：对飞行中的 DamagingProjectileAPI（非导弹）applyDamage 会触发
                // 其 onHit 链路（实机曾因此触发其他模组 onHit NPE），直接跳过；导弹保留——AOE 杀伤敌方导弹是预期行为。
                if (other is DamagingProjectileAPI && other !is MissileAPI) continue

                if (!config.affectAlliesAndNeutral && owner != null) {
                    val otherOwner = other.owner
                    if (otherOwner == owner) continue
                }

                // 友方战机与导弹豁免（2026-09-26 裁定）：AOE 波及友军大舰属机制设计，
                // 但友方战机/导弹体量小、价值密，被波及不符合预期——无论 affectAlliesAndNeutral 如何都跳过
                if (owner != null && other.owner == owner &&
                    (other is MissileAPI || (other as? ShipAPI)?.isFighter == true)
                ) continue

                val ship = other as? ShipAPI
                if (ship != null && ship.isHulk && !config.affectHulks) continue

                val loc = other.location
                val cr = other.collisionRadius

                // 用“到外壳/实体边界的最短距离”做范围判定，避免大型目标被误判为离得很远。
                val distToSurface = (MathUtils.getDistance(point, loc) - cr).coerceAtLeast(0f)
                if (distToSurface > radius) continue

                applyTickToEntity(engine, source, other, point, tickDamageBase, values)
            }
        } else {
            for (other in engine.ships) {
                if (source != null && other === source) continue
                if (!config.affectAlliesAndNeutral && owner != null && other.owner == owner) continue
                if (other.isHulk && !config.affectHulks) continue

                // 用“到外壳的最短距离”做范围判定，避免大型舰被误判为离得很远
                val distToHull = (MathUtils.getDistance(point, other.location) - other.collisionRadius).coerceAtLeast(0f)
                if (distToHull > radius) continue

                applyTickToEntity(engine, source, other, point, tickDamageBase, values)
            }
        }

        spawnExtraHitTickFx(engine, point)
    }

    /**
     * 对单个范围内实体结算一次 tick，全程只有一次 applyDamage：
     * - 护盾覆盖 → 只结算护盾伤害（不触发穿甲与机动抑制）；
     * - 命中装甲 → 以「单次结算伤害 × 穿甲倍率 × HE 对装甲倍率」为打击强度反解输入伤害
     *   （[GravityCollapseArmorPiercing.solvePiercedInputDamage]），一次调用完成穿甲结算；
     * - 命中结构 / 非舰船目标 → 无需反解，直接单次结算；装甲耗尽格仍按原版最低装甲下限
     *   （armorRating × minArmorFraction）反解——原版引擎对耗尽格同样保留下限减伤。
     *
     * 每次 tick 的结算伤害 = 面板折算脉冲 + 目标当前船体值 × [GravityCollapseOnHitConfig.hullDamageRatio]
     * （2026-10 裁定，附加部分与脉冲合并为同一结算实例，护盾命中同样结算）；
     * SIC「武备升级」激活时附加部分改按目标最大船体值计（[GravityCollapseDifficulty.hullDamageBasis]）；
     * 目标为战机时结算伤害翻倍（穿甲打击强度基于翻倍后的值）；
     * 目标处于相位中时结算伤害按 [GravityCollapseDifficulty.ResolvedValues.phasedDamageRatio] 折算
     * （相位舰同时承担光束遮挡与按比例承伤两个角色）。
     */
    private fun applyTickToEntity(
        engine: CombatEngineAPI,
        source: ShipAPI?,
        other: CombatEntityAPI,
        point: Vector2f,
        damage: Float,
        values: GravityCollapseDifficulty.ResolvedValues,
    ) {
        val ship = other as? ShipAPI
        val shieldCovered = ship?.let { shieldCovers(it, point) } ?: false

        // 关键：若目标有盾且该点被盾覆盖，则把伤害落点吸附到盾面，避免穿透到装甲/船体。
        val applyPoint = if (ship != null && shieldCovered) {
            resolveShieldedDamagePoint(ship, point)
        } else {
            point
        }

        // 船体附加伤害基准：SIC「武备升级」激活时取目标最大船体值，否则取当前船体值
        //（GravityCollapseDifficulty.hullDamageBasis；标记由 astd-campaign 技能写入来源舰 customData）。
        val armamentUpgrade = SicWeaponUpgradeFlags.isArmamentUpgradeActive(source)
        var totalDamage = damage +
                GravityCollapseDifficulty.hullDamageBasis(other.hitpoints, other.maxHitpoints, armamentUpgrade) *
                values.hullDamageRatio
        // 对战机伤害翻倍（2026-10 裁定）；穿甲打击强度同样基于翻倍后的单次结算伤害。
        if (ship != null && ship.isFighter) totalDamage *= FIGHTER_DAMAGE_MULT
        // 相位中的舰船按比例折算承伤（2026-10 裁定）：原版相位免疫仅为碰撞层行为，
        // applyDamage 不检查相位状态，程序化结算可正常生效。
        if (ship != null && ship.isPhased) totalDamage *= values.phasedDamageRatio

        // 命中装甲/船体：反解输入伤害，把穿甲与主结算合并为一次 applyDamage
        // （装甲耗尽格按原版最低装甲下限反解，不跳过）。
        val inputDamage = if (ship != null && !ship.isHulk && !shieldCovered) {
            resolvePiercedInputDamage(ship, point, totalDamage)
        } else {
            totalDamage
        }

        if (inputDamage > 0f) {
            engine.applyDamage(
                other,
                applyPoint,
                inputDamage,
                DamageType.HIGH_EXPLOSIVE,
                0f,
                false,
                false,
                source,
                true,
            )
        }

        if (ship == null || ship.isHulk || shieldCovered) return

        // 命中装甲/船体：施加机动/航速抑制（刷新持续）。
        GravityCollapseMobilityDebuff.apply(
            engine = engine,
            source = source,
            target = ship,
            reduction = values.mobilityReduction,
            duration = values.mobilityDuration,
        )
    }

    /**
     * 反解合并穿甲后的 applyDamage 输入伤害（口径见 [GravityCollapseArmorPiercing]）。
     * 命中点不在装甲格内时无需反解，直接返回原结算伤害；装甲耗尽格不跳过反解——
     * 原版引擎的有效装甲恒有下限 `max(格装甲, armorRating × minArmorFraction)`，
     * 耗尽格按该下限反解（[GravityCollapseArmorPiercing.effectiveArmor] 的 max 语义）。
     */
    private fun resolvePiercedInputDamage(target: ShipAPI, point: Vector2f, totalDamage: Float): Float {
        if (totalDamage <= 0f) return totalDamage

        val grid = target.armorGrid ?: return totalDamage
        val cell = grid.getCellAtLocation(point) ?: return totalDamage
        if (cell.size < 2) return totalDamage
        val armor = grid.getArmorValue(cell[0], cell[1]).coerceAtLeast(0f)

        val stats = target.mutableStats
        val effectiveArmor = GravityCollapseArmorPiercing.effectiveArmor(
            cellArmor = armor,
            minArmor = grid.armorRating * stats.minArmorFraction.modifiedValue,
            effectiveArmorMult = stats.effectiveArmorBonus.mult,
        )
        val maxReduction = stats.maxArmorDamageReduction.modifiedValue

        return GravityCollapseArmorPiercing.solvePiercedInputDamage(
            damage = totalDamage,
            pierceMult = config.armorPierceDamageMult,
            effectiveArmor = effectiveArmor,
            maxReduction = maxReduction,
            typeMultVsArmor = HE_VS_ARMOR_MULT,
        )
    }

    /** 难度解析（委托 [GravityCollapseDifficulty] 唯一入口；结果缓存至 reset）。 */
    private fun resolveDifficulty(weapon: WeaponAPI): GravityCollapseDifficulty.ResolvedValues =
        GravityCollapseDifficulty.resolve(DifficultyTuningImpl, weapon.ship?.owner, config, weapon.id)

    /** 判定 [point] 是否被 [ship] 的护盾覆盖（与盾面吸附使用同一容差）。 */
    private fun shieldCovers(ship: ShipAPI, point: Vector2f): Boolean {
        val shield = ship.shield ?: return false
        if (shield.type == ShieldAPI.ShieldType.NONE) return false
        if (!shield.isOn) return false
        if (!shield.isWithinArc(point)) return false
        val sl = shield.location ?: return false
        val r = shield.radius
        if (r <= 0f) return false
        return MathUtils.getDistance(point, sl) <= r + SHIELD_SNAP_EPS
    }

    /**
     * 对于带盾舰船：若 [explosionPoint] 被盾覆盖，则返回盾面上的落点（用于 applyDamage）。
     * 这样 AOE 不会“看起来打到盾但实际穿透扣船体”。
     */
    private fun resolveShieldedDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f {
        val shield = ship.shield ?: return explosionPoint
        if (!shield.isOn) return explosionPoint

        if (!shield.isWithinArc(explosionPoint)) return explosionPoint

        val sl = shield.location ?: return explosionPoint

        val r = shield.radius
        if (r <= 0f) return explosionPoint

        val d = MathUtils.getDistance(explosionPoint, sl)
        if (d > r + SHIELD_SNAP_EPS) return explosionPoint

        val ang = Misc.getAngleInDegrees(sl, explosionPoint)
        return MathUtils.getPointOnCircumference(sl, r, ang)
    }

    private fun spawnSustainedHitCollapseDistortion(engine: CombatEngineAPI, point: Vector2f) {
        BoxUtilCombatVfx.ensureReady(engine)

        DistortionEntity().apply {
            setGlobalTimer(0.02f, 0.06f, 0.25f)

            setInnerIn(0.35f, 0.35f)
            setInnerFull(0.35f, 0.35f)
            setInnerOut(0.35f, 0.35f)

            innerHardness = 0.90f
            ringHardness = 0.70f

            val vfxS = config.vfxScale.coerceIn(0.35f, 2.25f)
            setSizeIn(config.aoeRadius * 0.35f * vfxS, config.aoeRadius * 0.35f * vfxS)
            setSizeFull(config.aoeRadius * 0.7f * vfxS, config.aoeRadius * 0.7f * vfxS)
            setSizeOut(config.aoeRadius * vfxS, config.aoeRadius * vfxS)

            powerIn = 0.4f
            powerFull = 0.8f
            powerOut = 0f

            setLocation(point)

            val state = BoxUtilCombatVfx.addEntity(engine, this)
            if (state != 0) {
                log.warn("[ASTD] 引力坍缩炮：命中坍缩扭曲 addEntity 失败（state=$state），本次视觉缺席")
                delete()
            }
        }
    }

    private fun spawnExtraHitTickFx(engine: CombatEngineAPI, point: Vector2f) {
        // 小范围红色爆炸烟雾 + 红色闪光（每 tick 一次）
        val vis = 1.5f
        val vfxS = config.vfxScale.coerceIn(0.35f, 2.25f)

        val color = Color(255, 35, 35, 55)
        repeat(5) {
            engine.addNebulaParticle(
                point,
                Vector2f(nextDouble(-10.0, 10.0).toFloat(), nextDouble(-10.0, 10.0).toFloat()),
                135f * vis * vfxS,
                3f * nextFloat(),
                0.1f,
                0.2f,
                0.8f,
                color,
                true
            )
        }
    }

}
