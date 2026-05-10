package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

/**
 * “引力坍缩炮”一次性光束 VFX（MVP）。
 *
 * 目标：
 * - 以 BoxUtil TrailEntity 为主（core/glow + U 镜像），快速得到高质量束体。
 * - 以 OglEllipseRingRenderer 作为“奇点/透镜环”补充表现。
 *
 * 注意：这里只做视觉，不包含任何伤害/判定逻辑。
 */
internal object GravityCollapseVfx {

    private val log = Global.getLogger(GravityCollapseVfx::class.java)

    // 视觉参数：更红少白（终结束风格）
    private val CORE_COLOR = Color(255, 85, 85, 235)
    private val GLOW_COLOR = Color(255, 35, 35, 190)
    private val HOT_COLOR = Color(255, 95, 95, 235)

    private const val CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val FRINGE_SPRITE = "graphics/fx/beamfringeb.png"

    private const val MIX_POWER_CORE = 2.6f
    private const val MIX_POWER_GLOW = 3.1f

    // 生命周期：1.5s 充能，5s 持续输出
    private const val CHARGE_TIME = 1.5f
    private const val FIRE_TIME = 5.0f

    // 束体淡入/淡出（不等同于系统充能）
    private const val BEAM_FADE_IN = 0.08f
    private const val BEAM_FADE_OUT = 0.65f

    // 椭圆环“高速前进”装饰（模拟绕束旋进：横向摆动 + ring 自旋）
    private val RING_INTERVAL = IntervalUtil(0.045f, 0.060f)
    private const val RING_TRAVEL_SPEED = 4200f

    // 需求：束宽整体 -40%
    private const val WIDTH_MUL = 0.60f

    // 充能粒子
    private const val CHARGE_PARTICLE_PER_SEC = 120f
    private const val CHARGE_PARTICLE_MAX_PER_FRAME = 18

    fun spawnBeam(
        engine: CombatEngineAPI,
        from: Vector2f,
        to: Vector2f,
        intensity: Float = 1f,
    ) {
        engine.addPlugin(ChargedBeamPlugin(Vector2f(from), Vector2f(to), intensity.coerceIn(0f, 1f)))
    }

    private class ChargedBeamPlugin(
        private val from: Vector2f,
        private val to: Vector2f,
        private val intensity: Float,
    ) : BaseEveryFrameCombatPlugin() {

        private var time = 0f
        private var fired = false
        private var done = false

        // 充能粒子 rate limit
        private var chargeAcc = 0f

        private val ringInterval = IntervalUtil(RING_INTERVAL.minInterval, RING_INTERVAL.maxInterval)

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused) return
            if (amount <= 0f) return
            if (done) {
                engine.removePlugin(this)
                return
            }

            time += amount

            val vec = Vector2f(to.x - from.x, to.y - from.y)
            val len = sqrt(vec.x * vec.x + vec.y * vec.y)
            if (len <= 8f) {
                done = true
                return
            }
            val facing = VectorUtils.getFacing(vec)

            if (time < CHARGE_TIME) {
                val t = (time / CHARGE_TIME).coerceIn(0f, 1f)
                emitCharge(engine, amount, from, facing, t, intensity)
                return
            }

            // ====== 充能完成：触发一次性爆发，并生成束体 ======
            if (!fired) {
                fired = true
                ringInterval.forceIntervalElapsed()
                spawnFireBurst(engine, from, to, facing, intensity)
                spawnBeamTrails(engine, from, facing, len, intensity)
                spawnHitCollapseDistortion(engine, to, intensity)
            }

