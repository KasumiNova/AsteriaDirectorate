package cn.kasuminova.astd.internal.debug

import com.fs.starfarer.api.combat.CombatEngineAPI
import kotlin.math.max
import kotlin.math.min

/**
 * 小工具：在 combat 里做“每秒封顶”的累积。
 *
 * 注意：这是运行时效果用的简易实现，基于 CombatEngineAPI.customData 存储。
 */
object CombatCaps {
    private const val ROOT_KEY = "astd_caps_per_second"

    private data class Bucket(var t0: Float, var used: Float)

    fun applyPerSecondCap(engine: CombatEngineAPI, bucketKey: String, capPerSecond: Float, desired: Float): Float {
        // 防 NaN/Inf：一旦把 NaN 写进 used，会把整桶污染为 NaN（之后永远无法恢复）。
        if (!capPerSecond.isFinitePositiveOrZero()) return 0f
        if (!desired.isFinitePositiveOrZero()) return 0f
        if (capPerSecond <= 0f || desired <= 0f) return 0f

        @Suppress("UNCHECKED_CAST")
        val map = (engine.customData[ROOT_KEY] as? MutableMap<String, Bucket>)
            ?: mutableMapOf<String, Bucket>().also { engine.customData[ROOT_KEY] = it }

        val t = engine.getTotalElapsedTime(false)
        val bucket = map.getOrPut(bucketKey) { Bucket(t0 = t, used = 0f) }

        // used 若被外部逻辑污染（NaN/Inf），直接重置。
        if (!bucket.used.isFinitePositiveOrZero()) {
            bucket.t0 = t
            bucket.used = 0f
        }

        if (t - bucket.t0 >= 1f) {
            bucket.t0 = t
            bucket.used = 0f
        }

        val remaining = max(0f, capPerSecond - bucket.used)
        val applied = min(desired, remaining)
        bucket.used += applied
        return applied
    }

    private fun Float.isFinitePositiveOrZero(): Boolean = !this.isNaN() && !this.isInfinite() && this >= 0f
}
