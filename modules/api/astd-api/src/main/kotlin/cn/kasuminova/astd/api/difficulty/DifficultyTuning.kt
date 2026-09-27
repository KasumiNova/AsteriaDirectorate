package cn.kasuminova.astd.api.difficulty

/**
 * 难度系数的统一读取面（机制数值缩放，D13 轨一 + 我方档位）。
 *
 * 动机：机制数值（船插 / 舰船系统 / 武器 / 词缀内部数值）按来源阵营分两套系数：
 * - [fixedScale]：敌方（及友军 AI）单位的固有缩放系数 k_s，由 LunaLib「敌方强度档位」选定；
 * - [playerFixedScale]：玩家阵营（owner == 0）单位的系数，由 LunaLib「我方强度档位」选定，
 *   默认砺刃 2.0（即早期「玩家侧固定 v2」口径）。
 * 全部机制数值统一经 [value] / [valueFor] 派生，禁止各处自行读取设置或缓存系数。
 *
 * 实现：[cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl]（object 单例）。
 * 调用侧字段一律声明为本接口类型。
 */
interface DifficultyTuning {

    /**
     * 敌方阵营的当前固有缩放系数 k_s，范围 [1.0, 5.0]。
     * 对应 LunaLib 设置档位：迟暮 1.0 / 砺刃 2.0（默认）/ 远征 3.0 / 破晓 5.0 / 自定义。
     */
    val fixedScale: Float

    /**
     * 玩家阵营的当前固有缩放系数，范围 [1.0, 5.0]。
     * 对应 LunaLib「我方强度档位」：迟暮 1.0 / 砺刃 2.0（默认）/ 远征 3.0 / 破晓 5.0。
     * 未安装 LunaLib 或玩家未改动时恒为 [PLAYER_SCALE_DESIGN_BASELINE]。
     */
    val playerFixedScale: Float get() = PLAYER_SCALE_DESIGN_BASELINE

    /**
     * 按三锚点声明取敌方阵营应用后的最终值。
     *
     * @param entry 使用处就地声明的三锚点（含映射策略）
     * @return 以当前 [fixedScale] 经 entry.map 映射后的最终数值
     */
    fun value(entry: ScalingEntry): Float

    /**
     * 按五档查表声明取敌方阵营应用后的最终值（逐档精确表的取值入口）。
     *
     * 默认实现直接以 [fixedScale] 查表（就近取档），实现类无需覆写。
     */
    fun value(table: ScalingTable): Float = table.at(fixedScale)

    /**
     * 按来源阵营取三锚点声明的最终值：玩家阵营（[isPlayer] = true）用 [playerFixedScale] 映射，
     * 其余阵营用 [fixedScale] 映射（等价 [value]）。
     */
    fun valueFor(entry: ScalingEntry, isPlayer: Boolean): Float =
        if (isPlayer) entry.map.value(playerFixedScale, entry.v1, entry.v2, entry.v5) else value(entry)

    /**
     * 按来源阵营取五档查表声明的最终值：玩家阵营以 [playerFixedScale] 查表，其余阵营以 [fixedScale] 查表。
     */
    fun valueFor(table: ScalingTable, isPlayer: Boolean): Float =
        if (isPlayer) table.at(playerFixedScale) else value(table)

    companion object {
        /** 玩家侧系数默认值：砺刃档 2.0（设计基准；与早期「玩家侧固定 v2」口径等价）。 */
        const val PLAYER_SCALE_DESIGN_BASELINE: Float = 2.0f
    }
}
