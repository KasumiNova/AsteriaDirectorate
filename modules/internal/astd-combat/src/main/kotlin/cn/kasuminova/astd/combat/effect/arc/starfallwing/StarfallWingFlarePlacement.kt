package cn.kasuminova.astd.combat.effect.arc.starfallwing

import org.lwjgl.util.vector.Vector2f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class StarfallWingFlarePlacement(
    private val impact: Vector2f,
    center: Vector2f,
    incomingFacing: Float,
) {
    private val direction: Vector2f
    private val outwardFacing: Float
    private var extent = 0f

    init {
        val dx = impact.x - center.x
        val dy = impact.y - center.y
        val length = sqrt(dx * dx + dy * dy)
        direction = if (length > 1e-4f) {
            Vector2f(dx / length, dy / length)
        } else {
            val angle = Math.toRadians((incomingFacing + 180f).toDouble())
            Vector2f(cos(angle).toFloat(), sin(angle).toFloat())
        }
        outwardFacing = Math.toDegrees(atan2(direction.y.toDouble(), direction.x.toDouble())).toFloat()
    }

    fun include(point: Vector2f) {
        extent = maxOf(extent, projection(point))
    }

    fun includeCircle(center: Vector2f, radius: Float) {
        extent = maxOf(extent, projection(center) + radius)
    }

    fun location(clearance: Float): Vector2f = Vector2f(
        impact.x + direction.x * (extent + clearance),
        impact.y + direction.y * (extent + clearance),
    )

    fun facing(randomUnit: Float): Float = outwardFacing + (randomUnit * 2f - 1f) * 60f

    private fun projection(point: Vector2f): Float =
        (point.x - impact.x) * direction.x + (point.y - impact.y) * direction.y
}
