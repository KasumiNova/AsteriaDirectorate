package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
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
import kotlin.math.min
import kotlin.random.Random.Default.nextDouble
import kotlin.random.Random.Default.nextFloat

/**
 * 引力坍缩炮：命中持续效果（坍缩扭曲 + 周期性范围高爆伤害 + 装甲减伤无视 + 机动抑制）。
 *
 * 机制口径（weapon_data tooltip 文案双向绑定）：
 * - 每 [GravityCollapseOnHitConfig.tickInterval] 秒在光束终点位置的一定半径造成
 *   “面板总伤害 × tick 间隔 × 难度缩放比例”的范围高爆伤害（半径内全额，无边缘衰减）；
 * - 命中装甲或船体的目标：穿甲力度生效——计算装甲减伤的伤害值提高至打击伤害 × 难度倍率，
 *   穿透差额以追加伤害形式结算（走原版伤害结算链路，计入装甲/船体伤害计算而非直扣船体），
 *   并施加“引力抑制”——最大航速与机动性降低，持续数秒；
 * - 命中护盾的目标只结算护盾伤害，不触发装甲减伤无视与机动抑制；
 * - 上述比例/时长全部受难度系数线性缩放（玩家来源固定 v2 设计基准）。
 */
internal class GravityCollapseOnHitHandler(
    private val config: GravityCollapseOnHitConfig,
) {

    companion object {
        private val log = Global.getLogger(GravityCollapseOnHitHandler::class.java)

        // 盾面吸附容差：点略微在盾外也认为被盾覆盖（避免浮点/采样误差导致“看起来打到盾但实际扣船体”）。
        private const val SHIELD_SNAP_EPS = 12f

        /** 高爆伤害对装甲的克制倍率（结算装甲减伤无视时与原版口径一致）。 */
        private const val HE_VS_ARMOR_MULT = 2f
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
        intensity: Float,
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

        val t = intensity.coerceIn(0f, 1f)
        val point = Vector2f(beam.to)

        // 1) 命中点持续坍缩扭曲
        hitCollapseInterval.advance(amount)
        if (hitCollapseInterval.intervalElapsed()) {
            spawnSustainedHitCollapseDistortion(engine, point, t)
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

        val radiusMul = lerp(config.aoeRadiusIntensityMinMul, config.aoeRadiusIntensityMaxMul, t)
        val radius = (config.aoeRadiusBase * radiusMul).coerceAtLeast(1f)

        if (config.affectNonShips) {
            val entities = CombatUtils.getEntitiesWithinRange(point, radius)
            for (other in entities) {
                if (other == null) continue
                if (source != null && other === source) continue

                if (!config.affectAlliesAndNeutral && owner != null) {
                    val otherOwner = other.owner
                    if (otherOwner == owner) continue
                }

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

        spawnExtraHitTickFx(engine, point, t)
    }

    /**
     * 对单个范围内实体结算一次 tick：
     * 护盾覆盖 → 只结算护盾伤害；命中装甲/船体 → 追加装甲减伤无视与机动抑制。
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

        engine.applyDamage(
            other,
            applyPoint,
            damage,
            DamageType.HIGH_EXPLOSIVE,
            0f,
            false,
            false,
            source,
            true,
        )

        if (ship == null || ship.isHulk || shieldCovered) return

        // 命中装甲/船体：穿甲力度结算（计算装甲减伤的伤害值提高至打击伤害 × 倍率，穿透差额追加）。
        applyArmorPiercing(engine, source, ship, point, damage, values.armorPierceMult)

        // 命中装甲/船体：施加机动/航速抑制（刷新持续）。
        GravityCollapseMobilityDebuff.apply(
            engine = engine,
            source = source,
            target = ship,
            reduction = values.mobilityReduction,
            duration = values.mobilityDuration,
        )
    }

    /** 难度解析（委托 [GravityCollapseDifficulty] 唯一入口；结果缓存至 reset）。 */
    private fun resolveDifficulty(weapon: WeaponAPI): GravityCollapseDifficulty.ResolvedValues =
        GravityCollapseDifficulty.resolve(DifficultyTuningImpl, weapon.ship?.owner, config, weapon.id)

    /**
     * 穿甲力度结算：原版最终伤害倍率为 `1 - min(h / (h + armor), maxArmorDamageReduction)`，
     * 其中打击强度 h = 伤害 × 类型倍率（高爆对装甲 ×2）。本效果把打击强度替换为
     * `伤害 × pierceMult`，两口径的伤害差值 `dmg_eff × (multPierced - multNormal)` 以追加高爆伤害
     * 形式结算（走原版 applyDamage 链路，计入装甲/船体伤害计算而非直扣船体值）；
     * 命中点装甲已耗尽时不存在减伤，无追加。
     */
    private fun applyArmorPiercing(
        engine: CombatEngineAPI,
        source: ShipAPI?,
        target: ShipAPI,
        point: Vector2f,
        baseDamage: Float,
        pierceMult: Float,
    ) {
        if (baseDamage <= 0f || pierceMult <= 0f) return

        val grid = target.armorGrid ?: return
        val cell = grid.getCellAtLocation(point) ?: return
        if (cell.size < 2) return
        val armor = grid.getArmorValue(cell[0], cell[1]).coerceAtLeast(0f)
        if (armor <= 0.01f) return

        val effectiveDamage = baseDamage * HE_VS_ARMOR_MULT
        val maxReduction = target.mutableStats.maxArmorDamageReduction.modifiedValue.coerceIn(0f, 1f)
        val multNormal = 1f - min(effectiveDamage / (effectiveDamage + armor), maxReduction)
        val hitPierced = baseDamage * pierceMult
        val multPierced = 1f - min(hitPierced / (hitPierced + armor), maxReduction)
        val extra = effectiveDamage * (multPierced - multNormal)
        if (extra <= 0f) return

        engine.applyDamage(
            target,
            point,
            extra,
            DamageType.HIGH_EXPLOSIVE,
            0f,
            false,
            false,
            source,
            true,
        )
    }

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

    private fun spawnSustainedHitCollapseDistortion(engine: CombatEngineAPI, point: Vector2f, intensity: Float) {
        BoxUtilCombatVfx.ensureReady(engine)

        DistortionEntity().apply {
            setGlobalTimer(0.02f, 0.06f, 0.25f)

            setInnerIn(0.35f, 0.35f)
            setInnerFull(0.35f, 0.35f)
            setInnerOut(0.35f, 0.35f)

            innerHardness = 0.90f
            ringHardness = 0.70f

            val vfxS = config.vfxScale.coerceIn(0.35f, 2.25f)
            val aoeScale = lerp(config.aoeRadiusIntensityMinMul, config.aoeRadiusIntensityMaxMul, intensity.coerceIn(0f, 1f))
            val scale = aoeScale * vfxS
            setSizeIn(config.aoeRadiusBase * 0.35f * scale, config.aoeRadiusBase * 0.35f * scale)
            setSizeFull(config.aoeRadiusBase * 0.7f * scale, config.aoeRadiusBase * 0.7f * scale)
            setSizeOut(config.aoeRadiusBase * scale, config.aoeRadiusBase * scale)

            powerIn = lerp(0.2f, 0.4f, intensity)
            powerFull = lerp(0.4f, 0.8f, intensity)
            powerOut = 0f

            setLocation(point)

            val state = BoxUtilCombatVfx.addEntity(engine, this)
            if (state != 0) {
                log.warn("[ASTD] 引力坍缩炮：命中坍缩扭曲 addEntity 失败（state=$state），本次视觉缺席")
                delete()
            }
        }
    }

    private fun spawnExtraHitTickFx(engine: CombatEngineAPI, point: Vector2f, intensity: Float) {
        // 小范围红色爆炸烟雾 + 红色闪光（每 tick 一次）
        val t = intensity.coerceIn(0f, 1f)
        val vis = 1.5f
        val vfxS = config.vfxScale.coerceIn(0.35f, 2.25f)

        val color = Color(255, 35, 35, 55)
        repeat(5) {
            engine.addNebulaParticle(
                point,
                Vector2f(nextDouble(-10.0, 10.0).toFloat(), nextDouble(-10.0, 10.0).toFloat()),
                lerp(85f, 135f, t) * vis * vfxS,
                3f * nextFloat(),
                0.1f,
                0.2f,
                0.8f,
                color,
                true
            )
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

}
