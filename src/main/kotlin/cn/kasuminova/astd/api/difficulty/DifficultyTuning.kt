package cn.kasuminova.astd.api.difficulty

/**
 * 轨一（固有缩放系数）读取面。
 *
 * 动机：难度双轨制（D13）的轨一——玩家经 LunaLib 设置界面选定的固有缩放系数 k_s，
 * 决定**敌方单位**的机制数值强度（船插 / 舰船系统 / 武器 / 词缀内部数值）。
 * 全部敌方机制数值统一由此派生，禁止各处自行读取设置或缓存系数。
 * 玩家侧使用同一机制时固定取设计基准（v2），不由本接口派生。
 *
 * 实现：[cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl]（object 单例）。
 * 调用侧字段一律声明为本接口类型。
 */
interface DifficultyTuning {

    /**
     * 当前固有缩放系数 k_s，范围 [1.0, 5.0]。
     * 对应 LunaLib 设置档位：迟暮 1.0 / 砺刃 2.0（默认）/ 远征 3.0 / 破晓 5.0 / 自定义。
     */
    val fixedScale: Float

    /**
     * 按三锚点声明取应用后的最终值。
     *
     * @param entry 使用处就地声明的三锚点（含映射策略）
     * @return 以当前 [fixedScale] 经 entry.map 映射后的最终数值
     */
    fun value(entry: ScalingEntry): Float
}
