package cn.kasuminova.astd.combat.effect.lens.production

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.pow

/**
 * GSP-12：命中即坍缩 + 范围幅压（总量封顶）+ 范围 EMP（总量封顶，带电弧可读）。
 *
 * 说明：投射体若被点防击落，则不会触发该效果。
 */
class Gsp12ShearOnHitEffect : OnHitEffectPlugin {

    companion object {
        // 需求：撕裂/特效 AOE 更大、更亮，整体倍率 +100%
        private const val RADIUS = 320f

        // 范围数值规则：以“直击面板伤害”（projectile.damageAmount）为基准，做总量封顶
        // - 额外范围能量伤害：总量 = 100% 直击
        // - 额外范围 EMP：总量 = 200% 直击
        private const val AOE_DAMAGE_TOTAL_MULT = 1.00f
        private const val AOE_EMP_TOTAL_MULT = 2.00f

        // 范围效果以“短促多脉冲”表现：既有读数，又不会拖太久
        private const val PULSE_DURATION = 0.75f
        private const val PULSE_INTERVAL = 0.10f
        private const val MAX_ARC_VISUALS_PER_PULSE = 6

        // 坍缩向心拖拽：短促、可读
        private const val PULL_MAX_SPEED = 260f
        private const val PULL_DURATION = 0.60f

        private val FX_CORE = Color(200, 110, 255, 210)
        private val FX_FRINGE = Color(90, 40, 140, 220)
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: com.fs.starfarer.api.combat.CombatEntityAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return
        if (point == null) return

        val source = projectile.source
        val panelDamage = sanitizePanelDamage(projectile.damageAmount, damageResult)

        // 命中即坍缩
        detonate(point, source, engine, panelDamage)
    }

    private fun detonate(center: Vector2f, source: ShipAPI?, engine: CombatEngineAPI, panelDamage: Float) {
        // 视觉：透镜环 + 爆闪
        engine.addHitParticle(center, Vector2f(), RADIUS * 1.15f, 2.05f, 0.28f, FX_CORE)
        engine.spawnExplosion(center, Vector2f(), FX_FRINGE, RADIUS * 0.85f, 0.28f)

        // 额外：一次短促的“扭曲波纹”，让撕裂更像空间被撕开
        spawnDetonationDistortion(center, engine)

        // 坍缩：对范围内目标施加“向心拖拽”（逐帧缓慢收缩）
        applyInwardPull(center, engine)

        // 视觉：边界刻度/碎光（可读范围）
        val ticks = 26
        for (i in 0 until ticks) {
            val ang = i * (360f / ticks.toFloat()) + MathUtils.getRandomNumberInRange(-7f, 7f)
            val p = MathUtils.getPointOnCircumference(center, RADIUS, ang)
            // 外向内：边界碎光向中心坍缩
            val v = Vector2f(center.x - p.x, center.y - p.y)
            v.scale(120f / maxOf(1f, v.length()))
            engine.addHitParticle(p, v, 28f, 1.65f, 0.80f, FX_CORE)
        }

        // 短促多脉冲 AOE：总量封顶（能量=100%直击，EMP=200%直击），并在命中点到受击点画电弧。
        val safePanelDamage = if (panelDamage.isFinitePositiveOrZero()) panelDamage else 0f
        val totalAoeDamage = (safePanelDamage * AOE_DAMAGE_TOTAL_MULT).finiteOrZero()
        val totalAoeEmp = (safePanelDamage * AOE_EMP_TOTAL_MULT).finiteOrZero()
        if (totalAoeDamage <= 0f && totalAoeEmp <= 0f) return

        val appliedDmg = HashMap<Int, Float>(16)
        val appliedEmp = HashMap<Int, Float>(16)

        var remainingDmg = totalAoeDamage
        var remainingEmp = totalAoeEmp

        val endTime = engine.getTotalElapsedTime(false) + PULSE_DURATION

        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            private var tick = 0f

            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused) return

                val now = engine.getTotalElapsedTime(false)
                if (now >= endTime || (remainingDmg <= 0f && remainingEmp <= 0f)) {
                    engine.removePlugin(this)
                    return
                }

                tick += amount
                if (tick < PULSE_INTERVAL) return
                tick -= PULSE_INTERVAL

                // 每次脉冲给一个轻量环提示（增强读数）
                try {
                    engine.addHitParticle(center, Vector2f(), RADIUS * 0.78f, 0.95f, 0.10f, FX_FRINGE)
                } catch (_: Throwable) {
                }

