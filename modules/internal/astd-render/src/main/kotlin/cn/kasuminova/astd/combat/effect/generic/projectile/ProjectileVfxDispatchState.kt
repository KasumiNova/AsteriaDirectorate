package cn.kasuminova.astd.combat.effect.generic.projectile

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI

object ProjectileVfxDispatchState {

    private const val ENGINE_EPOCH_KEY = "astd_projectile_vfx_epoch"
    private const val ENGINE_LOG_LEGACY_BOOL_ONCE = "astd_projectile_vfx_epoch_legacy_bool_logged"

    private val log = Global.getLogger(ProjectileVfxDispatchState::class.java)

    private fun epoch(engine: CombatEngineAPI): Int {
        val existing = engine.customData[ENGINE_EPOCH_KEY]
        if (existing is Int) return existing

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

    // 同场战斗复用 projectile 时旧 MARK 仍会命中当前 epoch，因此生命周期结束必须主动清除。
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
        try {
            projectile.setCustomData(key, 0)
        } catch (ex: Throwable) {
            log.warn("[ASTD] ProjectileVfxDispatchState.unlock failed (key=$key)", ex)
        }
    }

    fun clearAll(projectile: DamagingProjectileAPI) {
        unlock(projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)
        unmark(projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_MARK)
    }

    private fun logLegacyBoolOnce(engine: CombatEngineAPI, which: String) {
        if (engine.customData[ENGINE_LOG_LEGACY_BOOL_ONCE] == true) return
        engine.customData[ENGINE_LOG_LEGACY_BOOL_ONCE] = true
        log.info("[ASTD] ProjectileVfxDispatchState: legacy boolean $which marker observed; ignored under epoch scheme")
    }
}
