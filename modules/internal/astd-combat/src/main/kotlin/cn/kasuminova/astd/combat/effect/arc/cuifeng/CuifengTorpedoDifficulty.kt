package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

/**
 * 摧锋鱼雷的机制数值锚点与难度取值入口（blue/30-superlative.md 定案 v1.0）。
 *
 * 动机：辐能自适应 / 舰体自适应（四档舰体等级 + 部署点差值）/ 硬辐推进六条三锚点集中登记，
 * 玩家固定 v2 的取值口径与轨一 k_s 映射只有一处实现（与辉星/贯星同型，命中时取值——
 * 本武器无开火态状态机，命中回调与 AI 是仅有的结算点，LunaLib 热变更即时生效）。
 *
 * 数值缩放口径（90 计划全局约定）：敌方按轨一 k_s 三锚点线性映射；玩家来源（owner == 0）固定 v2。
 */
object CuifengTorpedoDifficulty {

    /** 辐能自适应最大增伤（面板倍率）：迟暮 25% / 砺刃 50% / 破晓 125%（设计案定稿三锚点）。 */
    val FLUX_ADAPTIVE_X = ScalingEntry(0.25f, 0.50f, 1.25f, ScalingMap.LINEAR)

    /** 硬辐推进（目标最大辐能比例）：迟暮 2% / 砺刃 4% / 破晓 10%（设计案定稿三锚点）。 */
    val HARD_FLUX_Y = ScalingEntry(0.02f, 0.04f, 0.10f, ScalingMap.LINEAR)

    /** 舰体自适应·护卫舰（面板倍率）：迟暮 0% / 砺刃 5% / 破晓 20%。 */
    val HULL_FRIGATE = ScalingEntry(0.00f, 0.05f, 0.20f, ScalingMap.LINEAR)

    /** 舰体自适应·驱逐舰（面板倍率）：迟暮 10% / 砺刃 20% / 破晓 50%。 */
    val HULL_DESTROYER = ScalingEntry(0.10f, 0.20f, 0.50f, ScalingMap.LINEAR)

    /** 舰体自适应·巡洋舰（面板倍率）：迟暮 20% / 砺刃 40% / 破晓 100%。 */
    val HULL_CRUISER = ScalingEntry(0.20f, 0.40f, 1.00f, ScalingMap.LINEAR)

    /** 舰体自适应·主力舰（面板倍率）：迟暮 30% / 砺刃 60% / 破晓 150%。 */
    val HULL_CAPITAL = ScalingEntry(0.30f, 0.60f, 1.50f, ScalingMap.LINEAR)

    /** 部署点差值增伤（每 1 点超出同级基准的部署点，面板倍率）：迟暮 2% / 砺刃 4% / 破晓 10%。 */
    val DP_PCT = ScalingEntry(0.02f, 0.04f, 0.10f, ScalingMap.LINEAR)

    /** 同级部署点基准：护卫 5 / 驱逐 10 / 巡洋 20 / 主力 40（设计案定稿）。 */
    val DP_BASELINE_FRIGATE = 5f
    val DP_BASELINE_DESTROYER = 10f
    val DP_BASELINE_CRUISER = 20f
    val DP_BASELINE_CAPITAL = 40f

    /** 爆炸范围（su）：提案 150 定稿（设计案待裁定项采用提案默认值），不缩放。 */
    const val AOE_RADIUS = 150f

    /** 导弹 AI 目标重选节流（秒）：与辉星同型 0.25。 */
    const val RETARGET_INTERVAL = 0.25f

    /** 二段式打击：航程 25% 前仅 50% 航速，至 50% 航程线性达到满速（玩家不可见隐性机制）。 */
    const val SLOW_PHASE_END = 0.25f
    const val RAMP_PHASE_END = 0.50f
    const val SLOW_SPEED_FACTOR = 0.50f

    /**
     * 难度统一取值：玩家来源（[sourceOwner] == 0）固定 v2，否则按轨一 k_s 映射。
     * 命中时每次调用（不缓存）。
     */
    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    /** 可注入 [DifficultyTuning] 的取值入口（单元测试与运行共用同一路径）。 */
    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        if (sourceOwner == 0) entry.v2 else tuning.value(entry)
}
