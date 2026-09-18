package cn.kasuminova.astd.combat.effect.joint.stardust

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.Global
import java.awt.Color

/**
 * 星尘发射器（联制线两舰内置导弹）的机制数值与双线配色声明（设计案 20-joint.md §武器）。
 *
 * 动机：光尘命中结算的三项难度缩放（对导弹/战机增伤、对舰船 EMP 倍率）与 ARC/LENS 双线
 * 配色集中在此声明；OnHit 每次命中实时解析（LunaLib 设置变更即时生效）。
 *
 * 数值口径：设计案给定 v1/v5 区间（导弹 +50%~250%、战机 +100%~500%、EMP 100%~500%），
 * v2 取区间中点；全部线性步进登记 LINEAR（一次性命中结算，无叠乘放大）。
 * 玩家来源（owner == 0）固定 v2（砺刃档）。
 */
object StardustMoteTuning {

    /** 撞击导弹的额外伤害倍率（v1 +50% / v2 +150% / v5 +250%；以面板单发伤害为基数）。 */
    val ANTI_MISSILE_BONUS = ScalingEntry(0.50f, 1.50f, 2.50f)

    /** 撞击战机的额外伤害倍率（v1 +100% / v2 +300% / v5 +500%）。 */
    val ANTI_FIGHTER_BONUS = ScalingEntry(1.0f, 3.0f, 5.0f)

    /** 撞击舰船时的 EMP 电弧伤害倍率（v1 100% / v2 300% / v5 500% 面板等额 EMP）。 */
    val SHIP_EMP_MULT = ScalingEntry(1.0f, 3.0f, 5.0f)

    /** 环绕/接敌半径缺省值（su，对应武器面板射程；武器实例不可用时兜底）。 */
    const val DEFAULT_ENGAGE_RANGE = 600f

    /** 同一目标的最大追踪光尘数（防全群堆叠单目标，对齐原版 mote 口径）。 */
    const val MAX_MOTES_PER_TARGET = 2

    /** ARC 线光尘色（蓝）：jitter/EMP 电弧/引擎辉光共用色相。 */
    val ARC_COLOR = Color(100, 165, 255, 255)

    /** LENS 线光尘色（紫）。 */
    val LENS_COLOR = Color(186, 120, 255, 255)

    /** 一次命中结算所需的全部机制数值（难度解析结果；最终倍率口径，直接可用）。 */
    data class Values(
        /** 对导弹额外伤害倍率（面板基数）。 */
        val antiMissileBonus: Float,
        /** 对战机额外伤害倍率（面板基数）。 */
        val antiFighterBonus: Float,
        /** 对舰船 EMP 电弧伤害倍率（面板等额）。 */
        val shipEmpMult: Float,
    )

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        antiMissileBonus = pick(tuning, isPlayer, ANTI_MISSILE_BONUS),
        antiFighterBonus = pick(tuning, isPlayer, ANTI_FIGHTER_BONUS),
        shipEmpMult = pick(tuning, isPlayer, SHIP_EMP_MULT),
    )

    /** 弹体 spec id → 线色（ARC 蓝 / LENS 紫；未知 id 按 ARC 蓝兜底并一次性 WARN——注册表只产出两条线，未知 id 属配置异常）。 */
    fun colorForProj(projectileSpecId: String): Color {
        if (!StardustMoteIds.isStardustProj(projectileSpecId)) {
            warnOnce("unknownProj:$projectileSpecId") { "星尘光尘配色收到未知弹体 spec：$projectileSpecId，按 ARC 蓝兜底" }
        }
        return if (projectileSpecId == StardustMoteIds.PROJ_LENS) LENS_COLOR else ARC_COLOR
    }

    private val log = Global.getLogger(StardustMoteTuning::class.java)

    /** 一次性 WARN 闸集合（配置异常必须可见，不刷屏；键级去重）。 */
    private val warnedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 一次性 WARN（同一 [key] 只告警一次；供本包内各结算点复用）。 */
    fun warnOnce(key: String, message: () -> String) {
        if (warnedKeys.add(key)) {
            log.warn(message())
        }
    }

    private fun pick(tuning: DifficultyTuning, isPlayer: Boolean, entry: ScalingEntry): Float =
        if (isPlayer) entry.v2 else tuning.value(entry)
}

/** 星尘发射器双线 id 集中声明（武器/弹体 spec；.wpn 与 catalog 行引用同一组常量口径）。 */
object StardustMoteIds {
    const val WEAPON_ARC = "astd_lh_stardust_launcher_arc"
    const val WEAPON_LENS = "astd_lh_stardust_launcher_lens"
    const val PROJ_ARC = "astd_lh_stardust_mote_arc"
    const val PROJ_LENS = "astd_lh_stardust_mote_lens"

    /** 弹体 spec id 是否为本机制的光尘（pickMissileAI 覆盖判定用）。 */
    fun isStardustProj(projectileSpecId: String): Boolean =
        projectileSpecId == PROJ_ARC || projectileSpecId == PROJ_LENS
}
