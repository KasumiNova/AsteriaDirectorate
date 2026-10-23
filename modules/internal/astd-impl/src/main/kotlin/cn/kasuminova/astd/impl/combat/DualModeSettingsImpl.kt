package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.combat.DualModeSettings

object DualModeSettingsImpl : DualModeSettings {

    const val FIELD_FREE_AUTO_POINTS: String = "astd_dual_mode_free_auto_points"

    const val DEFAULT_FREE_AUTO_POINTS: Boolean = true

    @Volatile
    private var settingsExempt: Boolean = DEFAULT_FREE_AUTO_POINTS

    @Volatile
    private var testOverride: Boolean? = null

    @Volatile
    var exemptChangedHook: ((Boolean) -> Unit)? = null

    override val automatedModeExemptFromAutoPoints: Boolean
        get() = testOverride ?: settingsExempt

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

    fun installExemptForTests(value: Boolean?) {
        testOverride = value
    }
}