                val ships = CombatUtils.getShipsWithinRange(center, RADIUS)
                val targets = ArrayList<ShipAPI>(ships.size)
                for (ship in ships) {
                    if (ship.isHulk) continue
                    if (ship.hullSize == ShipAPI.HullSize.FIGHTER) continue
                    // 避免友伤：能判定来源时跳过同阵营
                    if (source != null && ship.owner == source.owner) continue

                    val dist = Misc.getDistance(center, ship.location)
                    if (!dist.isFinitePositiveOrZero()) continue
                    if (dist > RADIUS) continue
                    targets.add(ship)
                }
                if (targets.isEmpty()) return

                // 把“剩余总量”均摊到剩余脉冲里，保证总量封顶而且不会一次性打完。
                val timeLeft = (endTime - now).coerceAtLeast(PULSE_INTERVAL)
                val pulsesLeft = (timeLeft / PULSE_INTERVAL).coerceAtLeast(1f)
                val dmgBudget = (remainingDmg / pulsesLeft).coerceAtLeast(0f)
                val empBudget = (remainingEmp / pulsesLeft).coerceAtLeast(0f)

                // 权重：越近越强，且边缘也保留一点点
                var wSum = 0f
                val weights = FloatArray(targets.size)
                for (i in targets.indices) {
                    val ship = targets[i]
                    val dist = Misc.getDistance(center, ship.location)
                    val t = (1f - (dist / RADIUS)).coerceIn(0f, 1f)
                    val w = (0.20f + 0.80f * t).pow(2f)
                    weights[i] = w
                    wSum += w
                }
                if (wSum <= 0.0001f) return

                // 记录本脉冲中 EMP 最大的若干次，用于画电弧（避免性能炸裂）
                class ArcHit(val ship: ShipAPI, val point: Vector2f, val emp: Float)

                val arcHits = ArrayList<ArcHit>(8)

                for (i in targets.indices) {
                    val ship = targets[i]
                    val key = System.identityHashCode(ship)

                    val share = (weights[i] / wSum).coerceIn(0f, 1f)
                    var dmg = (dmgBudget * share).finiteOrZero()
                    var emp = (empBudget * share).finiteOrZero()

                    // 单目标封顶：最多吃满“总量”
                    val dmgAlready = appliedDmg[key] ?: 0f
                    val empAlready = appliedEmp[key] ?: 0f
                    dmg = dmg.coerceAtMost((totalAoeDamage - dmgAlready).coerceAtLeast(0f))
                    emp = emp.coerceAtMost((totalAoeEmp - empAlready).coerceAtLeast(0f))

                    // 总量封顶
                    dmg = dmg.coerceAtMost(remainingDmg.coerceAtLeast(0f))
                    emp = emp.coerceAtMost(remainingEmp.coerceAtLeast(0f))

                    if (dmg <= 0f && emp <= 0f) continue

                    // 受击点：优先落在“面向场域中心”的一侧，尽量命中盾弧
                    val toward = Misc.getAngleInDegrees(ship.location, center)
                    val shield = ship.shield
                    val shieldOn = shield != null && shield.isOn
                    val pointRadius = if (shieldOn) {
                        shield!!.radius.coerceAtLeast(ship.collisionRadius * 0.65f).coerceAtLeast(12f)
                    } else {
                        (ship.collisionRadius * 0.75f).coerceAtLeast(12f)
                    }
                    val hitPoint = MathUtils.getPointOnCircumference(ship.location, pointRadius, toward)

                    val shieldCovers = if (shieldOn) {
                        try {
                            shield!!.isWithinArc(hitPoint)
                        } catch (_: Throwable) {
                            false
                        }
                    } else {
                        false
                    }

                    // 优先攻击护盾：盾覆盖时走 soft-flux，便于读到“先压盾”
                    val softFlux = shieldCovers

                    try {
                        engine.applyDamage(
                            ship,
                            hitPoint,
                            dmg,
                            com.fs.starfarer.api.combat.DamageType.ENERGY,
                            emp,
                            false,
                            softFlux,
                            source,
                        )
                    } catch (_: Throwable) {
                        continue
                    }

                    remainingDmg -= dmg
                    remainingEmp -= emp
                    appliedDmg[key] = dmgAlready + dmg
                    appliedEmp[key] = empAlready + emp

                    if (emp > 0f) {
                        arcHits.add(ArcHit(ship, hitPoint, emp))
                    }

                    ship.setJitterUnder(this, FX_FRINGE, 0.18f, 2, 0f, 6f)

                    if (remainingDmg <= 0f && remainingEmp <= 0f) break
                }

