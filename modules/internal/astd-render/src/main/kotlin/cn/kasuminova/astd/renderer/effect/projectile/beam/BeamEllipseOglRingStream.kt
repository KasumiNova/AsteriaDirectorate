package cn.kasuminova.astd.renderer.effect.projectile.beam

import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.sin

internal class BeamEllipseOglRingStream(
    private val spacingDistance: Float,
    private val travelSpeed: Float,
    private val aSideHalf: Float,
    private val bAlongHalf: Float,
    private val duration: Float,
    private val baseColor: Color,
    private val lineWidthPx: Float,
    private val segments: Int,
    private val expandSpeed: Float,
    private val tangentialSpeed: Float,
    private val orbitAmplitude: Float,
    lanes: Int = 3,
    private val enableCenterWobble: Boolean = true,
    private val enableRingSpin: Boolean = true,
    private val randomizeLanePhase: Boolean = true,
) {

    private data class Lane(var travel: Float, var acc: Float, val phase: Float)

    private val step = spacingDistance.coerceAtLeast(1f)
    private val laneCount = lanes.coerceIn(1, 6)
    private val laneList: List<Lane> = List(laneCount) { idx ->
        val phase = if (randomizeLanePhase) {
            MathUtils.getRandomNumberInRange(0f, (2f * PI).toFloat())
        } else {
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

                val dist = (lane.travel % len).coerceIn(0f, len)

                val phase = (timeSeconds * 7.5f) + lane.phase + (dist / len) * (2f * PI).toFloat() * 2.0f

                val wobble = if (enableCenterWobble && orbitAmplitude > 0.01f) {
                    sin(phase.toDouble()).toFloat() * orbitAmplitude * (0.65f + 0.55f * t)
                } else {
                    0f
                }

                val pos = Vector2f(
                    line.from.x + line.dirUnit.x * dist + line.perpUnit.x * wobble,
                    line.from.y + line.dirUnit.y * dist + line.perpUnit.y * wobble,
                )

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
