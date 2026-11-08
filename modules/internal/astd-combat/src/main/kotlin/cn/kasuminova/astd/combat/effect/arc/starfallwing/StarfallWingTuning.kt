package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt

/**
 * 坠星残翼（星翼-级内置主炮）机制数值。
 * 虚数充能/星羽弹幕/折跃相关数值见 [cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning]。
 */
object StarfallWingTuning {

    const val WEAPON_ID = "astd_starfall_wing"
    const val SHOT_SPEC_ID = "astd_starfall_wing_shot"
    const val EMPOWERED_SHOT_SPEC_ID = "astd_starfall_wing_empowered_shot"
    const val MOTE_WEAPON_ID = "astd_starfall_wing_mote_launcher"
    const val MOTE_SPEC_ID = "astd_starfall_wing_mote"

    /** 断熵强化贯通弹标记（customData）。 */
    const val EMPOWERED_MARK_KEY = "astd_starfall_wing_empowered"

    // ---------- 断熵强化贯通弹 ----------

    /** 常态触发周期（每第 N 发升格）：迟暮 6 / 砺刃 6 / 破晓 4。 */
    val EMPOWERED_PERIOD = ScalingEntry(6f, 6f, 4f)

    /** 超翼过载期触发周期：迟暮 4 / 砺刃 3 / 破晓 2。 */
    val EMPOWERED_PERIOD_OVERDRIVE = ScalingEntry(4f, 3f, 2f)

    const val EMPOWERED_DAMAGE_MULT = 2f

    const val EMPOWERED_EMP = 300f

    const val EMPOWERED_SIZE_MULT = 1.5f

    const val PIERCE_TICK_SECONDS = 0.05f

    const val PIERCE_TICK_RATIO = 0.25f

    fun pierceTickDamage(panel: Float): Float = panel * PIERCE_TICK_RATIO

    fun pierceTickEmp(empPanel: Float): Float = empPanel * PIERCE_TICK_RATIO

    // ---------- 振频蚀穿 ----------

    const val EROSION_TAKEN_PER_STACK = 0.02f

    const val EROSION_MAX_STACKS = 15

    const val EROSION_DURATION_SECONDS = 4f

    /** 每层 +2% 加法劣化最终护盾承伤效率，劣化后不超过 1.0。 */
    fun erosionShieldMult(baseTaken: Float, stacks: Int): Float {
        if (baseTaken <= 0f || baseTaken >= 1.0f) return 1f
        val target = (baseTaken + EROSION_TAKEN_PER_STACK * stacks.coerceAtLeast(0))
            .coerceAtMost(1.0f)
        return target / baseTaken
    }

    // ---------- 几何工具（穿透扫掠用） ----------

    fun distanceToSegment(p: Vector2f, a: Vector2f, b: Vector2f): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq <= 1e-6f) {
            val dx = p.x - a.x
            val dy = p.y - a.y
            return sqrt(dx * dx + dy * dy)
        }
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
        val cx = a.x + abx * t
        val cy = a.y + aby * t
        val dx = p.x - cx
        val dy = p.y - cy
        return sqrt(dx * dx + dy * dy)
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
            val intersects = (a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            if (intersects) inside = !inside
        }
        return inside
    }
}
