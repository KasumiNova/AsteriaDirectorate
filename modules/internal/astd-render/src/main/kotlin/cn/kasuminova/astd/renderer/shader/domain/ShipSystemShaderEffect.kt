package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI

interface ShipSystemShaderEffect {
    val effectSpec: ShaderEffectSpec

    fun submit(
        engine: CombatEngineAPI,
        sink: ShaderSink,
        ship: ShipAPI,
        effectLevel: Float,
        intensity: Float,
    ): ShaderHandle?
}
