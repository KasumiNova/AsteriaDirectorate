package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 引力相位甲板（茑萝级内置 Hullmod）的机制数值声明（purple/20-production.md §2，2026-09 D27 重做）。
 *
 * 动机：战机辐能返还比例的三锚点查值集中在此声明，供 hullmod 每帧实时解析
 * （LunaLib 设置变更即时生效），并由单元测试直接驱动。
 * 相位联动（母舰相位 → 战机同步相位）无数值参数，不在此声明。
 *
 * 玩家来源（owner == 0）固定 v2（砺刃档），对照 BurstFlowTuning 既有口径。
 */
object GravPhaseDeckTuning {

    /** 战机产生辐能返还母舰的比例（v1 50% / v2 60% / v5 90%，三锚点 LINEAR）。 */
    val FLUX_RETURN_RATIO = ScalingEntry(0.5f, 0.6f, 0.9f)

    /** 母舰辐能水平（当前/最大）高于该值时返还失效。 */
    const val MOTHERSHIP_FLUX_LEVEL_DISABLE = 0.8f

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。 */
    fun resolveReturnRatio(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        if (isPlayer) FLUX_RETURN_RATIO.v2 else tuning.value(FLUX_RETURN_RATIO)

    /**
     * 单帧辐能返还量（纯函数）：战机本帧辐能净增量 × 返还比例，下限 0
     * （净耗散/净下降帧不返还，0 下限仅防御浮点抖动）。
     */
    fun returnAmount(fluxDelta: Float, ratio: Float): Float =
        if (fluxDelta <= 0f) 0f else fluxDelta * ratio
}
