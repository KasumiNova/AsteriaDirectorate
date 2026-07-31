package cn.kasuminova.astd.impl.render

import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 光束节点共用的标量/向量小工具（螺旋、回流粒子、环流等都用得到）。纯函数，无状态。 */
internal object BeamMath {

    fun rand01(): Float = Math.random().toFloat()
    fun randSigned01(): Float = rand01() * 2f - 1f
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun smoothstep01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun facingUnitX(facing: Float): Float = cos(Math.toRadians(facing.toDouble())).toFloat()
    fun facingUnitY(facing: Float): Float = sin(Math.toRadians(facing.toDouble())).toFloat()

    fun rotate2D(x: Float, y: Float, rad: Float): Pair<Float, Float> {
        val c = cos(rad)
        val s = sin(rad)
        return Pair(x * c - y * s, x * s + y * c)
    }

    fun normalize(x: Float, y: Float): Pair<Float, Float> {
        val len = sqrt((x * x + y * y).coerceAtLeast(0.000001f))
        return Pair(x / len, y / len)
    }

    fun colorLerp(a: Color, b: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * tt).toInt().coerceIn(0, 255),
            (a.green + (b.green - a.green) * tt).toInt().coerceIn(0, 255),
            (a.blue + (b.blue - a.blue) * tt).toInt().coerceIn(0, 255),
            (a.alpha + (b.alpha - a.alpha) * tt).toInt().coerceIn(0, 255),
        )
    }
}
