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

/**
 * 剧情星系落位算法（纯函数层，不触碰 Global，可直接单测）。
 *
 * 约束（07 文档「遗址星系规格」）：
 * - 第二章双星系距剧情主星系约 [CH2_DISTANCE_FROM_MAIN] su；
 * - 固定位于边缘星区：距星区中心（原点）≥ [EDGE_MIN_RADIUS] su；
 * - 与既有剧情星系的最小角距 [MIN_ANGULAR_SEPARATION_DEG]°（以星区中心为顶点的夹角）。
 */
object StoryPlacement {

    /** 简易二维向量（纯数据，避免测试环境加载 lwjgl Vector2f）。 */
    data class Vec(val x: Float, val y: Float) {
        fun length(): Float = sqrt(x * x + y * y)
        fun minus(o: Vec): Vec = Vec(x - o.x, y - o.y)
        fun plus(o: Vec): Vec = Vec(x + o.x, y + o.y)

        companion object {
            val ZERO = Vec(0f, 0f)
        }
    }

    /** 第二章星系与主星系的标称距离（su，约 30 光年）。 */
    const val CH2_DISTANCE_FROM_MAIN: Float = 60000f

    /** 第二章星系与主星系的距离容差（±）。 */
    const val CH2_DISTANCE_TOLERANCE: Float = 8000f

    /** 边缘星区最小半径（距原点，su）。 */
    const val EDGE_MIN_RADIUS: Float = 40000f

    /** 剧情星系间最小角距（以原点为顶点，度）。 */
    const val MIN_ANGULAR_SEPARATION_DEG: Float = 35f

    /** 主星系与既有原版星系的最小间距（su），避免压进核心星区。 */
    const val MAIN_MIN_CLEARANCE: Float = 8000f

    /** 主星系候选点半径上限（su，核心星区外缘一圈）。 */
    const val MAIN_MAX_RADIUS: Float = 20000f

    private const val MAX_TRIES = 64

    /** 两向量（相对原点）的夹角（度）；任一向量退化（零长度，无方向）时角距约束无效，返回 180。 */
    fun angleBetweenDeg(a: Vec, b: Vec): Float {
        val la = a.length()
        val lb = b.length()
        if (la <= 0f || lb <= 0f) return 180f
        val cosv = ((a.x * b.x + a.y * b.y) / (la * lb)).coerceIn(-1f, 1f)
        return Math.toDegrees(Math.acos(cosv.toDouble())).toFloat()
    }

    /**
     * 主星系落位：在半径 [0, [MAIN_MAX_RADIUS]] 内取种子随机点，
     * 与全部既有星系保持 [MAIN_MIN_CLEARANCE] 以上间距；超限尝试后取最后一个候选（调用侧记日志）。
     */
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

    /**
     * 第二章双星系落位：以主星系为基点取 [CH2_DISTANCE_FROM_MAIN]±容差的方向随机点，
     * 双星系均满足边缘半径约束，且与主星系/彼此相对原点的角距 ≥ [MIN_ANGULAR_SEPARATION_DEG]。
     *
     * 确定性：同一种子输出相同；[MAX_TRIES] 内未命中时抛 [IllegalStateException]
     * （几何上主星系在核心区时几乎不会失败，失败即视为数据异常，由调用侧记日志）。
     *
     * @return 星坠 / 紫菀 两星系的落位（顺序固定）
     */
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
            if (existing.any { angleBetweenDeg(candidate, it) < MIN_ANGULAR_SEPARATION_DEG }) return@repeat
            return candidate
        }
        return null
    }

    /** 距离约束校验（测试与调试断言用）。 */
    fun chapter2ConstraintsSatisfied(mainLoc: Vec, starfall: Vec, aster: Vec): Boolean {
        val distOk = { v: Vec -> abs(v.minus(mainLoc).length() - CH2_DISTANCE_FROM_MAIN) <= CH2_DISTANCE_TOLERANCE }
        if (!distOk(starfall) || !distOk(aster)) return false
        if (starfall.length() < EDGE_MIN_RADIUS || aster.length() < EDGE_MIN_RADIUS) return false
        if (angleBetweenDeg(starfall, aster) < MIN_ANGULAR_SEPARATION_DEG) return false
        if (angleBetweenDeg(starfall, mainLoc) < MIN_ANGULAR_SEPARATION_DEG) return false
        if (angleBetweenDeg(aster, mainLoc) < MIN_ANGULAR_SEPARATION_DEG) return false
        return true
    }
}
