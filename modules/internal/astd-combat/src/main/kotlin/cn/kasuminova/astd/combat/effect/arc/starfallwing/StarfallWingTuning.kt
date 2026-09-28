package cn.kasuminova.astd.combat.effect.arc.starfallwing

import org.lwjgl.util.vector.Vector2f

/**
 * 坠星残翼（XC-002 淬刃内置主炮，规格 blue/10-signature.md 坠星残翼节）的机制数值声明与纯函数。
 *
 * 动机：穿透高频结算节拍、子射弹散发/伤害比例、「振频适应」叠层的承伤比映射与穿盾门槛
 * 集中在一处声明；穿透 tick 伤害、子射弹伤害、承伤比映射与点到线段距离均为纯函数，
 * 供 OnFire/EveryFrame 调用并由单元测试直接驱动。
 *
 * 设计案锁死项（不随难度缩放）：每层承伤削弱 10%、层数流失 1 层/s、穿盾门槛 10 层、
 * 穿透/散发节拍 0.2s、子射弹 20% 面板与 +0.5 层。
 */
object StarfallWingTuning {

    const val WEAPON_ID = "astd_starfall_wing"
    const val SHOT_SPEC_ID = "astd_starfall_wing_shot"
    const val MOTE_WEAPON_ID = "astd_starfall_wing_mote_launcher"
    const val MOTE_SPEC_ID = "astd_starfall_wing_mote"

    /** 穿透结算节拍（秒）：每拍对接触的护盾/船体/装甲结算一次 [PIERCE_TICK_RATIO] 面板伤害。 */
    const val PIERCE_TICK_SECONDS = 0.2f

    /** 穿透单次结算伤害占面板比例（10%）。 */
    const val PIERCE_TICK_RATIO = 0.1f

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

    /** 振频适应层数流失速率（层/秒）。 */
    const val ADAPTATION_DECAY_PER_SECOND = 1f

    /** 削弱后的承伤比上限（设计案：削弱后的护盾效率最高不会高于 1.0）。 */
    const val ADAPTATION_TAKEN_CAP = 1.0f

    /** 主弹穿盾门槛：目标层数严格大于本值时主弹穿透护盾（子射弹不继承）。 */
    const val PIERCE_SHIELD_STACK_THRESHOLD = 10f

    /** 穿透单次结算伤害（纯函数）：面板 × [PIERCE_TICK_RATIO]。 */
    fun pierceTickDamage(panel: Float): Float = panel * PIERCE_TICK_RATIO

    /** 子射弹面板伤害（纯函数）：主弹面板 × [MOTE_DAMAGE_RATIO]。 */
    fun moteDamage(mainPanel: Float): Float = mainPanel * MOTE_DAMAGE_RATIO

    /** 主弹是否穿透护盾（纯函数）：层数严格大于 [PIERCE_SHIELD_STACK_THRESHOLD]。 */
    fun piercesShields(stacks: Float): Boolean = stacks > PIERCE_SHIELD_STACK_THRESHOLD

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
}
