package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

/**
 * 辉星 MRM 的机制数值锚点与难度取值入口（规格 08 §2.1）。
 *
 * 动机：战机增伤 / 武器 EMP / 爆炸倍率 / 撞线阈值四条三锚点集中登记，
 * 玩家固定 v2 的取值口径与轨一 k_s 映射只有一处实现（与 04/05 组同型，命中时取值——
 * 本武器无开火态状态机，命中回调是唯一结算点，LunaLib 热变更对后续命中即时生效）。
 *
 * 数值缩放口径（90 计划全局约定）：敌方按轨一 k_s 三锚点线性映射；玩家来源（owner == 0）固定 v2。
 */
object StellarMrmDifficulty {

    /** 对战机额外伤害（面板倍率）：迟暮 0.5 / 砺刃 1.0 / 破晓 2.5（设计案定稿三锚点）。 */
    val FIGHTER_BONUS = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    /** 战机全部武器 EMP（面板倍率）：迟暮 2 / 砺刃 4 / 破晓 10（设计案定稿三锚点）。 */
    val WEAPON_EMP = ScalingEntry(2f, 4f, 10f, ScalingMap.LINEAR)

    /** 辉星爆炸倍率（面板倍率）：迟暮 0.5 / 砺刃 1.0 / 破晓 2.5（设计案定稿三锚点）。 */
    val EXPLOSION_MULT = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    /** 撞线阈值 h（自身 HP 倍数）：迟暮 1.5 / 砺刃 3.0 / 破晓 7.5（设计案定稿三锚点）。 */
    val LINE_CROSS_H = ScalingEntry(1.5f, 3.0f, 7.5f, ScalingMap.LINEAR)

    /** 爆炸范围（su）：固定 50，不缩放（设计案定稿）。 */
    const val EXPLOSION_RADIUS = 50f

    /** 导弹 AI 目标重选节流（秒）：提案值 0.25，目检可调。 */
    const val RETARGET_INTERVAL = 0.25f

    /**
     * 难度统一取值：玩家来源（[sourceOwner] == 0）固定 v2，否则按轨一 k_s 映射。
     * 命中时每次调用（不缓存，规格 08 §2.2 难度取值调用点唯一入口）。
     */
    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    /** 可注入 [DifficultyTuning] 的取值入口（单元测试与运行共用同一路径）。 */
    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        if (sourceOwner == 0) entry.v2 else tuning.value(entry)
}
