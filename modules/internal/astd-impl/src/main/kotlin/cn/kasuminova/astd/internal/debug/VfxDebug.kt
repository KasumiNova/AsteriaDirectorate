package cn.kasuminova.astd.internal.debug

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI

/**
 * 轻量 VFX 调试开关：通过 JVM system property 启用。
 *
 * 在 launch-config.json 里添加：-Dastd.debugVfx=true
 */
object VfxDebug {

    private const val PROP_DEBUG_VFX = "astd.debugVfx"

    fun enabled(): Boolean {
        return try {
            java.lang.Boolean.getBoolean(PROP_DEBUG_VFX)
        } catch (_: Throwable) {
            false
        }
    }

    fun logEvery(engine: CombatEngineAPI, key: String, intervalSec: Float, message: () -> String) {
        if (!enabled()) return
        val now = try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }
        val k = "astd_vfx_debug_last_log:$key"
        val last = (engine.customData[k] as? Float) ?: Float.NEGATIVE_INFINITY
        if (now - last < intervalSec) return
        engine.customData[k] = now
        try {
            Global.getLogger(VfxDebug::class.java).info(message())
        } catch (_: Throwable) {
        }
    }
}
