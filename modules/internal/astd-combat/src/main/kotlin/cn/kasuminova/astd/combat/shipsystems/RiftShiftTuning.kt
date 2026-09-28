package cn.kasuminova.astd.combat.shipsystems

import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 裂隙折跃（XC-002 星翼舰船系统，规格 blue/10-unique.md XC-002 节）的机制数值声明与纯函数。
 *
 * 动机：折跃距离/时长、变距钳制、伤害点位布设与逐点位接触判定、闭合扫掠推进与虚空锚雷布点
 * 集中在一处声明；全部为纯函数/常量，供 stats 脚本/系统 AI/每帧插件调用并由单元测试直接驱动。
 *
 * 伤害模型（离散点位，固定值不走难度缩放）：裂隙沿路径每 [ANCHOR_SPACING]su 一个伤害点位
 * （与虚空锚雷布点同序列），点位以自身位置为圆心、[CONTACT_RANGE]su 为半径做接触判定。
 * 三相位点位状态机：成形拉开（点位随拉开进度逐个激活，激活点位每 [GRAZE_TICK_SECONDS]s
 * 一拍 [GRAZE_DAMAGE] 能量）→ 驻留（全部点位每 [CONTACT_TICK_SECONDS]s 一拍 [CONTACT_DAMAGE]
 * 能量）→ 闭合拉上（扫掠头自起点向终点同向推进，被扫过的点位在扫过时刻结算一拍
 * [GRAZE_DAMAGE] 后失效，未扫到点位维持驻留节拍）。
 *
 * 时长口径（舰船时间，与原版相位系统 ChargeTracker 同一时钟——相位三倍时流下两者天然对齐）：
 * 拉开时长 = [shiftDurationSeconds]（按折跃距离占比线性映射，满距 [SHIFT_DURATION_MAX]s），
 * 闭合拉上时长与拉开相同；[CLOSURE_DELAY_SECONDS] 为驻留时长。
 */
object RiftShiftTuning {

    /** 最大折跃距离基准（su）：实际最大距离 = 基准 × 系统射程加成（mutableStats.systemRangeBonus）。 */
    const val SHIFT_DISTANCE = 1000f

    /** 满距拉开时长（秒，舰船时间）：动态激活时长的名义最大值，.system active 按此对齐。 */
    const val SHIFT_DURATION_MAX = 1.0f

    /** 变距折跃下限占比：玩家鼠标选距/AI 选距最短钳到最大距离的 25%。 */
    const val MIN_SHIFT_FRACTION = 0.25f

    /** 点位接触判定半径（su）：目标心到伤害点位的距离 ≤ 该值判定接触。 */
    const val CONTACT_RANGE = 100f

    /** 驻留接触结算节拍（秒，舰船时间，拉开完成后至闭合开始前）。 */
    const val CONTACT_TICK_SECONDS = 0.2f

    /** 驻留接触单次能量伤害（每接触目标每拍，单点 applyDamage）。 */
    const val CONTACT_DAMAGE = 200f

    /** 掠过结算节拍（秒，舰船时间，成形拉开期激活点位共用）。 */
    const val GRAZE_TICK_SECONDS = 0.1f

    /** 掠过单次能量伤害（每接触目标每拍，单点 applyDamage；闭合扫过点位的即席结算同口径）。 */
    const val GRAZE_DAMAGE = 400f

    /** 拉开完成后裂隙驻留时长（秒，舰船时间）。 */
    const val CLOSURE_DELAY_SECONDS = 5.0f

    /** 伤害点位/虚空锚雷布点间距（su）：沿裂隙路径每 100su 一个，告警圈（=[CONTACT_RANGE]）恰好满覆盖路径带。 */
    const val ANCHOR_SPACING = 100f

    /** 虚空锚雷面板伤害（裁定值）：高到原版 AI 判定为致命威胁主动规避；地雷永不引爆，纯威慑。 */
    const val MINE_PANEL_DAMAGE = 4000f

    /** 虚空锚雷发射器武器 id（隐藏武器，脚本 spawn 专用）。 */
    const val MINE_WEAPON_ID = "astd_rift_mine_layer"

