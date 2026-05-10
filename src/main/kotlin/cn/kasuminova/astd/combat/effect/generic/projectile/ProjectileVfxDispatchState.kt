package cn.kasuminova.astd.combat.effect.generic.projectile

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI

/**
 * 解决 Starsector 可能的对象池/复用导致的 projectile.customData 残留问题：
 * - 不再用 Boolean 标记，而是用“每场战斗 epoch(Int)”标记。
 * - 这样即便同一个 projectile 实例在不同战斗/不同发射中被复用，旧标记也不会影响当前战斗。
 */
internal object ProjectileVfxDispatchState {

    private const val ENGINE_EPOCH_KEY = "astd_projectile_vfx_epoch"

    // 仅用于兼容/诊断：发现残留 boolean 标记时，最多每场战斗提示一次。
    private const val ENGINE_LOG_LEGACY_BOOL_ONCE = "astd_projectile_vfx_epoch_legacy_bool_logged"

    private fun epoch(engine: CombatEngineAPI): Int {
        val existing = engine.customData[ENGINE_EPOCH_KEY]
        if (existing is Int) return existing

        // 使用 engine 的 identityHashCode 足以区分每场战斗。
        val e = System.identityHashCode(engine)
        engine.customData[ENGINE_EPOCH_KEY] = e
        return e
    }

    fun isMarked(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, key: String): Boolean {
        val e = epoch(engine)
        return when (val v = projectile.customData[key]) {
            is Int -> v == e
            is Boolean -> {
                logLegacyBoolOnce(engine, "MARK")
                false
            }
            else -> false
        }
    }

    fun mark(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, key: String) {
        val e = epoch(engine)
        try {
            projectile.setCustomData(key, e)
        } catch (_: Throwable) {
        }
    }

    /**
     * 清除 MARK（允许扫描式 dispatcher 再次尝试分发）。
     *
     * 说明：epoch 只能隔离“跨战斗复用”，但如果引擎在同一场战斗内复用 projectile 实例，
     * 旧的 MARK 仍会命中新 epoch（因为 epoch 以 engine 为粒度）。因此在生命周期结束时必须主动清掉。
     */
    fun unmark(projectile: DamagingProjectileAPI, key: String) {
        try {
            projectile.setCustomData(key, 0)
        } catch (_: Throwable) {
        }
    }

    fun isLocked(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, key: String): Boolean {
        val e = epoch(engine)
        return when (val v = projectile.customData[key]) {
            is Int -> v == e
            is Boolean -> {
                logLegacyBoolOnce(engine, "LOCK")
                false
            }
            else -> false
        }
    }

    fun lock(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, key: String) {
        val e = epoch(engine)
        try {
            projectile.setCustomData(key, e)
        } catch (_: Throwable) {
        }
    }

    fun unlock(projectile: DamagingProjectileAPI, key: String) {
        // 用 0 表示“无锁”。（避免 setCustomData(null) 在某些实现里不允许）
        try {
            projectile.setCustomData(key, 0)
        } catch (_: Throwable) {
        }
    }

    fun clearAll(projectile: DamagingProjectileAPI) {
        // 注意：这里不依赖 engine epoch；直接清为 0。
        // 目的：处理“同一场战斗内对象复用”与“pending 超时需要允许兜底重试”。
        unlock(projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)
        unlock(projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_LOCK)
        unmark(projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_MARK)
        unmark(projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_MARK)
        unmark(projectile, ProjectileVfxKeys.PROJECTILE_AI_INSTALLED_MARK)
        unmark(projectile, ProjectileVfxKeys.PROJECTILE_VFX_COMMON_FX_SKIP)
    }

    /**
     * spawn 扫描插件使用的失败计数，需要同样做 epoch 隔离：
     * 否则对象池复用会把旧的 failCount 带到新弹体上，导致“永远不再尝试/直接 MARK”。
     */
    fun getFailCount(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, key: String): Int {
        val e = epoch(engine)
        val v = projectile.customData[key]
        return when (v) {
            is Long -> {
                val ve = (v ushr 32).toInt()
                if (ve != e) 0 else (v and 0xFFFF_FFFFL).toInt()
            }
            is Int -> 0 // 旧格式：直接丢弃
            else -> 0
        }
    }

    fun setFailCount(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, key: String, count: Int) {
        val e = epoch(engine)
        val packed = (e.toLong() shl 32) or (count.toLong() and 0xFFFF_FFFFL)
        try {
            projectile.setCustomData(key, packed)
        } catch (_: Throwable) {
        }
    }

    private fun logLegacyBoolOnce(engine: CombatEngineAPI, which: String) {
        if (engine.customData[ENGINE_LOG_LEGACY_BOOL_ONCE] == true) return
        engine.customData[ENGINE_LOG_LEGACY_BOOL_ONCE] = true
        // 这里不引 Global logger，避免循环依赖；由调用方现有日志覆盖即可。
        // 如需更细日志，可在 dispatcher 里增加打印。
        // (which=$which)
    }
}
