package cn.kasuminova.astd.combat.effect.generic.projectile

import com.fs.starfarer.api.Global
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

    private val log = Global.getLogger(ProjectileVfxDispatchState::class.java)

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
        } catch (ex: Throwable) {
            log.warn("[ASTD] ProjectileVfxDispatchState.mark failed (key=$key)", ex)
        }
    }

    /**
     * 清除 MARK（允许 dispatcher 再次尝试分发）。
     *
     * 说明：epoch 只能隔离“跨战斗复用”，但如果引擎在同一场战斗内复用 projectile 实例，
     * 旧的 MARK 仍会命中新 epoch（因为 epoch 以 engine 为粒度）。因此在生命周期结束时必须主动清掉。
     */
    fun unmark(projectile: DamagingProjectileAPI, key: String) {
        try {
            projectile.setCustomData(key, 0)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ProjectileVfxDispatchState.unmark failed (key=$key)", ex)
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
        } catch (ex: Throwable) {
            log.warn("[ASTD] ProjectileVfxDispatchState.lock failed (key=$key)", ex)
        }
    }

    fun unlock(projectile: DamagingProjectileAPI, key: String) {
        // 用 0 表示“无锁”。（避免 setCustomData(null) 在某些实现里不允许）
        try {
            projectile.setCustomData(key, 0)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ProjectileVfxDispatchState.unlock failed (key=$key)", ex)
        }
    }

    fun clearAll(projectile: DamagingProjectileAPI) {
        // 注意：这里不依赖 engine epoch；直接清为 0。
        // 目的：处理“同一场战斗内对象复用”与“pending 超时需要允许兜底重试”。
        unlock(projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)
        unmark(projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_MARK)
        unmark(projectile, ProjectileVfxKeys.PROJECTILE_VFX_COMMON_FX_SKIP)
    }

    private fun logLegacyBoolOnce(engine: CombatEngineAPI, which: String) {
        if (engine.customData[ENGINE_LOG_LEGACY_BOOL_ONCE] == true) return
        engine.customData[ENGINE_LOG_LEGACY_BOOL_ONCE] = true
        log.info("[ASTD] ProjectileVfxDispatchState: legacy boolean $which marker observed; ignored under epoch scheme")
    }
}
