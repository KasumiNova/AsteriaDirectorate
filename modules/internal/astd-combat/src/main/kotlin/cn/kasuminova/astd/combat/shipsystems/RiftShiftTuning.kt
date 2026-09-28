package cn.kasuminova.astd.combat.shipsystems

import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 裂隙折跃（XC-002 星翼舰船系统，规格 blue/10-unique.md XC-002 节）的机制数值声明与纯函数。
 *
 * 动机：折跃距离/时长、变距钳制、裂隙接触判定、闭合收拢曲线与虚空锚雷布点集中在一处声明；
 * 全部为纯函数/常量，供 stats 脚本/系统 AI/每帧插件调用并由单元测试直接驱动。
 *
 * 伤害口径（固定值，不走难度缩放）：激活成形与闭合收拢的掠过结算每 [GRAZE_TICK_SECONDS]s
 * 一拍 [GRAZE_DAMAGE] 能量、驻留接触每 [CONTACT_TICK_SECONDS]s 一拍 [CONTACT_DAMAGE] 能量，
 * 接触判定 = 目标心到裂隙段距离 ≤ [CONTACT_RANGE]su（统一口径，不叠碰撞半径）；EMP 无。
 */
object RiftShiftTuning {

    /** 最大折跃距离基准（su）：实际最大距离 = 基准 × 系统射程加成（mutableStats.systemRangeBonus）。 */
    const val SHIFT_DISTANCE = 1000f

    /** 折跃时长（秒，与 .system active 窗口一致）。 */
    const val SHIFT_DURATION = 0.7f

    /** 变距折跃下限占比：玩家鼠标选距/AI 选距最短钳到最大距离的 25%。 */
    const val MIN_SHIFT_FRACTION = 0.25f

    /** 裂隙接触判定范围（su）：目标心到裂隙段的最短距离 ≤ 该值判定接触。 */
    const val CONTACT_RANGE = 100f

    /** 驻留接触结算节拍（秒，折跃完成后至闭合开始前）。 */
    const val CONTACT_TICK_SECONDS = 0.2f

    /** 驻留接触单次能量伤害（每接触目标每拍，单点 applyDamage）。 */
    const val CONTACT_DAMAGE = 200f

    /** 掠过结算节拍（秒，激活成形掠过与闭合收拢掠过共用）。 */
    const val GRAZE_TICK_SECONDS = 0.1f

    /** 掠过单次能量伤害（每接触目标每拍，单点 applyDamage，激活/闭合共用）。 */
    const val GRAZE_DAMAGE = 400f

    /** 折跃完成后裂隙闭合延迟（秒）。 */
    const val CLOSURE_DELAY_SECONDS = 5.0f

    /** 闭合收拢时长（秒，裁定值）：裂隙从成形末端反向收拢回起点的「拉上」节奏，比成形稍慢保观感可读。 */
    const val CLOSURE_DURATION_SECONDS = 1.0f

    /** 虚空锚雷布点间距（su）：沿裂隙路径每 100su 一枚，告警圈（=[CONTACT_RANGE]）恰好满覆盖路径带。 */
    const val MINE_SPACING = 100f

    /** 虚空锚雷面板伤害（裁定值）：高到原版 AI 判定为致命威胁主动规避；地雷永不引爆，纯威慑。 */
    const val MINE_PANEL_DAMAGE = 4000f

    /** 虚空锚雷发射器武器 id（隐藏武器，脚本 spawn 专用）。 */
    const val MINE_WEAPON_ID = "astd_rift_mine_layer"

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

    /** 裂隙接触判定（纯函数）：目标心到裂隙段的最短距离 ≤ [CONTACT_RANGE]。 */
    fun contactsRift(targetLoc: Vector2f, from: Vector2f, to: Vector2f): Boolean =
        distanceToSegment(targetLoc, from, to) <= CONTACT_RANGE

    /**
     * 闭合收拢末端（纯函数）：收拢进度 [closureT]∈[0,1] 经 [easeProgress] 缓动，
     * 末端自 to 匀速感回收到 from（反向播放成形动画）；存续段恒为 from→返回点。
     */
    fun closureTip(from: Vector2f, to: Vector2f, closureT: Float): Vector2f {
        val p = easeProgress(closureT)
        return Vector2f(to.x + (from.x - to.x) * p, to.y + (from.y - to.y) * p)
    }

    /**
     * 虚空锚雷布点序列（纯函数）：自 [from] 沿 from→to 方向每 [MINE_SPACING]su 一枚，
     * 末点不超过路径全长；路径短于间距时为空。
     */
    fun mineAnchorPoints(from: Vector2f, to: Vector2f): List<Vector2f> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < MINE_SPACING) return emptyList()
        val ux = dx / len
        val uy = dy / len
        val count = floor(len / MINE_SPACING).toInt()
        return (1..count).map { i ->
            Vector2f(from.x + ux * MINE_SPACING * i, from.y + uy * MINE_SPACING * i)
        }
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

    /** 点到线段的最近点（纯函数）：伤害落点取数用；退化为点的线段返回端点 a。 */
    fun closestPointOnSegment(p: Vector2f, a: Vector2f, b: Vector2f): Vector2f {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq <= 1e-6f) return Vector2f(a)
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
        return Vector2f(a.x + abx * t, a.y + aby * t)
    }

    /**
     * 点到线段的最短距离（纯函数；StarfallWingTuning 同名实现同型注记——穿透碰撞箱同款）。
     * 退化为点的线段按点到点距离处理。
     */
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
}
