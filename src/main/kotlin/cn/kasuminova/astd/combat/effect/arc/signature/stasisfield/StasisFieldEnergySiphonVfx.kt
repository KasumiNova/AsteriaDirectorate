package cn.kasuminova.astd.combat.effect.arc.signature.stasisfield

import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamEllipseRingRenderer
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamSpriteRingRenderer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.TaperedBeamTrailsVfx

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 停滞场“能量收集/回流”束：
 * - 当弹体在立场期间消失/被摧毁，或系统结束统一结算时，从弹体位置射向视界线（舰首汇聚点）。
 * - 纯视觉：短寿命、偏细、偏亮，给出“被抽取/充能”的读感。
 */
internal object StasisFieldEnergySiphonVfx {

    private val CORE_COLOR = Color(255, 95, 95, 220)
    private val GLOW_COLOR = Color(255, 35, 35, 180)
    private val HOT_COLOR = Color(255, 70, 70, 210)

    // 更“流动”的读感：淡入更慢、停留更久一些，避免像瞬间点射。
    private const val FADE_IN = 0.06f
    private const val FULL = 0.16f
    private const val FADE_OUT = 0.42f

    // 需求：束宽整体 -40%
    private const val WIDTH_MUL = 0.60f

    fun spawn(
        engine: CombatEngineAPI,
        from: Vector2f,
        to: Vector2f,
        strength: Float,
    ) {
        val level = strength.coerceIn(0f, 1f)

        val vec = Vector2f(to.x - from.x, to.y - from.y)
        val len = sqrt(vec.x * vec.x + vec.y * vec.y)
        if (len <= 8f) return

        val facing = VectorUtils.getFacing(vec)

        val coreBaseW = lerp(6f, 14f, level) * WIDTH_MUL
        val coreTipW = coreBaseW * 0.55f
        val glowBaseW = coreBaseW * 1.8f
        val glowTipW = coreTipW * 1.6f

        TaperedBeamTrailsVfx.spawn(
            engine = engine,
            from = from,
            to = to,
            coreBaseWidth = coreBaseW,
            coreTipWidth = coreTipW,
            glowBaseWidth = glowBaseW,
            glowTipWidth = glowTipW,
            params = TaperedBeamTrailsVfx.BeamParams(
                fadeIn = FADE_IN,
                full = FULL,
                fadeOut = lerp(FADE_OUT, FADE_OUT * 1.25f, level),
                core = TaperedBeamTrailsVfx.LayerParams(
                    coreColor = CORE_COLOR,
                    fringeColor = GLOW_COLOR,
                    baseAlphaMul = 0.70f,
                    tipAlphaMul = 0.35f,
                    baseEmissiveAlphaMul = 2.20f,
                    tipEmissiveAlphaMul = 0.95f,
                    mixPower = 3.25f,
                ),
                glow = TaperedBeamTrailsVfx.LayerParams(
                    coreColor = GLOW_COLOR,
                    // 需求：更红少白
                    fringeColor = HOT_COLOR,
                    baseAlphaMul = 0.25f,
                    tipAlphaMul = 0.12f,
                    baseEmissiveAlphaMul = 1.85f,
                    tipEmissiveAlphaMul = 0.65f,
                    mixPower = 3.60f,
                ),
            )
        )

        // 粒子：沿束回流（更慢、更“流动”）
        try {
            val jitter = lerp(8f, 18f, level)
            val count = lerp(10f, 26f, level).toInt().coerceIn(8, 30)
            for (i in 0 until count) {
                val t = Math.random().toFloat().coerceIn(0f, 1f)
                val p = Vector2f(from.x + vec.x * t, from.y + vec.y * t)
                val loc = MathUtils.getRandomPointInCircle(p, jitter)
                val size = lerp(7f, 14f, level) * MathUtils.getRandomNumberInRange(0.75f, 1.25f)
                val dur = MathUtils.getRandomNumberInRange(0.22f, 0.55f)

                // 速度：朝向“汇聚点”
                val spd = MathUtils.getRandomNumberInRange(70f, 240f) * (0.75f + 0.55f * level)
                val vel = MathUtils.getPointOnCircumference(Vector2f(0f, 0f), spd, facing)

                engine.addSmoothParticle(loc, vel, size, 1.25f, dur, if (Math.random() < 0.50) CORE_COLOR else GLOW_COLOR)
            }
        } catch (_: Throwable) {
        }

        // “流动感”补强：短时间持续生成回流粒子（避免像一帧打出去的激光）。
        engine.addPlugin(SiphonFlowParticlesPlugin(Vector2f(from), Vector2f(to), level))
    }

    private class SiphonFlowParticlesPlugin(
        private val from: Vector2f,
        private val to: Vector2f,
        private val level: Float,
    ) : BaseEveryFrameCombatPlugin() {

        private var elapsed = 0f
        private var acc = 0f

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused) return
            if (amount <= 0f) return

            elapsed += amount
            if (elapsed > 0.38f) {
                engine.removePlugin(this)
                return
            }

            val vec = Vector2f(to.x - from.x, to.y - from.y)
            val len = sqrt(vec.x * vec.x + vec.y * vec.y)
            if (len <= 8f) {
                engine.removePlugin(this)
                return
            }

            val facing = VectorUtils.getFacing(vec)
            val rate = lerp(28f, 65f, level)
            acc += rate * amount
            val count = acc.toInt().coerceIn(0, 10)
            if (count <= 0) return
            acc -= count

            val jitter = lerp(10f, 22f, level)
            repeat(count) {
                // 更偏向起点，像“抽吸”
                val t = (Math.random().toFloat().pow(1.75f)).coerceIn(0f, 1f)
                val base = Vector2f(from.x + vec.x * t, from.y + vec.y * t)
                val loc = MathUtils.getRandomPointInCircle(base, jitter)

                val spd = MathUtils.getRandomNumberInRange(45f, 140f) * (0.8f + 0.5f * level)
                val vel = MathUtils.getPointOnCircumference(Vector2f(0f, 0f), spd, facing)
                // 横向扰动：像“流体”而不是直线射击
                val side = MathUtils.getPointOnCircumference(Vector2f(0f, 0f), spd * MathUtils.getRandomNumberInRange(-0.35f, 0.35f), facing + 90f)
                vel.x += side.x
                vel.y += side.y

                val size = lerp(6f, 12f, level) * MathUtils.getRandomNumberInRange(0.85f, 1.35f)
                val dur = MathUtils.getRandomNumberInRange(0.35f, 0.70f)
                val c = if (Math.random() < 0.40) CORE_COLOR else GLOW_COLOR
                try {
                    engine.addSmoothParticle(loc, vel, size, 1.15f, dur, c)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