                if (arcHits.isNotEmpty()) {
                    arcHits.sortByDescending { it.emp }
                    val take = arcHits.take(MAX_ARC_VISUALS_PER_PULSE)
                    for (h in take) {
                        try {
                            engine.spawnEmpArcVisual(
                                center,
                                source ?: h.ship,
                                h.point,
                                h.ship,
                                MathUtils.getRandomNumberInRange(10f, 16f),
                                FX_FRINGE,
                                FX_CORE,
                            )
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        })
    }

    private fun spawnDetonationDistortion(center: Vector2f, engine: CombatEngineAPI) {
        try {
            val e = DistortionEntity()

            // 命中即坍缩：出得快、收得快
            e.setGlobalTimer(0.04f, 0.12f, 0.55f)

            // 形状：中心较硬、外围较柔，避免把整屏都揉成一团。
            e.setInnerFull(0.40f, 0.40f)
            e.setInnerHardness(0.78f)
            e.setRingHardness(0.62f)

            // 外向内坍缩：从大到小
            e.setSizeIn(RADIUS * 1.30f, RADIUS * 1.30f)
            e.setSizeFull(RADIUS * 1.05f, RADIUS * 1.05f)
            e.setSizeOut(RADIUS * 0.22f, RADIUS * 0.22f)

            e.setPowerIn(1.15f)
            e.setPowerFull(1.00f)
            e.setPowerOut(0f)

            e.setLocation(center)
            CombatRenderingManager.addEntity(e)
        } catch (_: Throwable) {
            // BoxUtil 不可用/未初始化/玩家关闭扭曲等情况：不致命，直接跳过。
        }
    }

    private fun applyInwardPull(center: Vector2f, engine: CombatEngineAPI) {
        val targets = CombatUtils.getShipsWithinRange(center, RADIUS)
            .filter { s -> !s.isHulk && s.hullSize != ShipAPI.HullSize.FIGHTER }

        if (targets.isEmpty()) return

        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            private var elapsed = 0f

            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused) return
                elapsed += amount
                val x = (elapsed / PULL_DURATION).coerceIn(0f, 1f)

                // 先缓入、后缓出：整体看起来像“慢慢收缩”。
                val inFactor = smoothStep(0f, 0.28f, x)
                val outFactor = 1f - smoothStep(0.70f, 1.00f, x)
                val strength = (inFactor * outFactor).coerceIn(0f, 1f)

                // PULL_MAX_SPEED 原本是“瞬间加速到的速度”；改为逐帧加速度时需要除以 duration。
                // 曲线平均值 < 1，因此额外乘一点补偿系数，保持体感不至于太弱。
                val accelPerSecond = (PULL_MAX_SPEED / PULL_DURATION) * 1.65f * strength

                for (ship in targets) {
                    if (ship.isHulk) continue

                    val dist = Misc.getDistance(center, ship.location)
                    if (!dist.isFinitePositiveOrZero()) continue
                    if (dist > RADIUS) continue

                    val t = (1f - (dist / RADIUS)).coerceIn(0f, 1f)
                    if (t <= 0f) continue

                    val dir = Vector2f(center.x - ship.location.x, center.y - ship.location.y)
                    val len = maxOf(1f, dir.length())
                    dir.scale(1f / len)

                    val dv = accelPerSecond * t * amount
                    ship.velocity?.let {
                        it.x += dir.x * dv
                        it.y += dir.y * dv
                    }
                }

                if (elapsed >= PULL_DURATION) {
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun sanitizePanelDamage(raw: Float, damageResult: ApplyDamageResultAPI): Float {
        // projectile.damageAmount 偶发会出现 NaN/Infinity（通常是某些特殊弹体/改伤链路导致）。
        // 一旦把 NaN 喂给 applyDamage，会把目标的幅能/船体推入 NaN 地狱。
        if (raw.isFinitePositiveOrZero()) return raw

        // 回退：取这次命中“实际造成”的总伤害（并不等于面板值，但至少是有限数）。
        val fallback = (damageResult.damageToShields + damageResult.totalDamageToArmor + damageResult.damageToHull)
        return if (fallback.isFinitePositiveOrZero()) fallback else 0f
    }

    private fun Float.isFinitePositiveOrZero(): Boolean = !this.isNaN() && !this.isInfinite() && this >= 0f

    private fun Float.finiteOrZero(): Float = if (this.isNaN() || this.isInfinite() || this < 0f) 0f else this
}
