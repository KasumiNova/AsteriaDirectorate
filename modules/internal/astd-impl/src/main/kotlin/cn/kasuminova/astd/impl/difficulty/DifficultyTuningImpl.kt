package cn.kasuminova.astd.impl.difficulty

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl.applyResolvedScale
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl.fixedScale

object DifficultyTuningImpl : DifficultyTuning {

    @Volatile
    private var settingsScale: Float = DifficultySettingsKeys.DEFAULT_SCALE

    @Volatile
    private var testOverride: Float? = null

    @Volatile
    private var settingsPlayerScale: Float = DifficultySettingsKeys.DEFAULT_SCALE

    @Volatile
    private var testPlayerOverride: Float? = null

    override val fixedScale: Float
        get() = testOverride ?: settingsScale

    override val playerFixedScale: Float
        get() = testPlayerOverride ?: settingsPlayerScale

    override fun value(entry: ScalingEntry): Float =
        entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)

    fun applyResolvedScale(scale: Float, tierDisplayName: String) {
        val clamped = scale.coerceIn(1f, 5f)
        if (clamped != settingsScale) {
            AstdLog.logger.info("[ASTD] 敌方难度档位变更：$tierDisplayName（k_s=$clamped）")
        }
        settingsScale = clamped
    }

    fun applyResolvedPlayerScale(scale: Float, tierDisplayName: String) {
        val clamped = scale.coerceIn(1f, 5f)
        if (clamped != settingsPlayerScale) {
            AstdLog.logger.info("[ASTD] 我方难度档位变更：$tierDisplayName（k_s=$clamped）")
        }
        settingsPlayerScale = clamped
    }

    fun installScaleForTests(scale: Float?) {
        testOverride = scale
    }

    fun installPlayerScaleForTests(scale: Float?) {
        testPlayerOverride = scale
    }
}
