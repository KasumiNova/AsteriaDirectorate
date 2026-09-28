package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 裂隙折跃（XC-002 星翼舰船系统，规格 blue/10-unique.md XC-002 节）的机制数值声明与纯函数。
 *
 * 动机：折跃距离/时长、裂隙接触判定、闭合爆点序列与难度缩放锚点集中在一处声明；
 * 爆点序列、折跃方向与接触判定均为纯函数，供 stats 脚本/每帧插件调用并由单元测试直接驱动。
 *
 * 难度缩放走 D13 双轨三锚点（[ScalingEntry]，isPlayer = owner==0）：
 * 裂隙接触每秒伤害 100/200/500、闭合爆炸每次伤害 400/800/2000。
 * 裁定值（设计案未给）：裂隙接触半宽 40su、闭合爆炸接触半径 80su、接触结算节拍 0.2s。
 */
object RiftShiftTuning {

    /** 折跃距离（su）。 */
    const val SHIFT_DISTANCE = 1200f

    /** 折跃时长（秒，与 .system active 窗口一致）。 */
    const val SHIFT_DURATION = 0.5f

    /** 裂隙接触半宽（su，裁定值）：舰心到裂隙段距离 ≤ 半宽 + 舰船碰撞半径 判定接触。 */
    const val RIFT_HALF_WIDTH = 40f

    /** 裂隙接触结算节拍（秒）。 */
    const val CONTACT_TICK_SECONDS = 0.2f

    /** 折跃完成后裂隙闭合延迟（秒）。 */
    const val CLOSURE_DELAY_SECONDS = 5.0f

    /** 闭合爆点间距（su）：沿路径每 100su 爆炸一次。 */
    const val BLAST_SPACING = 100f

    /** 闭合爆炸接触半径（su，裁定值 = 裂隙半宽 ×2）。 */
    const val BLAST_RADIUS = 80f

    /** 裂隙接触每秒能量伤害（三锚点 100/200/500）。 */
    val CONTACT_DAMAGE_PER_SECOND = ScalingEntry(100f, 200f, 500f)

    /** 闭合爆炸每次能量伤害（三锚点 400/800/2000）。 */
    val CLOSURE_BLAST_DAMAGE = ScalingEntry(400f, 800f, 2000f)

    /**
     * 闭合爆点序列（纯函数）：自 [from] 沿 from→to 方向每 [BLAST_SPACING]su 一点，
     * 末点不超过路径全长（800su 路径 → 8 个爆点）；路径短于间距时为空。
     */
    fun closureBlastPoints(from: Vector2f, to: Vector2f): List<Vector2f> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < BLAST_SPACING) return emptyList()
        val ux = dx / len
        val uy = dy / len
        val count = floor(len / BLAST_SPACING).toInt()
        return (1..count).map { i ->
            Vector2f(from.x + ux * BLAST_SPACING * i, from.y + uy * BLAST_SPACING * i)
        }
    }

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

    /** 裂隙接触判定（纯函数）：舰心到裂隙段的最短距离 ≤ 半宽 + 舰船碰撞半径。 */
    fun contactsRift(shipLoc: Vector2f, shipRadius: Float, from: Vector2f, to: Vector2f): Boolean =
        distanceToSegment(shipLoc, from, to) <= RIFT_HALF_WIDTH + shipRadius

    /**
     * 折跃位移缓动（纯函数）：smoothstep 3t²−2t³，[t] 钳 [0,1]。
     * 端点 0→0 / 1→1、中点 0.5→0.5、单调不减；端点导数为零——速度即位移导数，
     * 曲线插值后起步加速/到达减速自然成立，无需额外速度窗口。
     */
    fun easeProgress(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 点到线段的最近点（纯函数）：伤害落点方向判定用；退化为点的线段返回端点 a。 */
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