            val fireElapsed = (time - CHARGE_TIME).coerceAtLeast(0f)
            if (fireElapsed <= FIRE_TIME) {
                // 椭圆环装饰：沿束高速前进
                ringInterval.advance(amount)
                if (ringInterval.intervalElapsed()) {
                    spawnTravelingRings(engine, from, facing, len, fireElapsed, intensity)
                }
            } else {
                done = true
            }
        }

        private fun emitCharge(engine: CombatEngineAPI, amount: Float, center: Vector2f, facing: Float, t: Float, s: Float) {
            // 充能阶段：大量粒子向中心汇聚
            val strength = (0.20f + 0.80f * t).coerceIn(0f, 1f)
            val rate = CHARGE_PARTICLE_PER_SEC * (0.35f + 0.65f * strength)
            chargeAcc += rate * amount
            val count = chargeAcc.toInt().coerceAtMost(CHARGE_PARTICLE_MAX_PER_FRAME)
            if (count > 0) chargeAcc -= count
            if (count <= 0) return

            val radius = lerp(260f, 90f, strength)
            val speed = lerp(520f, 1450f, strength) * (0.75f + 0.55f * s)
            val sizeBase = lerp(10f, 18f, strength) * (0.85f + 0.45f * s)

            for (i in 0 until count) {
                val ang = MathUtils.getRandomNumberInRange(0f, 360f)
                val spawn = MathUtils.getPointOnCircumference(center, radius * MathUtils.getRandomNumberInRange(0.55f, 1.05f), ang)
                val dir = Vector2f(center.x - spawn.x, center.y - spawn.y)
                val dist = sqrt((dir.x * dir.x + dir.y * dir.y).coerceAtLeast(0.001f))
                dir.x /= dist
                dir.y /= dist
                val vel = Vector2f(dir.x * speed, dir.y * speed)

                val size = sizeBase * MathUtils.getRandomNumberInRange(0.70f, 1.30f)
                val dur = MathUtils.getRandomNumberInRange(0.10f, 0.20f)
                val bright = MathUtils.getRandomNumberInRange(0.75f, 1.35f) * (0.70f + 0.55f * strength)
                val c = if (Math.random() < 0.40) CORE_COLOR else GLOW_COLOR
                try {
                    engine.addSmoothParticle(spawn, vel, size, bright, dur, c)
                } catch (_: Throwable) {
                }
            }

            // 充能中心点：小闪光
            try {
                val v = MathUtils.getPointOnCircumference(Vector2f(0f, 0f), MathUtils.getRandomNumberInRange(10f, 55f), facing)
                engine.addHitParticle(center, v, lerp(26f, 62f, strength), 1.2f, 0.06f, HOT_COLOR)
            } catch (_: Throwable) {
            }
        }

        private fun spawnFireBurst(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, facing: Float, s: Float) {
            val level = s.coerceIn(0f, 1f)
            try {
                engine.spawnExplosion(from, Vector2f(0f, 0f), CORE_COLOR, lerp(140f, 260f, level), 0.25f)
            } catch (_: Throwable) {
            }
            try {
                engine.addHitParticle(from, Vector2f(0f, 0f), lerp(220f, 360f, level), 1.8f, 0.12f, HOT_COLOR)
            } catch (_: Throwable) {
            }
            try {
                engine.addSmoothParticle(from, Vector2f(0f, 0f), lerp(340f, 520f, level), 1.25f, 0.30f, GLOW_COLOR)
            } catch (_: Throwable) {
            }

            // 命中点爆发：更像“束到达并触发坍缩”
            try {
                engine.spawnExplosion(to, Vector2f(0f, 0f), GLOW_COLOR, lerp(120f, 220f, level), 0.22f)
            } catch (_: Throwable) {
            }
            try {
                engine.addHitParticle(to, Vector2f(0f, 0f), lerp(180f, 320f, level), 1.6f, 0.10f, HOT_COLOR)
            } catch (_: Throwable) {
            }

            try {
                OglEllipseRingRenderer.spawn(
                    engine,
                    OglEllipseRingRenderer.RingSpec(
                        center = to,
                        facing = facing,
                        aSideHalf = lerp(65f, 120f, level),
                        bAlongHalf = lerp(48f, 90f, level),
                        duration = 0.42f,
                        color = Color(255, 90, 90, (85f + 95f * level).toInt().coerceIn(0, 255)),
                        lineWidthPx = 1.65f,
                        segments = 96,
                        expandSpeed = 120f,
                        tangentialSpeed = 2.35f,
                    )
                )
            } catch (_: Throwable) {
            }

            // 发射端透镜环（爆发）
            try {
                OglEllipseRingRenderer.spawn(
                    engine,
                    OglEllipseRingRenderer.RingSpec(
                        center = from,
                        facing = facing,
                        aSideHalf = lerp(80f, 140f, level),
                        bAlongHalf = lerp(55f, 95f, level),
                        duration = 0.55f,
                        color = Color(255, 120, 120, (95f + 95f * level).toInt().coerceIn(0, 255)),
                        lineWidthPx = 1.85f,
                        segments = 96,
                        expandSpeed = 160f,
                        tangentialSpeed = 2.10f,
                    )
                )
            } catch (_: Throwable) {
            }
        }

        private fun spawnBeamTrails(engine: CombatEngineAPI, from: Vector2f, facing: Float, len: Float, s: Float) {
            val level = s.coerceIn(0f, 1f)
            val coreBaseW = lerp(22f, 52f, level) * WIDTH_MUL
            val coreTipW = lerp(8f, 18f, level) * WIDTH_MUL
            val glowBaseW = coreBaseW * 2.05f
            val glowTipW = coreTipW * 1.85f

            val coreSprite = try {
                Global.getSettings().getSprite(CORE_SPRITE)
            } catch (_: Throwable) {
                return
            }
            val fringeSprite = try {
                Global.getSettings().getSprite(FRINGE_SPRITE)
            } catch (_: Throwable) {
                return
            }

            val core = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine,
                location = from,
                facing = facing,
                length = len,
                baseWidth = coreBaseW,
                tipWidth = coreTipW,
                coreColor = CORE_COLOR,
                fringeColor = GLOW_COLOR,
                coreSprite = coreSprite,
                fringeSprite = fringeSprite,
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                full = 9999f,
                baseAlphaMul = 0.62f,
                tipAlphaMul = 0.35f,
                baseEmissiveAlphaMul = 3.10f,
                tipEmissiveAlphaMul = 1.55f,
                mixPower = MIX_POWER_CORE,
            )
            val coreMirroredU = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
                engine = engine,
                location = from,
                facing = facing,
                length = len,
                baseWidth = coreBaseW,
                tipWidth = coreTipW,
                coreColor = CORE_COLOR,
                fringeColor = GLOW_COLOR,
                coreSprite = coreSprite,
                fringeSprite = fringeSprite,
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                full = 9999f,
                baseAlphaMul = 0.40f,
                tipAlphaMul = 0.20f,
                baseEmissiveAlphaMul = 1.55f,
                tipEmissiveAlphaMul = 0.85f,
                mixPower = MIX_POWER_CORE,
            )

            val glow = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine,
                location = from,
                facing = facing,
                length = len,
                baseWidth = glowBaseW,
                tipWidth = glowTipW,
                coreColor = GLOW_COLOR,
                fringeColor = HOT_COLOR,
                coreSprite = coreSprite,
                fringeSprite = fringeSprite,
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                full = 9999f,
                baseAlphaMul = 0.18f,
                tipAlphaMul = 0.10f,
                baseEmissiveAlphaMul = 2.10f,
                tipEmissiveAlphaMul = 0.85f,
                mixPower = MIX_POWER_GLOW,
            )
            val glowMirroredU = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
                engine = engine,
                location = from,
                facing = facing,
                length = len,
                baseWidth = glowBaseW,
                tipWidth = glowTipW,
                coreColor = GLOW_COLOR,
                fringeColor = HOT_COLOR,
                coreSprite = coreSprite,
                fringeSprite = fringeSprite,
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                full = 9999f,
                baseAlphaMul = 0.14f,
                tipAlphaMul = 0.08f,
                baseEmissiveAlphaMul = 1.25f,
                tipEmissiveAlphaMul = 0.60f,
                mixPower = MIX_POWER_GLOW,
            )

            listOf(core, coreMirroredU, glow, glowMirroredU).forEach { e ->
                if (e == null) return@forEach
                try {
                    e.setGlobalTimer(BEAM_FADE_IN, FIRE_TIME, BEAM_FADE_OUT)
                } catch (_: Throwable) {
                }
            }
        }

        private fun spawnTravelingRings(engine: CombatEngineAPI, from: Vector2f, facing: Float, len: Float, fireElapsed: Float, s: Float) {
            val level = s.coerceIn(0f, 1f)
            val baseA = lerp(16f, 28f, level) * WIDTH_MUL
            val baseB = baseA * 1.85f

            // 多个环：用不同 offset 形成“串流”
            val offsets = floatArrayOf(0f, len * 0.22f, len * 0.44f)
            for (off in offsets) {
                val dist = ((fireElapsed * RING_TRAVEL_SPEED + off) % len).coerceIn(0f, len)
                // 模拟“围绕光束前进”：沿束前进 + 横向摆动
                val basePos = MathUtils.getPointOnCircumference(from, dist, facing)
                val theta = fireElapsed * 10f + off * 0.003f
                val wobble = (kotlin.math.sin(theta.toDouble()).toFloat()) * lerp(10f, 22f, level)
                val pos = MathUtils.getPointOnCircumference(basePos, wobble, facing + 90f)
                try {
                    OglEllipseRingRenderer.spawn(
                        engine,
                        OglEllipseRingRenderer.RingSpec(
                            center = pos,
                            facing = facing + theta * 28f,
                            aSideHalf = baseA,
                            bAlongHalf = baseB,
                            duration = 0.22f,
                            color = Color(255, 85, 85, (70f + 90f * level).toInt().coerceIn(0, 255)),
                            lineWidthPx = 1.35f * WIDTH_MUL,
                            segments = 72,
                            expandSpeed = 0f,
                            tangentialSpeed = 5.0f,
                        )
                    )
                } catch (_: Throwable) {
                }
            }
        }

        private fun spawnHitCollapseDistortion(engine: CombatEngineAPI, point: Vector2f, s: Float) {
            val level = s.coerceIn(0f, 1f)

            // 优先：BoxUtil DistortionEntity（更像“坍缩扭曲”）
            val ok = try {
                val e = DistortionEntity()
                e.setGlobalTimer(0.05f, 0.10f, 0.55f)

                // 坍缩感：先强后弱
                e.setInnerFull(0.30f, 0.30f)
                e.setInnerHardness(0.85f)
                e.setRingHardness(0.62f)

                e.setSizeIn(80f, 80f)
                e.setSizeFull(220f, 220f)
                e.setSizeOut(520f, 520f)

                e.setPowerIn(0.00f)
                e.setPowerFull(lerp(0.85f, 1.25f, level))
                e.setPowerOut(0f)

                e.setLocation(point)
                CombatRenderingManager.addEntity(e)
                true
            } catch (_: Throwable) {
                false
            }

            if (ok) return

            // 回退：nebula 近似扭曲
            try {
                val c = Color(255, 55, 55, 55)
                engine.addNebulaParticle(point, Vector2f(0f, 0f), 240f, 2.8f, 0.06f, 0.22f, 0.65f, c, true)
                engine.addNebulaParticle(point, Vector2f(0f, 0f), 360f, 3.1f, 0.05f, 0.20f, 0.75f, c, true)
            } catch (_: Throwable) {
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
