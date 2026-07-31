package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI

/**
 * Shader VFX adapter for beam-attached effects.
 *
 * Beam effects are expected to call [upsert] every frame the source beam is
 * considered alive. Missing updates are cleaned by the shader runtime stale
 * policy declared on [effectSpec].
 */
interface BeamShaderEffect {
    /** Static shader effect contract rendered by this beam adapter. */
    val effectSpec: ShaderEffectSpec

    /**
     * Submit or update the shader instance attached to [beam].
     */
    fun upsert(engine: CombatEngineAPI, sink: ShaderSink, beam: BeamAPI): ShaderHandle?
}
