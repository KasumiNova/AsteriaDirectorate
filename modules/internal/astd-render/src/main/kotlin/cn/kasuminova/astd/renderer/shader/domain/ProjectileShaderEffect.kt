package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI

/**
 * Shader VFX adapter for projectile-spec-id based effects.
 *
 * Implementations are intended to be invoked by the existing projectile VFX
 * dispatch path after it has resolved a projectile by `projectileSpecId`.
 * They must not scan the battlefield for projectiles.
 */
interface ProjectileShaderEffect {
    /** Static shader effect contract rendered by this projectile adapter. */
    val effectSpec: ShaderEffectSpec

    /**
     * Create the initial shader instance for a fired projectile.
     *
     * The returned handle is owned by the projectile runtime path and should be
     * passed back to [advance] while the projectile remains in play.
     */
    fun spawn(engine: CombatEngineAPI, sink: ShaderSink, projectile: DamagingProjectileAPI): ShaderHandle?

    /**
     * Update a previously spawned projectile shader instance.
     */
    fun advance(
        engine: CombatEngineAPI,
        sink: ShaderSink,
        projectile: DamagingProjectileAPI,
        handle: ShaderHandle,
    ): ShaderHandle?
}
