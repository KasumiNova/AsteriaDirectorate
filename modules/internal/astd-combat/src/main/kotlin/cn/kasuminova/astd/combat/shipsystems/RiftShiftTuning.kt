package cn.kasuminova.astd.combat.shipsystems

import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

object RiftShiftTuning {

    const val SHIFT_DISTANCE = 1000f

    const val SHIFT_DURATION_MAX = 1.0f

    const val MIN_SHIFT_FRACTION = 0.25f

    const val CONTACT_RANGE = 100f

    const val CONTACT_TICK_SECONDS = 0.2f

    const val CONTACT_DAMAGE = 200f

    const val GRAZE_TICK_SECONDS = 0.1f

    const val GRAZE_DAMAGE = 400f

    const val CLOSURE_DELAY_SECONDS = 5.0f

    const val ANCHOR_SPACING = 100f

    const val MINE_PANEL_DAMAGE = 4000f

    const val MINE_WEAPON_ID = "astd_rift_mine_layer"

    const val MINE_LIFE_MARGIN_SECONDS = 1f

    fun shiftDirection(velocity: Vector2f, facingDeg: Float): Vector2f {
        if (velocity.lengthSquared() > 1f) {
            val v = Vector2f(velocity)
            v.normalise()
            return v
        }
        val rad = Math.toRadians(facingDeg.toDouble())
        return Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
    }

    fun resolveShiftDistance(maxDistance: Float, targetDistance: Float): Float =
        targetDistance.coerceIn(maxDistance * MIN_SHIFT_FRACTION, maxDistance)

    fun shiftDurationSeconds(maxDistance: Float, shiftDistance: Float): Float =
        SHIFT_DURATION_MAX * (shiftDistance / maxDistance).coerceIn(MIN_SHIFT_FRACTION, 1f)

    fun riftLifetimeSeconds(formingDuration: Float): Float =
        formingDuration * 2f + CLOSURE_DELAY_SECONDS

    fun mineFlightTimeSeconds(formingDuration: Float): Float =
        riftLifetimeSeconds(formingDuration) + MINE_LIFE_MARGIN_SECONDS

    fun anchorPoints(from: Vector2f, to: Vector2f): List<Vector2f> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < ANCHOR_SPACING) return emptyList()
        val ux = dx / len
        val uy = dy / len
        val count = floor(len / ANCHOR_SPACING).toInt()
        return (1..count).map { i ->
            Vector2f(from.x + ux * ANCHOR_SPACING * i, from.y + uy * ANCHOR_SPACING * i)
        }
    }

    fun pathPointAt(from: Vector2f, to: Vector2f, progress: Float): Vector2f {
        val p = easeProgress(progress)
        return Vector2f(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
    }

    fun easedPathFraction(progress: Float): Float = easeProgress(progress)

    fun nearestAnchorInRange(targetLoc: Vector2f, points: List<Vector2f>): Vector2f? {
        var best: Vector2f? = null
        var bestDistSq = CONTACT_RANGE * CONTACT_RANGE
        for (point in points) {
            val dx = targetLoc.x - point.x
            val dy = targetLoc.y - point.y
            val distSq = dx * dx + dy * dy
            if (distSq <= bestDistSq) {
                bestDistSq = distSq
                best = point
            }
        }
        return best
    }

    fun easeProgress(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
