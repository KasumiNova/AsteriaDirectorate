package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * “穷距”相位轨道炮的难度三锚点声明（规格 05 §2.1，定案 v1.0 数值）。
 *
 * 动机：持续演算的三项缩放数值（每层加成/切换保留/衰减速率）集中登记在一处，
 * 取值统一走 [QiongjueStackMath.resolve]（玩家 owner==0 固定 v2，否则轨一 k_s 映射）；
 * 层数上限/衰减窗口/射程/面板为不缩放常量（2026-07-29 裁定）。
 */
object QiongjuePhaseRailgunDifficulty {

    /** 每层伤害/射速加成（v1 5% / v2 6.25% / v5 10%；叠乘收益恒等于层数线性，无超线性，LINEAR 即可）。 */
    val PER_STACK_BONUS = ScalingEntry(0.05f, 0.0625f, 0.10f)

    /** 切换目标保留比例（v1 25% / v2 31.25% / v5 50%）。 */
    val SWITCH_RETAIN = ScalingEntry(0.25f, 0.3125f, 0.50f)

    /** 衰减速率（层/s；v1 2 / v2 1.75 / v5 1——v1>v5 属反向语义，LINEAR 插值天然支持）。 */
    val DECAY_RATE = ScalingEntry(2f, 1.75f, 1f)

    /** 层数上限（固定不缩放）。 */
    const val MAX_STACKS = 10

    /** 衰减窗口：最近一次命中起静默该时长后层数开始流失（固定不缩放）。 */
    const val DECAY_WINDOW_SECONDS = 3f

    /** 武器 id（`.wpn`/`.proj`/蓝图 params 的同一身份）。 */
    const val WEAPON_ID = "astd_qiongjue_phase_railgun"
}
