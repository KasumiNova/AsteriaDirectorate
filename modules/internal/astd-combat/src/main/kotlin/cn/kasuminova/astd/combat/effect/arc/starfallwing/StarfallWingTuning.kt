package cn.kasuminova.astd.combat.effect.arc.starfallwing

import org.lwjgl.util.vector.Vector2f

/**
 * 坠星残翼（XC-002 星翼内置主炮，规格 blue/10-signature.md 坠星残翼节）的机制数值声明与纯函数。
 *
 * 动机：穿透高频结算节拍、子射弹散发/伤害比例与「振频适应」叠层的承伤比映射集中在一处
 * 声明；穿透 tick 伤害/EMP、子射弹伤害、承伤比映射、护盾接触行为与扫掠几何判定均为
 * 纯函数，供 OnFire/EveryFrame 调用并由单元测试直接驱动。
 *
 * 设计案锁死项（不随难度缩放）：每层承伤削弱 10%、单层 3s 消散（1/3 层/s）、主弹恒穿盾、
 * 穿透拍率 0.1s、穿透结算 20% 面板 + 20% 面板 EMP、子射弹 20% 面板与撞盾 +0.5 层。
 */
object StarfallWingTuning {

    const val WEAPON_ID = "astd_starfall_wing"
    const val SHOT_SPEC_ID = "astd_starfall_wing_shot"
    const val MOTE_WEAPON_ID = "astd_starfall_wing_mote_launcher"
    const val MOTE_SPEC_ID = "astd_starfall_wing_mote"

    /** 穿透结算节拍（秒）：穿盾 tick 与船体/装甲单点结算共用同一时间拍（首触补拍除外）。 */
    const val PIERCE_TICK_SECONDS = 0.1f

    /** 穿透单次结算伤害占面板比例（20%）：穿盾每拍、穿船体每拍单点、导弹/陨石单次穿越一次。 */
    const val PIERCE_TICK_RATIO = 0.2f

    /** 穿透单次结算附带的 EMP 占 EMP 面板比例（20%）。 */
    const val PIERCE_EMP_RATIO = 0.2f

    /** 子射弹散发节拍（秒）：主弹飞行中每拍向两侧随机散发一枚追踪子射弹。 */
    const val MOTE_INTERVAL_SECONDS = 0.2f

    /** 子射弹伤害占主弹面板比例（20%）。 */
    const val MOTE_DAMAGE_RATIO = 0.2f

    /** 子射弹命中护盾附加的振频适应层数（0.5 层）。 */
    const val MOTE_STACKS_ON_SHIELD = 0.5f

    /** 子射弹散发时的侧向初速（su/s，沿主弹飞行向量 ±90°）。 */
    const val MOTE_SIDE_SPEED = 250f

    /** 振频适应单层承伤比增量（+0.1/层，承伤比口径见 [adaptationShieldMult]）。 */
    const val ADAPTATION_TAKEN_PER_STACK = 0.1f

    /** 振频适应层数流失速率（层/秒）：单层 3s 消散 → 1/3 层每秒。 */
    const val ADAPTATION_DECAY_PER_SECOND = 1f / 3f

    /** 削弱后的承伤比上限（设计案：削弱后的护盾效率最高不会高于 1.0）。 */
    const val ADAPTATION_TAKEN_CAP = 1.0f

    /** 穿透单次结算伤害（纯函数）：面板 × [PIERCE_TICK_RATIO]。 */
    fun pierceTickDamage(panel: Float): Float = panel * PIERCE_TICK_RATIO

    /** 穿透单次结算附带 EMP（纯函数）：EMP 面板 × [PIERCE_EMP_RATIO]。 */
    fun pierceTickEmp(empPanel: Float): Float = empPanel * PIERCE_EMP_RATIO

    /** 子射弹面板伤害（纯函数）：主弹面板 × [MOTE_DAMAGE_RATIO]。 */
    fun moteDamage(mainPanel: Float): Float = mainPanel * MOTE_DAMAGE_RATIO

    /** 护盾接触行为（纯函数）：主弹恒穿透护盾；子射弹不继承穿盾，撞盾 = 阻挡消散。 */
    fun shieldContactPierces(isMote: Boolean): Boolean = !isMote

    /**
     * 振频适应护盾承伤映射（纯函数，承伤比口径）：目标承伤比 = min(base + 0.1×层数, 1.0)，
     * 修饰倍率 = 目标承伤比 / base。base >= 1 时恒 1（护盾已等额承伤，无削弱空间）；
     * base <= 0 为护盾免伤规格（原版无此口径），恒 1 不产生除零。
     */
    fun adaptationShieldMult(baseTaken: Float, stacks: Float): Float {
        if (baseTaken <= 0f || baseTaken >= ADAPTATION_TAKEN_CAP) return 1f
        val target = (baseTaken + ADAPTATION_TAKEN_PER_STACK * stacks.coerceAtLeast(0f))
            .coerceAtMost(ADAPTATION_TAKEN_CAP)
        return target / baseTaken
    }

    /**
     * 点到线段的最短距离（纯函数；RiftShiftTuning 同名实现同型注记——裂隙接触判定同款）。
     * 退化为点的线段按点到点距离处理。
     */
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

    /**
     * 点到线段的最近点（纯函数；RiftShiftTuning 同名实现同型注记——裂隙伤害落点同款）。
     * 穿透扫掠的非舰船目标接触点取数用；退化为点的线段返回端点 a。
     */
    fun closestPointOnSegment(p: Vector2f, a: Vector2f, b: Vector2f): Vector2f {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq <= 1e-6f) return Vector2f(a)
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
        return Vector2f(a.x + abx * t, a.y + aby * t)
    }

    /**
     * 点是否在多边形内（纯函数，+X 水平射线偶奇规则）：边跨越 p.y 且交点在 p 右侧则翻转。
     * 穿透扫掠的船体接触子件：多边形 = 舰船真实碰撞箱边界段集——采样点深入舰体内部也算接触
     * （中段漏拍闸门修复：只算贴面时穿越大舰中段会丢到期拍）。空段集无碰撞箱语义，恒 false，
     * 调用方在无碰撞箱时走碰撞圈近似。
     */
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
