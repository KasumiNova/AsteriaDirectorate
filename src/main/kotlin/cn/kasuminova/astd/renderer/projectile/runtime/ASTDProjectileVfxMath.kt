package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileHistoryNode
import org.lwjgl.util.vector.Vector2f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object ASTDProjectileVfxMath {
    fun clamp(value: Float, min: Float, max: Float): Float = min(max, max(min, value))

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp((x - edge0) / max(edge1 - edge0, 0.0001f), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun hermite01(t: Float, m0: Float, m1: Float): Float {
        val u = clamp(t, 0f, 1f)
        val u2 = u * u
        val u3 = u2 * u
        return (u3 - 2f * u2 + u) * m0 + (-2f * u3 + 3f * u2) + (u3 - u2) * m1
    }

    fun shaderNoise(x: Float, y: Float): Float {
        val value = kotlin.math.sin(x.toDouble() * 127.1 + y.toDouble() * 311.7) * 43758.5453123
        return (value - kotlin.math.floor(value)).toFloat()
    }

    fun layeredNoise(x: Float, y: Float): Float {
        val xd = x.toString().toDouble()
        val yd = y.toString().toDouble()
        return (shaderNoiseDouble(xd, yd) * 0.52 +
            shaderNoiseDouble(xd * 2.13 + 17.4, yd * 2.31 - 9.2) * 0.32 +
            shaderNoiseDouble(xd * 4.07 - 3.8, yd * 3.63 + 21.6) * 0.16).toFloat()
    }

    fun sampleHistoryAt(history: List<Vector2f>, targetDist: Float, histPixelsPerEntry: Float): Vector2f {
        if (history.isEmpty()) return Vector2f(0f, 0f)
        if (targetDist <= 0f || history.size == 1) return Vector2f(history.first())
        val rawIdx = targetDist / max(histPixelsPerEntry, 0.1f)
        val idx0 = floor(rawIdx).toInt()
        val idx1 = idx0 + 1
        if (idx0 >= history.lastIndex) return Vector2f(history.last())
        val frac = rawIdx - idx0
        val p0 = history[idx0]
        val p1 = history[idx1]
        return Vector2f(lerp(p0.x, p1.x, frac), lerp(p0.y, p1.y, frac))
    }

    fun sampleHistoryAtNodes(history: List<ASTDProjectileHistoryNode>, targetDist: Float, histPixelsPerEntry: Float): Vector2f {
        return sampleHistoryAt(history.map { it.location }, targetDist, histPixelsPerEntry)
    }

    fun ribbonWave(type: String, worldX: Float, timeSeconds: Float, frequency: Float, speed: Float, amplitude: Float, noiseScale: Float, syncCode: Int, softening: Float): Float {
        val worldXd = worldX.toString().toDouble()
        val timeD = timeSeconds.toString().toDouble()
        val frequencyD = frequency.toString().toDouble()
        val speedD = speed.toString().toDouble()
        val amplitudeD = amplitude.toString().toDouble()
        val noiseScaleD = noiseScale.toString().toDouble()
        val softeningD = softening.toString().toDouble()
        val worldTimePhase = timeD * speedD * 0.18 + syncCode * 0.05
        return when (type.lowercase()) {
            "noise" -> {
                val noiseVal = layeredNoiseDouble(worldXd * noiseScaleD * 0.005, worldTimePhase)
                ((smoothstepDouble(0.12, 0.88, noiseVal) - 0.5) * 2.0 * amplitudeD * softeningD).toFloat()
            }
            "zigzag" -> {
                val phase = worldXd * frequencyD * 0.01 + timeD * speedD
                val raw = 1.0 - 4.0 * kotlin.math.abs(fractDouble(phase + 0.25) - 0.5)
                ((if (raw >= 0.0) smoothstepDouble(0.0, 1.0, raw) else -smoothstepDouble(0.0, 1.0, -raw)) * amplitudeD * softeningD).toFloat()
            }
            else -> {
                val phase = worldXd * frequencyD * 0.01 + timeD * speedD
                (kotlin.math.sin(phase * Math.PI * 2.0 + syncCode * 0.05) * amplitudeD * softeningD).toFloat()
            }
        }
    }

    fun ribbonDistanceWave(type: String, trailT: Float, timeSeconds: Float, frequency: Float, speed: Float, amplitude: Float, noiseScale: Float, syncCode: Int, softening: Float): Float {
        val distanceD = trailT.coerceIn(0f, 1f).toString().toDouble()
        val timeD = timeSeconds.toString().toDouble()
        val frequencyD = frequency.toString().toDouble()
        val speedD = speed.toString().toDouble()
        val amplitudeD = amplitude.toString().toDouble()
        val noiseScaleD = noiseScale.toString().toDouble()
        val softeningD = softening.toString().toDouble()
        val phase = distanceD * frequencyD + syncCode * 0.031
        return when (type.lowercase()) {
            "noise" -> {
                val drift = timeD * speedD * 0.035
                val spread = kotlin.math.max(noiseScaleD, 0.0001) * 0.12
                val coarse = layeredNoiseDouble(phase * spread + drift, syncCode * 0.071)
                val fine = layeredNoiseDouble(phase * spread * 1.7 + drift * 0.62 + 9.4, syncCode * 0.113)
                val noiseVal = coarse * 0.72 + fine * 0.28
                ((smoothstepDouble(0.18, 0.82, noiseVal) - 0.5) * 2.0 * amplitudeD * softeningD).toFloat()
            }
            "zigzag" -> {
                val raw = 1.0 - 4.0 * kotlin.math.abs(fractDouble(phase + timeD * speedD * 0.12 + 0.25) - 0.5)
                ((if (raw >= 0.0) smoothstepDouble(0.0, 1.0, raw) else -smoothstepDouble(0.0, 1.0, -raw)) * amplitudeD * softeningD).toFloat()
            }
            else -> {
                (kotlin.math.sin((phase + timeD * speedD * 0.12) * Math.PI * 2.0) * amplitudeD * softeningD).toFloat()
            }
        }
    }

    fun dissolve(elapsed: Float, duration: Float, dissolveStartRatio: Float): Float {
        val start = duration * dissolveStartRatio
        return clamp((elapsed - start) / max(duration - start, 0.0001f), 0f, 1f)
    }

    fun beamAlpha(dissolveValue: Float): Float = clamp((1f - dissolveValue) * (1f - dissolveValue) * (1f - dissolveValue * 0.48f), 0f, 1f)

    fun visibleLength(baseLength: Float, dissolveValue: Float): Float = baseLength * (1f + (0.08f - 1f) * dissolveValue)

    fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    private fun fract(value: Float): Float = value - floor(value)

    private fun shaderNoiseDouble(x: Double, y: Double): Double {
        val value = kotlin.math.sin(x * 127.1 + y * 311.7) * 43758.5453123
        return value - kotlin.math.floor(value)
    }

    private fun layeredNoiseDouble(x: Double, y: Double): Double {
        return shaderNoiseDouble(x, y) * 0.52 +
            shaderNoiseDouble(x * 2.13 + 17.4, y * 2.31 - 9.2) * 0.32 +
            shaderNoiseDouble(x * 4.07 - 3.8, y * 3.63 + 21.6) * 0.16
    }

    private fun smoothstepDouble(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / kotlin.math.max(edge1 - edge0, 0.0001)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun fractDouble(value: Double): Double = value - kotlin.math.floor(value)
}
