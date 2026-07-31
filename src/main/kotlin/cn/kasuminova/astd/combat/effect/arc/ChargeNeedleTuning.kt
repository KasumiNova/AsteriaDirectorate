package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import kotlin.math.floor
import kotlin.math.min

/**
 * 电荷针刺 / 重型电荷针刺的机制数值声明与安全闸纯函数（规格 01 §2.2 / §2.5）。
 *
 * 动机：两槽位机制完全同源（设计案：中槽变体机制完全复用无差异化），三锚点与固定常量
 * 集中在一处声明；耗散安全闸（追加维持量 ≤ 目标耗散 50%）与泄放判定均为纯函数，
 * 供 OnHit / Stacks 调用并由单元测试直接驱动。
 *
 * 数值缩放口径（90 计划全局约定）：敌方按轨一 k_s 三锚点 LINEAR 映射；玩家来源（owner == 0）固定 v2。
 */
object ChargeNeedleTuning {

    /** 每层护盾维持加成（v1 1% / v2 2% / v5 5%）。 */
    val PER_STACK = ScalingEntry(0.01f, 0.02f, 0.05f)

    /** 船体/装甲命中泄放概率（v1 25% / v2 40% / v5 100%）。 */
    val DISCHARGE_CHANCE = ScalingEntry(0.25f, 0.40f, 1.00f)

    /** 泄放 EMP 倍率（v1 100% / v2 175% / v5 400%）。 */
    val DISCHARGE_EMP_MULT = ScalingEntry(1.00f, 1.75f, 4.00f)

    /** 泄放基准 EMP（面板单发 EMP，固定不缩放）。 */
    const val BASE_DISCHARGE_EMP = 100f

    /** 层数绝对上限（固定不缩放）。 */
    const val ABSOLUTE_MAX_STACKS = 200

    /** 连续衰减速率（层/s，固定不缩放）。 */
    const val DECAY_PER_SECOND = 10f

    /** 安全闸比例：追加维持量不得超过目标当前耗散的该比例（固定不缩放）。 */
    const val DISSIPATION_CAP_RATIO = 0.5f

    /** 一次命中路由所需的全部机制数值（难度解析结果）。 */
    data class Values(
        /** 每层护盾维持加成。 */
        val perStack: Float,
        /** 船体/装甲命中泄放概率。 */
        val dischargeChance: Float,
        /** 泄放 EMP 倍率。 */
        val dischargeEmpMult: Float,
    )

    /**
     * 难度取值唯一入口：玩家来源固定 v2，否则按轨一 k_s 三锚点映射。
     * 每次命中调用一次（不缓存），保证 LunaLib 设置变更即时生效。
     */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        perStack = if (isPlayer) PER_STACK.v2 else tuning.value(PER_STACK),
        dischargeChance = if (isPlayer) DISCHARGE_CHANCE.v2 else tuning.value(DISCHARGE_CHANCE),
        dischargeEmpMult = if (isPlayer) DISCHARGE_EMP_MULT.v2 else tuning.value(DISCHARGE_EMP_MULT),
    )

    /**
     * 耗散安全闸（纯函数，唯一入口）：追加维持量 = baseUpkeep × stacks × perStack
     * ≤ [DISSIPATION_CAP_RATIO] × dissipation → 允许层数 = floor(ratio × dissipation / (baseUpkeep × perStack))，
     * 再与 [ABSOLUTE_MAX_STACKS] 取小；恰整除按 floor 含端。
     *
     * 0 值防线（只定返回值语义；WARN/ERROR 日志由调用侧按船去重承担，见 ChargeNeedleStacks）：
     * - [baseUpkeep] ≤ 0（无盾舰/零耗盾）：返回 [ABSOLUTE_MAX_STACKS]——命中护盾分支已在上游拦截，此分支仅为防御；
     * - [dissipation] ≤ 0（耗散被特殊机制压没的异常态）：返回 0——层数立即裁到 0，禁止静默恒零；
     * - [perStack] ≤ 0（难度配置错误）：返回 [ABSOLUTE_MAX_STACKS]——闸失效但机制不退化。
     */
    fun dissipationCapStacks(dissipation: Float, baseUpkeep: Float, perStack: Float): Int {
        if (perStack <= 0f) return ABSOLUTE_MAX_STACKS
        if (baseUpkeep <= 0f) return ABSOLUTE_MAX_STACKS
        if (dissipation <= 0f) return 0
        return min(ABSOLUTE_MAX_STACKS, floor(DISSIPATION_CAP_RATIO * dissipation / (baseUpkeep * perStack)).toInt())
    }

    /**
     * 泄放判定（纯函数）：严格小于口径——[roll] ∈ [0, 1)，chance = 0 恒不触发，
     * chance = 1 时 roll = 0.999 仍触发，roll == chance 边界不触发。
     */
    fun shouldDischarge(roll: Float, chance: Float): Boolean = roll < chance
}