    /** 锚雷飞行时间余量（秒）：覆盖相位时流最低的极端情形（时流 1× 时裂隙世界寿命 = 全寿命）。 */
    const val MINE_LIFE_MARGIN_SECONDS = 1f

    /** 折跃方向（纯函数）：飞行向量方向；速度近零（≤1su/s）回退舰船朝向（裁定）。 */
    fun shiftDirection(velocity: Vector2f, facingDeg: Float): Vector2f {
        if (velocity.lengthSquared() > 1f) {
            val v = Vector2f(velocity)
            v.normalise()
            return v
        }
        val rad = Math.toRadians(facingDeg.toDouble())
        return Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
    }

    /**
     * 变距折跃长度（纯函数）：目标点距离钳进 [最大×[MIN_SHIFT_FRACTION], 最大]。
     * [maxDistance] 调用方按 基准×systemRangeBonus 折算；[targetDistance] 为舰心到鼠标/AI 目标点距离。
     */
    fun resolveShiftDistance(maxDistance: Float, targetDistance: Float): Float =
        targetDistance.coerceIn(maxDistance * MIN_SHIFT_FRACTION, maxDistance)

    /**
     * 拉开（成形）时长（纯函数，秒，舰船时间）：按折跃距离占最大距离的比例线性映射——
     * 满距 → [SHIFT_DURATION_MAX]s，最短（25% 占比）→ 0.25×[SHIFT_DURATION_MAX]s。
     * 依据：折跃是匀速感的裂隙拉开过程，拉开耗时与拉开长度成正比最直觉可读；
     * 闭合拉上时长复用本值（同向同速收回）。
     */
    fun shiftDurationSeconds(maxDistance: Float, shiftDistance: Float): Float =
        SHIFT_DURATION_MAX * (shiftDistance / maxDistance).coerceIn(MIN_SHIFT_FRACTION, 1f)

    /** 裂隙全寿命（纯函数，秒，舰船时间）：拉开 + 驻留 + 闭合拉上（闭合与拉开同时长）。 */
    fun riftLifetimeSeconds(formingDuration: Float): Float =
        formingDuration * 2f + CLOSURE_DELAY_SECONDS

    /** 锚雷飞行时间（纯函数，秒，世界时钟）：裂隙全寿命 + [MINE_LIFE_MARGIN_SECONDS] 余量。 */
    fun mineFlightTimeSeconds(formingDuration: Float): Float =
        riftLifetimeSeconds(formingDuration) + MINE_LIFE_MARGIN_SECONDS

    /**
     * 路径锚点序列（纯函数，伤害点位与虚空锚雷共用）：自 [from] 沿 from→to 方向
     * 每 [ANCHOR_SPACING]su 一个，末点不超过路径全长；路径短于间距时为空。
     */
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

    /**
     * 路径推进点（纯函数）：进度 [progress]∈[0,1] 经 [easeProgress] 缓动后沿 from→to 取点。
     * 成形拉开末端与闭合扫掠头共用（拉开/拉上同向同曲线）。
     */
    fun pathPointAt(from: Vector2f, to: Vector2f, progress: Float): Vector2f {
        val p = easeProgress(progress)
        return Vector2f(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
    }

    /** 缓动后的路径进度（纯函数）：[progress]∈[0,1] → 路径里程占比∈[0,1]，点位激活/扫掠判定用。 */
    fun easedPathFraction(progress: Float): Float = easeProgress(progress)

    /**
     * 点位接触判定（纯函数）：[points] 中距目标心 ≤ [CONTACT_RANGE] 的最近点位；
     * 无接触返回 null。单目标单拍只取一个点位结算（邻近点位半径交叠不重复结算）。
     */
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

    /**
     * 折跃位移缓动（纯函数）：smoothstep 3t²−2t³，[t] 钳 [0,1]。
     * 端点 0→0 / 1→1、中点 0.5→0.5、单调不减；端点导数为零——速度即位移导数，
     * 曲线插值后起步加速/到达减速自然成立，无需额外速度窗口。
     */
    fun easeProgress(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
