package cn.kasuminova.astd.renderer.projectile.reload

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPreset
import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentContext

object ASTDProjectileVfxHotReloadManager {
    fun reload(source: ASTDProjectileVfxHotReloadSource): ReloadedPresets {
        val presets = source.presets()
        val duplicateIds = presets.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Projectile VFX hot reload source contains duplicate preset ids: ${duplicateIds.joinToString()}"
        }
        presets.forEach { preset ->
            ASTDProjectileVfxComponentContext(preset.components)
        }
        return ReloadedPresets(source.version(), presets.associateBy { it.id })
    }

    data class ReloadedPresets(
        val version: Long,
        val presets: Map<String, ASTDProjectileVfxPreset>,
    )
}
