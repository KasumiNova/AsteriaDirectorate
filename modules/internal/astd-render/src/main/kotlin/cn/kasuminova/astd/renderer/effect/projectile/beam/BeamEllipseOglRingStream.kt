package cn.kasuminova.astd.renderer.effect.projectile.beam

import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.sin

/**
 * 沿光束方向“前进”的椭圆环串流：
 * - 通过按【距离间距】生成环（类似 AOD-7 的路径采样思路），让环看起来像在束上滑行，而不是闪烁。
 * - 由于 OglEllipseRingRenderer 的 ring 中心不可更新，这里用“连续生成 + 短寿命”来形成运动感。
 */
internal class BeamEllipseOglRingStream(
    /** 每隔多少距离生成一圈（世界单位）。 */
    private val spacingDistance: Float,
    /** 环沿束方向的推进速度（世界单位/秒）。 */
    private val travelSpeed: Float,
    /** 基础环大小（侧向半轴）。*/
    private val aSideHalf: Float,
    /** 基础环大小（沿向半轴）。*/
    private val bAlongHalf: Float,
    private val duration: Float,
    private val baseColor: Color,
    private val lineWidthPx: Float,
    private val segments: Int,
    private val expandSpeed: Float,
    private val tangentialSpeed: Float,
    /** 环中心的“绕束摆动”幅度（世界单位）。 */
    private val orbitAmplitude: Float,
    /** 串流通道数：多通道相位错开，会更像“围绕束旋进”。 */
    lanes: Int = 3,

    /** 是否启用横向摆动（环中心偏离束线）。 */
    private val enableCenterWobble: Boolean = true,
    /** 是否启用环自身旋进（ringFacing 随时间变化）。 */
    private val enableRingSpin: Boolean = true,
    /** 是否随机每条 lane 的初始相位。关闭后相位将按 lane 均匀分布，利于“稳定/可控”的视觉。 */
    private val randomizeLanePhase: Boolean = true,
) {

    private data class Lane(var travel: Float, var acc: Float, val phase: Float)

    private val step = spacingDistance.coerceAtLeast(1f)
    private val laneCount = lanes.coerceIn(1, 6)
    private val laneList: List<Lane> = List(laneCount) { idx ->
        val phase = if (randomizeLanePhase) {
            MathUtils.getRandomNumberInRange(0f, (2f * PI).toFloat())
        } else {
            // 均匀分布：避免随机“抖动/偏移”导致的观感不稳定
            ((2f * PI).toFloat() * (idx.toFloat() / laneCount.toFloat()))
        }
        Lane(travel = 0f, acc = 0f, phase = phase)
    }

    fun reset() {
        for (l in laneList) {
            l.travel = 0f
            l.acc = 0f
        }
    }

    fun advance(
        engine: CombatEngineAPI,
        amount: Float,
        line: BeamLineUtil.BeamLine,
        intensity: Float,
        timeSeconds: Float,
    ) {
        if (amount <= 0f) return

        val len = line.length
        if (len <= 32f) return

        val speedStep = (travelSpeed.coerceAtLeast(0f) * amount)
        if (speedStep <= 0.01f) return

        val t = intensity.coerceIn(0f, 1f)

        // 基于强度做一点尺寸/alpha 放大（但避免过头）。
        val a = (aSideHalf * (0.85f + 0.55f * t)).coerceAtLeast(1f)
        val b = (bAlongHalf * (0.85f + 0.55f * t)).coerceAtLeast(1f)
        val alpha = (baseColor.alpha * (0.60f + 0.55f * t)).toInt().coerceIn(0, 255)
        if (alpha <= 0) return
        val c = Color(baseColor.red, baseColor.green, baseColor.blue, alpha)

        for (lane in laneList) {
            lane.travel += speedStep
            lane.acc += speedStep

            while (lane.acc >= step) {
                lane.acc -= step

                // 在 [0, len) 上循环，让环持续“往前跑”。
                val dist = (lane.travel % len).coerceIn(0f, len)

                // 相位：用于摆动/旋进（可被禁用）
                val phase = (timeSeconds * 7.5f) + lane.phase + (dist / len) * (2f * PI).toFloat() * 2.0f

                // 让环中心绕束摆动（看起来像在束周围旋进）。
                val wobble = if (enableCenterWobble && orbitAmplitude > 0.01f) {
                    sin(phase.toDouble()).toFloat() * orbitAmplitude * (0.65f + 0.55f * t)
                } else {
                    0f
                }

                val pos = Vector2f(
                    line.from.x + line.dirUnit.x * dist + line.perpUnit.x * wobble,
                    line.from.y + line.dirUnit.y * dist + line.perpUnit.y * wobble,
                )

                // 环自身旋转：可选。禁用后环将保持与束方向一致（更贴近“参考图：沿束前进的圈”）。
                val ringFacing = if (enableRingSpin) (line.facing + (phase * 28f)) else line.facing

                OglEllipseRingRenderer.spawn(
                    engine,
                    OglEllipseRingRenderer.RingSpec(
                        center = pos,
                        facing = ringFacing,
                        aSideHalf = a,
                        bAlongHalf = b,
                        duration = duration,
                        color = c,
                        lineWidthPx = lineWidthPx,
                        segments = segments,
                        expandSpeed = expandSpeed,
                        tangentialSpeed = tangentialSpeed,
                    )
                )
            }
        }
    }
}
