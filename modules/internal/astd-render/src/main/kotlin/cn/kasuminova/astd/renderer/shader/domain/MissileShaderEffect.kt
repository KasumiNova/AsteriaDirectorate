package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI

/**
 * Shader VFX adapter for missile-attached effects.
 *
 * Missiles share the projectile lifecycle shape but commonly expose thrust,
 * turn, plume, and death-fade state. Implementations should keep their key
 * stable for the missile entity and remove or allow stale cleanup on death.
 */
interface MissileShaderEffect {
    /** Static shader effect contract rendered by this missile adapter. */
    val effectSpec: ShaderEffectSpec

    /**
     * Submit or update the shader instance attached to [missile].
     */
    fun upsert(engine: CombatEngineAPI, sink: ShaderSink, missile: MissileAPI): ShaderHandle?
}
