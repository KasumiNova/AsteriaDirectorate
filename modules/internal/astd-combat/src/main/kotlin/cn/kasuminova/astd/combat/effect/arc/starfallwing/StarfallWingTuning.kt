package cn.kasuminova.astd.combat.effect.arc.starfallwing

import org.lwjgl.util.vector.Vector2f

object StarfallWingTuning {

    const val WEAPON_ID = "astd_starfall_wing"
    const val SHOT_SPEC_ID = "astd_starfall_wing_shot"
    const val MOTE_WEAPON_ID = "astd_starfall_wing_mote_launcher"
    const val MOTE_SPEC_ID = "astd_starfall_wing_mote"

    const val PIERCE_TICK_SECONDS = 0.05f

    const val PIERCE_TICK_RATIO = 0.2f

    const val PIERCE_EMP_RATIO = 0.2f

    const val MOTE_INTERVAL_SECONDS = 0.2f

    const val MOTE_DAMAGE_RATIO = 0.2f

    const val MOTE_STACKS_ON_SHIELD = 0.5f

    const val MOTE_SIDE_SPEED = 250f

    const val MOTE_TRACK_DELAY_SECONDS = 0.5f

    const val ADAPTATION_TAKEN_PER_STACK = 0.1f

    const val ADAPTATION_DECAY_PER_SECOND = 1f / 3f

    const val ADAPTATION_TAKEN_CAP = 1.0f

    fun pierceTickDamage(panel: Float): Float = panel * PIERCE_TICK_RATIO

    fun pierceTickEmp(empPanel: Float): Float = empPanel * PIERCE_EMP_RATIO

    fun moteDamage(mainPanel: Float): Float = mainPanel * MOTE_DAMAGE_RATIO

    fun moteTrackingActive(flightTimeSeconds: Float): Boolean = flightTimeSeconds >= MOTE_TRACK_DELAY_SECONDS

    fun adaptationShieldMult(baseTaken: Float, stacks: Float): Float {
        if (baseTaken <= 0f || baseTaken >= ADAPTATION_TAKEN_CAP) return 1f
        val target = (baseTaken + ADAPTATION_TAKEN_PER_STACK * stacks.coerceAtLeast(0f))
            .coerceAtMost(ADAPTATION_TAKEN_CAP)
        return target / baseTaken
    }

    fun distanceToSegment(p: Vector2f, a: Vector2f, b: Vector2f): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq <= 1e-6f) {
            val dx = p.x - a.x
            val dy = p.y - a.y
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
        val cx = a.x + abx * t
        val cy = a.y + aby * t
        val dx = p.x - cx
        val dy = p.y - cy
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun closestPointOnSegment(p: Vector2f, a: Vector2f, b: Vector2f): Vector2f {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq <= 1e-6f) return Vector2f(a)
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
        return Vector2f(a.x + abx * t, a.y + aby * t)
    }

    fun pointInPolygon(p: Vector2f, segments: List<Pair<Vector2f, Vector2f>>): Boolean {
        var inside = false
        for ((a, b) in segments) {
            if ((a.y > p.y) == (b.y > p.y)) continue
            val t = (p.y - a.y) / (b.y - a.y)
            val xCross = a.x + t * (b.x - a.x)
            if (xCross > p.x) inside = !inside
        }
        return inside
    }
}
