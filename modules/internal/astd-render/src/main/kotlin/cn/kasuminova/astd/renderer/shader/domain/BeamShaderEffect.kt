package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI

interface BeamShaderEffect {
    val effectSpec: ShaderEffectSpec

    fun upsert(engine: CombatEngineAPI, sink: ShaderSink, beam: BeamAPI): ShaderHandle?
}
