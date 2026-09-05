package cn.kasuminova.astd.campaign.world

import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 剧情星系超空间落位计算（纯逻辑，不依赖 Sector 状态，可单测）。
 *
 * - 主星系：位置半随机——避开已有星系，落在核心区外环一带；
 * - 遗址星系：距主星系约 60,000su，方向朝远离星区中心的边缘地带。
 */
object StoryWorldLocations {

    /** 引擎无关的二维点（生成器落地时转换为 Vector2f）。 */
    data class WorldPoint(val x: Float, val y: Float) {
        fun distanceTo(other: WorldPoint): Float {
            val dx = x - other.x
            val dy = y - other.y
            return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

        fun length(): Float = distanceTo(ORIGIN)
    }

    val ORIGIN = WorldPoint(0f, 0f)

    /** 主星系候选半径区间（距星区中心，su）。 */
    const val MAIN_MIN_RADIUS = 15000f
    const val MAIN_MAX_RADIUS = 30000f

    /** 与已有星系的最小间距（su）。 */
    const val MIN_SEPARATION = 2500f

    /** 遗址星系与主星系的基准距离（su，docs/story/07：约 60,000su / 30 光年）。 */
    const val RUIN_DISTANCE = 60000f

    /** 遗址星系距离抖动区间（±su）。 */
    const val RUIN_DISTANCE_JITTER = 4000f

    /** 两个遗址星系之间的最小角距（度），避免两者贴在一起。 */
    const val RUIN_MIN_ANGULAR_SEPARATION_DEG = 35f

    /** 边缘星区判据：遗址星系距星区中心不得小于该半径（su）。 */
    const val FRINGE_MIN_RADIUS = 40000f

    private const val MAX_ATTEMPTS = 64

    /**
     * 选取主星系落位：在 [MAIN_MIN_RADIUS, MAIN_MAX_RADIUS] 环带内随机取点，
     * 与 [occupied] 中所有已有星系保持 [MIN_SEPARATION] 以上间距；
     * 尝试耗尽时退而求其次返回间距最大的候选点（保证总能落位）。
     */
    fun pickMainSystemLocation(random: Random, occupied: List<WorldPoint>): WorldPoint {
        var fallback: WorldPoint? = null
        var fallbackMinDistance = -1f

        repeat(MAX_ATTEMPTS) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val radius = MAIN_MIN_RADIUS + random.nextFloat() * (MAIN_MAX_RADIUS - MAIN_MIN_RADIUS)
            val candidate = WorldPoint(cos(angle) * radius, sin(angle) * radius)
            val minDistance = occupied.minOfOrNull { it.distanceTo(candidate) } ?: Float.MAX_VALUE
            if (minDistance >= MIN_SEPARATION) return candidate
            if (minDistance > fallbackMinDistance) {
                fallbackMinDistance = minDistance
                fallback = candidate
            }
        }

        return requireNotNull(fallback)
    }

    /**
     * 选取遗址星系落位：以主星系为基点、朝远离星区中心的方向偏移约 [RUIN_DISTANCE]，
     * 并满足 [FRINGE_MIN_RADIUS] 边缘星区约束。
     *
     * @param ruinIndex 0 = 星坠，1 = 紫菀；两者角距不小于 [RUIN_MIN_ANGULAR_SEPARATION_DEG]。
     */
    fun pickRuinSystemLocation(
        random: Random,
        mainLocation: WorldPoint,
        ruinIndex: Int,
        occupied: List<WorldPoint>,
    ): WorldPoint {
        // 主星系相对星区中心的方位角；主星系恰好压在中心时退化为随机方向。
        val baseAngle = if (mainLocation.length() > 1f) {
            kotlin.math.atan2(mainLocation.y, mainLocation.x)
        } else {
            random.nextFloat() * Math.PI.toFloat() * 2f
        }
        // 两个遗址星系分居基线两侧（±45°~80°），天然满足最小角距。
        val side = if (ruinIndex == 0) -1f else 1f
        val angleOffset = side * (45f + random.nextFloat() * 35f) * (Math.PI.toFloat() / 180f)
        val distance = RUIN_DISTANCE + (random.nextFloat() * 2f - 1f) * RUIN_DISTANCE_JITTER

        var fallback: WorldPoint? = null
        var fallbackScore = Float.MIN_VALUE

        repeat(MAX_ATTEMPTS) {
            val attemptAngle = baseAngle + angleOffset +
                (random.nextFloat() * 2f - 1f) * 10f * (Math.PI.toFloat() / 180f)
            val candidate = WorldPoint(
                mainLocation.x + cos(attemptAngle) * distance,
                mainLocation.y + sin(attemptAngle) * distance,
            )
            val separation = occupied.minOfOrNull { it.distanceTo(candidate) } ?: Float.MAX_VALUE
            val fringe = candidate.length()
            if (separation >= MIN_SEPARATION && fringe >= FRINGE_MIN_RADIUS) return candidate

            // 评分：优先满足边缘约束，其次拉开与已有星系的间距。
            val score = minOf(fringe, FRINGE_MIN_RADIUS) + minOf(separation, MIN_SEPARATION)
            if (score > fallbackScore) {
                fallbackScore = score
                fallback = candidate
            }
        }

        return requireNotNull(fallback)
    }

    /** 校验两个遗址落位是否满足最小角距与距离约束（供生成器与测试使用）。 */
    fun ruinPlacementValid(
        mainLocation: WorldPoint,
        ruinA: WorldPoint,
        ruinB: WorldPoint,
    ): Boolean {
        val distA = mainLocation.distanceTo(ruinA)
        val distB = mainLocation.distanceTo(ruinB)
        if (distA < RUIN_DISTANCE - RUIN_DISTANCE_JITTER - 1f) return false
        if (distB < RUIN_DISTANCE - RUIN_DISTANCE_JITTER - 1f) return false
        if (distA > RUIN_DISTANCE + RUIN_DISTANCE_JITTER + 1f) return false
        if (distB > RUIN_DISTANCE + RUIN_DISTANCE_JITTER + 1f) return false
        val angA = kotlin.math.atan2(ruinA.y - mainLocation.y, ruinA.x - mainLocation.x)
        val angB = kotlin.math.atan2(ruinB.y - mainLocation.y, ruinB.x - mainLocation.x)
        var diff = Math.abs(angA - angB) % (Math.PI.toFloat() * 2f)
        if (diff > Math.PI.toFloat()) diff = Math.PI.toFloat() * 2f - diff
        return diff >= RUIN_MIN_ANGULAR_SEPARATION_DEG * (Math.PI.toFloat() / 180f) * 0.999f
    }
}
