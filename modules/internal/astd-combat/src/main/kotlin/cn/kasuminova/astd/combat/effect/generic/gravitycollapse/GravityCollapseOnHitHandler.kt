package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
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
 * 引力坍缩炮：命中持续效果（坍缩扭曲 + 周期性 AOE 额外伤害 + 引力撕裂）。
 *
 * 设计目标：从具体武器 everyFrameEffect 中抽离，以便多个尺寸/变体复用。
 */
internal class GravityCollapseOnHitHandler(
    private val config: GravityCollapseOnHitConfig,
) {

    companion object {
        // 盾面吸附容差：点略微在盾外也认为被盾覆盖（避免浮点/采样误差导致“看起来打到盾但实际扣船体”）。
        private const val SHIELD_SNAP_EPS = 12f
    }

    private val hitCollapseInterval = IntervalUtil(config.tickInterval, config.tickInterval)
    private val extraDamageInterval = IntervalUtil(config.tickInterval, config.tickInterval)
    private var wasHittingLastFrame = false

    fun reset() {
        hitCollapseInterval.forceIntervalElapsed()
        extraDamageInterval.forceIntervalElapsed()
        wasHittingLastFrame = false
    }

    fun advance(
        engine: CombatEngineAPI,
        amount: Float,
        weapon: WeaponAPI,
        beam: BeamAPI?,
        intensity: Float,
        /**
         * 面板 DPS（用于把“每秒值”换算成 tick 伤害）。
         * 通常传：weapon.damage.damage（已包含技能/改装等加成）。
         */
        panelDps: Float,
    ) {
        if (beam == null) {
            if (wasHittingLastFrame) {
                hitCollapseInterval.forceIntervalElapsed()
                extraDamageInterval.forceIntervalElapsed()
            }
            wasHittingLastFrame = false
            return
        }

        val target = try {
            beam.damageTarget
        } catch (_: Throwable) {
            null
        }

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

        // 2) 周期性额外伤害：每 tick 一次
        extraDamageInterval.advance(amount)
        if (!extraDamageInterval.intervalElapsed()) return

        val tickDamageBase = (panelDps.coerceAtLeast(0f) * config.tickInterval).coerceAtLeast(0f)
        if (tickDamageBase <= 0f) return

        val source = weapon.ship
        val owner = source?.owner

        val radiusMul = lerp(config.aoeRadiusIntensityMinMul, config.aoeRadiusIntensityMaxMul, t)
        val radius = (config.aoeRadiusBase * radiusMul).coerceAtLeast(1f)

        val gravityTearTarget = target as? ShipAPI

        try {
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

                    // 用“到外壳/实体边界的最短距离”做衰减，避免大型目标被误判为离得很远。
                    val distToSurface = (MathUtils.getDistance(point, loc) - cr).coerceAtLeast(0f)
                    if (distToSurface > radius) continue

                    val u = (distToSurface / radius).coerceIn(0f, 1f)
                    val falloff = lerp(1f, config.aoeEdgeDamageMul, u)
                    val dmg = tickDamageBase * falloff
                    if (dmg <= 0f) continue

                    // 关键修复：若目标有盾且该点被盾覆盖，则把伤害落点吸附到盾面，避免穿透到装甲/船体。
                    val applyPoint = (other as? ShipAPI)?.let { resolveShieldedDamagePoint(it, point) } ?: point

                    engine.applyDamage(
                        other as CombatEntityAPI,
                        applyPoint,
                        dmg,
                        DamageType.HIGH_EXPLOSIVE,
                        0f,
                        false,
                        false,
                        source,
                        true,
                    )

                    // 需求：坍缩脉冲范围内单位获得机动/航速下降（可叠加）。
                    if (ship != null && !ship.isHulk) {
                        GravityCollapseMobilityDebuff.apply(engine = engine, source = source, target = ship)
                    }

                    if (gravityTearTarget != null && other === gravityTearTarget) {
                        tryApplyGravityTearOnHullHit(
                            engine = engine,
                            source = source,
                            targetShip = gravityTearTarget,
                            // 关键：撕裂判定必须与本次伤害落点一致。
                            // 否则会出现“打在盾上但按船体点计算撕裂/穿透”的问题。
                            point = applyPoint,
                            aoeDamageApplied = dmg,
                        )
                    }
                }
            } else {
                for (other in engine.ships) {
                    if (source != null && other === source) continue
                    if (!config.affectAlliesAndNeutral && owner != null && other.owner == owner) continue
                    if (other.isHulk && !config.affectHulks) continue

                    // 用“到外壳的最短距离”做衰减，避免大型舰被误判为离得很远
                    val distToHull = (MathUtils.getDistance(point, other.location) - other.collisionRadius).coerceAtLeast(0f)
                    if (distToHull > radius) continue

                    val u = (distToHull / radius).coerceIn(0f, 1f)
                    val falloff = lerp(1f, config.aoeEdgeDamageMul, u)
                    val dmg = tickDamageBase * falloff
                    if (dmg <= 0f) continue

                    // 关键修复：若目标有盾且该点被盾覆盖，则把伤害落点吸附到盾面，避免穿透到装甲/船体。
                    val applyPoint = resolveShieldedDamagePoint(other, point)

                    engine.applyDamage(
                        other,
                        applyPoint,
                        dmg,
                        DamageType.HIGH_EXPLOSIVE,
                        0f,
                        false,
                        false,
                        source,
                        true,
                    )

                    // 需求：坍缩脉冲范围内单位获得机动/航速下降（可叠加）。
                    if (!other.isHulk) {
                        GravityCollapseMobilityDebuff.apply(engine = engine, source = source, target = other)
                    }

                    if (gravityTearTarget != null && other === gravityTearTarget) {
                        tryApplyGravityTearOnHullHit(
                            engine = engine,
                            source = source,
                            targetShip = gravityTearTarget,
                            point = applyPoint,
                            aoeDamageApplied = dmg,
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }

        spawnExtraHitTickFx(engine, point, t)
    }

    /**
     * 对于带盾舰船：若 [explosionPoint] 被盾覆盖，则返回盾面上的落点（用于 applyDamage）。
     * 这样 AOE 不会“看起来打到盾但实际穿透扣船体”。
     */
    private fun resolveShieldedDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f {
        val shield = try {
            ship.shield
        } catch (_: Throwable) {
            null
        } ?: return explosionPoint

        if (!shield.isOn) return explosionPoint

        val inArc = try {
            shield.isWithinArc(explosionPoint)
        } catch (_: Throwable) {
            // 某些实现下 isWithinArc 可能抛异常；保守起见视为在弧内。
            true
        }
        if (!inArc) return explosionPoint

        val sl = try {
            shield.location
        } catch (_: Throwable) {
            null
        } ?: return explosionPoint

        val r = try {
            shield.radius
        } catch (_: Throwable) {
            0f
        }
        if (r <= 0f) return explosionPoint

        val d = MathUtils.getDistance(explosionPoint, sl)
        if (d > r + SHIELD_SNAP_EPS) return explosionPoint

        val ang = Misc.getAngleInDegrees(sl, explosionPoint)
        return MathUtils.getPointOnCircumference(sl, r, ang)
    }

    private fun tryApplyGravityTearOnHullHit(
        engine: CombatEngineAPI,
        source: ShipAPI?,
        targetShip: ShipAPI,
        point: Vector2f,
        aoeDamageApplied: Float,
    ) {
        if (aoeDamageApplied <= 0f) return
        if (targetShip.isHulk) return

        // 若命中点被盾覆盖，则视为“未击中船体”，不触发引力撕裂。
        // 使用与 resolveShieldedDamagePoint 相同的容差，避免“点略微在盾外”导致误触发。
        try {
            val shield = targetShip.shield
            if (shield != null && shield.isOn) {
                val inArc = try {
                    shield.isWithinArc(point)
                } catch (_: Throwable) {
                    true
                }
                if (inArc) {
                    val sl = shield.location
                    val r = shield.radius
                    if (sl != null && r > 0f) {
                        val d = MathUtils.getDistance(point, sl)
                        if (d <= r + SHIELD_SNAP_EPS) return
                    }
                }
            }
        } catch (_: Throwable) {
        }

        val grid = try {
            targetShip.armorGrid
        } catch (_: Throwable) {
            null
        } ?: return

        val cell = try {
            grid.getCellAtLocation(point)
        } catch (_: Throwable) {
            null
        } ?: return
        if (cell.size < 2) return

        val cx = cell[0]
        val cy = cell[1]
        if (cx < 0 || cy < 0) return

        val armor0 = try {
            grid.getArmorValue(cx, cy)
        } catch (_: Throwable) {
            0f
        }.coerceAtLeast(0f)

        val maxArmor = try {
            grid.maxArmorInCell
        } catch (_: Throwable) {
            0f
        }.coerceAtLeast(0f)

        val armorFrac = if (maxArmor > 0.01f) (armor0 / maxArmor).coerceIn(0f, 1f) else 0f

        // 穿透：局部装甲不足时，额外直扣船体（无视装甲减伤）
        if (armorFrac < config.tearArmorThreshold) {
            // 让“穿透”在阈值处为 0，装甲越低越接近 1；避免装甲仍然充足时也产生明显船体直扣。
            val thr = config.tearArmorThreshold.coerceAtLeast(0.01f)
            val pierceFactor = ((thr - armorFrac) / thr).coerceIn(0f, 1f)
            val hullExtra = (aoeDamageApplied * pierceFactor).coerceAtLeast(0f)
            if (hullExtra <= 0f) return

            val hp0 = try {
                targetShip.hitpoints
            } catch (_: Throwable) {
                0f
            }

            // - 穿透直扣船体保留至少 1 点（避免 setHitpoints 直接扣到 0 带来的异常死亡链路/表现）。
            // - 若目标已濒死（船体 <= 1），则用一次小额 applyDamage 走正常死亡链路。
            if (hp0 <= 1f) {
                try {
                    engine.applyDamage(
                        targetShip,
                        point,
                        config.executeDamage,
                        DamageType.HIGH_EXPLOSIVE,
                        0f,
                        true,
                        false,
                        source,
                        true,
                    )
                } catch (_: Throwable) {
                }
                return
            }

            val hpAfter = (hp0 - hullExtra).coerceAtLeast(1f)
            val directLoss = (hp0 - hpAfter).coerceAtLeast(0f)
            try {
                targetShip.hitpoints = hpAfter
            } catch (_: Throwable) {
            }

            // 直接扣 hitpoints 不会自动弹出伤害数字；这里补一条 floaty。
            try {
                if (directLoss > 0f && Misc.shouldShowDamageFloaty(source, targetShip)) {
                    val p2 = Vector2f(point)
                    p2.y += 20f
                    engine.addFloatingDamageText(p2, directLoss, Misc.FLOATY_HULL_DAMAGE_COLOR, targetShip, source)
                }
            } catch (_: Throwable) {
            }
        }
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

            BoxUtilCombatVfx.addEntity(engine, this)
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
