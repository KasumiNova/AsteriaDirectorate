package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.combat.DualModeSettings

/**
 * [DualModeSettings] 的单例实现：持有「双模式切换器自动模式免自动化点数」开关的当前值。
 *
 * 开关来源：LunaLib 设置（由 [cn.kasuminova.astd.impl.difficulty.DifficultySettingsRegistrar]
 * 注册并在设置变更时调用 [applyResolvedExempt] 刷新）；未安装 LunaLib 或未设置时保持默认（开启）。
 *
 * 注意：本类的初始化不触碰 LunaSettings（单元测试环境没有 LunaLib），
 * 所有设置读写都发生在注册器的注册/回调路径上。
 */
object DualModeSettingsImpl : DualModeSettings {

    /** LunaLib 设置项 field id（注册与读取共用此常量，避免两处各写一份导致失配）。 */
    const val FIELD_FREE_AUTO_POINTS: String = "astd_dual_mode_free_auto_points"

    /** 默认值：开启（带切换器的自动模式舰船不计入自动化舰船点数）。 */
    const val DEFAULT_FREE_AUTO_POINTS: Boolean = true

    @Volatile
    private var settingsExempt: Boolean = DEFAULT_FREE_AUTO_POINTS

    @Volatile
    private var testOverride: Boolean? = null

    /**
     * 开关变更钩子：combat 侧双模式框架在 mod 插件 onApplicationLoad 安装
     * （见 cn.kasuminova.astd.combat.hullmods.base.installDualModeAutoPointsHook），
     * 开关热重载时立即对玩家舰队同步 no_auto_penalty 标签，免等下次 stats 重建。
     */
    @Volatile
    var exemptChangedHook: ((Boolean) -> Unit)? = null

    override val automatedModeExemptFromAutoPoints: Boolean
        get() = testOverride ?: settingsExempt

    /**
     * 应用从 LunaLib 设置解析出的开关值（设置注册与 settingsChanged 回调路径调用）。
     * 值变化时输出 INFO 日志并触发 [exemptChangedHook]（钩子未安装属装配异常，打 WARN）。
     */
    fun applyResolvedExempt(enabled: Boolean) {
        if (enabled == settingsExempt) return
        settingsExempt = enabled
        AstdLog.logger.info("[ASTD] 双模式切换器「自动模式免自动化点数」变更：${if (enabled) "开启" else "关闭"}")
        val hook = exemptChangedHook
        if (hook != null) {
            hook.invoke(enabled)
        } else {
            AstdLog.logger.warn("[ASTD] 免自动化点数变更钩子未安装（onApplicationLoad 未完成？），标签将于下次 stats 刷新收敛")
        }
    }

    /**
     * 单元测试注入开关值：传入非 null 值后 [automatedModeExemptFromAutoPoints] 恒返回该值；传 null 清除注入。
     * 仅测试使用，游戏运行路径不应调用。
     */
    fun installExemptForTests(value: Boolean?) {
        testOverride = value
    }
}
