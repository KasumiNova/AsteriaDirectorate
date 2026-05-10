package cn.kasuminova.astd.renderer.projectile

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import java.util.IdentityHashMap

object ASTDProjectileVfxRuntimeManager {
    private val runtimesByProjectile = IdentityHashMap<DamagingProjectileAPI, ASTDProjectileVfxRuntime>()

    fun track(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, preset: ASTDProjectileVfxPreset): Boolean {
        if (runtimesByProjectile.containsKey(projectile)) return false
        runtimesByProjectile[projectile] = ASTDProjectileVfxRuntime(projectile, preset)
        return true
    }

    fun advance(engine: CombatEngineAPI, amount: Float) {
        val iterator = runtimesByProjectile.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val runtime = entry.value
            runtime.advance(engine, amount)
            if (runtime.state == ASTDProjectileVfxRuntimeState.Removed) {
                iterator.remove()
            }
        }
    }

    fun clear() {
        runtimesByProjectile.values.forEach { it.dispose() }
        runtimesByProjectile.clear()
    }

    fun trackedCountForTests(): Int = runtimesByProjectile.size
}
