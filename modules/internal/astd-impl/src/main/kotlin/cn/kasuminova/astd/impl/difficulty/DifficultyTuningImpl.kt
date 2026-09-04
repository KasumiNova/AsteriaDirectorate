package cn.kasuminova.astd.impl.difficulty

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.AstdLog

/**
 * [DifficultyTuning] 的单例实现：持有当前固有缩放系数 k_s。
 *
 * 系数来源：LunaLib 设置（由 [DifficultySettingsRegistrar] 注册并在设置变更时调用
 * [applyResolvedScale] 刷新）；未设置或解析失败时保持默认档（砺刃 2.0）。
 *
 * 注意：本类的初始化不触碰 LunaSettings（单元测试环境没有 LunaLib），
 * 所有设置读写都发生在 [DifficultySettingsRegistrar] 注册/回调路径上。
 */
object DifficultyTuningImpl : DifficultyTuning {

    @Volatile
    private var settingsScale: Float = DifficultySettingsKeys.DEFAULT_SCALE

    @Volatile
    private var testOverride: Float? = null

    override val fixedScale: Float
        get() = testOverride ?: settingsScale

    override fun value(entry: ScalingEntry): Float =
        entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)

    /**
     * 应用从 LunaLib 设置解析出的新系数（设置注册与 settingsChanged 回调路径调用）。
     * 会输出一条 INFO 日志记录档位变化，便于实机核对。
     */
    fun applyResolvedScale(scale: Float, tierDisplayName: String) {
        val clamped = scale.coerceIn(1f, 5f)
        if (clamped != settingsScale) {
            AstdLog.logger.info("[ASTD] 难度档位变更：$tierDisplayName（k_s=$clamped）")
        }
        settingsScale = clamped
    }

    /**
     * 单元测试注入系数：传入非 null 值后 [fixedScale] 恒返回该值；传 null 清除注入。
     * 仅测试使用，游戏运行路径不应调用。
     */
    fun installScaleForTests(scale: Float?) {
        testOverride = scale
    }
}
