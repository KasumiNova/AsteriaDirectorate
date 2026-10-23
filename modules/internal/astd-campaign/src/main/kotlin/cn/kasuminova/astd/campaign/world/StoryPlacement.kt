package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.world.StoryPlacement.CH2_DISTANCE_FROM_MAIN
import cn.kasuminova.astd.campaign.world.StoryPlacement.EDGE_MIN_RADIUS
import cn.kasuminova.astd.campaign.world.StoryPlacement.MAIN_MAX_RADIUS
import cn.kasuminova.astd.campaign.world.StoryPlacement.MAIN_MIN_CLEARANCE
import cn.kasuminova.astd.campaign.world.StoryPlacement.MAX_TRIES
import cn.kasuminova.astd.campaign.world.StoryPlacement.MIN_ANGULAR_SEPARATION_DEG
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object StoryPlacement {

    data class Vec(val x: Float, val y: Float) {
        fun length(): Float = sqrt(x * x + y * y)
        fun minus(o: Vec): Vec = Vec(x - o.x, y - o.y)
        fun plus(o: Vec): Vec = Vec(x + o.x, y + o.y)

        companion object {
            val ZERO = Vec(0f, 0f)
        }
    }

    const val CH2_DISTANCE_FROM_MAIN: Float = 60000f

    const val CH2_DISTANCE_TOLERANCE: Float = 8000f

    const val EDGE_MIN_RADIUS: Float = 40000f

    const val MAP_MAX_X: Float = 72000f
    const val MAP_MAX_Y: Float = 42000f

    const val MIN_ANGULAR_SEPARATION_DEG: Float = 35f

    const val MAIN_MIN_CLEARANCE: Float = 8000f

    const val MAIN_MAX_RADIUS: Float = 20000f

    private const val MAX_TRIES = 64

    fun angleBetweenDeg(a: Vec, b: Vec): Float {
        val la = a.length()
        val lb = b.length()
        if (la <= 0f || lb <= 0f) return 180f
        val cosv = ((a.x * b.x + a.y * b.y) / (la * lb)).coerceIn(-1f, 1f)
        return Math.toDegrees(Math.acos(cosv.toDouble())).toFloat()
    }

    fun placeMainSystem(seed: Long, existingSystemLocs: List<Vec>): Vec {
        val rnd = Random(seed)
        var candidate = Vec.ZERO
        repeat(MAX_TRIES) {
            val angle = rnd.nextDouble() * Math.PI * 2.0
            val radius = 4000f + rnd.nextFloat() * (MAIN_MAX_RADIUS - 4000f)
            candidate = Vec((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
            if (existingSystemLocs.all { it.minus(candidate).length() >= MAIN_MIN_CLEARANCE }) {
                return candidate
            }
        }
        return candidate
    }

    fun placeChapter2Systems(mainLoc: Vec, seed: Long): Pair<Vec, Vec> {
        val rnd = Random(seed)
        val first = pickChapter2Candidate(mainLoc, rnd, existing = listOf(mainLoc))
            ?: throw IllegalStateException("第二章星系落位失败：无满足边缘约束的候选点（星坠）")
        val second = pickChapter2Candidate(mainLoc, rnd, existing = listOf(mainLoc, first))
            ?: throw IllegalStateException("第二章星系落位失败：无满足角距约束的候选点（紫菀）")
        return first to second
    }

    private fun pickChapter2Candidate(mainLoc: Vec, rnd: Random, existing: List<Vec>): Vec? {
        repeat(MAX_TRIES) {
            val angle = rnd.nextDouble() * Math.PI * 2.0
            val distance = CH2_DISTANCE_FROM_MAIN + (rnd.nextFloat() * 2f - 1f) * CH2_DISTANCE_TOLERANCE
            val candidate = mainLoc.plus(Vec((cos(angle) * distance).toFloat(), (sin(angle) * distance).toFloat()))
            if (candidate.length() < EDGE_MIN_RADIUS) return@repeat
            if (kotlin.math.abs(candidate.x) > MAP_MAX_X || kotlin.math.abs(candidate.y) > MAP_MAX_Y) return@repeat
            if (existing.any { angleBetweenDeg(candidate, it) < MIN_ANGULAR_SEPARATION_DEG }) return@repeat
            return candidate
        }
        return null
    }

    fun clampToMapBounds(loc: Vec): Vec {
        val factor = minOf(
            if (kotlin.math.abs(loc.x) > MAP_MAX_X) MAP_MAX_X / kotlin.math.abs(loc.x) else 1f,
            if (kotlin.math.abs(loc.y) > MAP_MAX_Y) MAP_MAX_Y / kotlin.math.abs(loc.y) else 1f,
        )
        return if (factor >= 1f) loc else Vec(loc.x * factor, loc.y * factor)
    }

    fun isWithinMapBounds(loc: Vec): Boolean =
        kotlin.math.abs(loc.x) <= MAP_MAX_X && kotlin.math.abs(loc.y) <= MAP_MAX_Y

    fun chapter2ConstraintsSatisfied(mainLoc: Vec, starfall: Vec, aster: Vec): Boolean {
        val distOk = { v: Vec -> abs(v.minus(mainLoc).length() - CH2_DISTANCE_FROM_MAIN) <= CH2_DISTANCE_TOLERANCE }
        if (!distOk(starfall) || !distOk(aster)) return false
        if (starfall.length() < EDGE_MIN_RADIUS || aster.length() < EDGE_MIN_RADIUS) return false
        if (!isWithinMapBounds(starfall) || !isWithinMapBounds(aster)) return false
        if (angleBetweenDeg(starfall, aster) < MIN_ANGULAR_SEPARATION_DEG) return false
        if (angleBetweenDeg(starfall, mainLoc) < MIN_ANGULAR_SEPARATION_DEG) return false
        if (angleBetweenDeg(aster, mainLoc) < MIN_ANGULAR_SEPARATION_DEG) return false
        return true
    }
}
