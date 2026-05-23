package cn.kasuminova.astd.renderer.projectile.reload

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPreset

interface ASTDProjectileVfxHotReloadSource {
    fun version(): Long
    fun presets(): List<ASTDProjectileVfxPreset>
}
