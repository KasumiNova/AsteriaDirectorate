package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * Shader VFX adapter for a ship-system driven effect.
 *
 * Implementations translate ship-system state into one or more submissions on
 * [sink]. The adapter owns the stable instance key for persistent system VFX;
 * callers should invoke [submit] from the system or hullmod path that already
 * knows the current effect level.
 */
interface ShipSystemShaderEffect {
    /** Static shader effect contract rendered by this adapter. */
    val effectSpec: ShaderEffectSpec

    /**
     * Submit or update the system effect for [ship].
     *
     * [effectLevel] is the Starsector system level in the current frame.
     * [intensity] is a domain-specific normalized value, such as flux pressure.
     * Returns a handle when a persistent keyed instance is active.
     */
    fun submit(
        engine: CombatEngineAPI,
        sink: ShaderSink,
        ship: ShipAPI,
        effectLevel: Float,
        intensity: Float,
    ): ShaderHandle?
}
