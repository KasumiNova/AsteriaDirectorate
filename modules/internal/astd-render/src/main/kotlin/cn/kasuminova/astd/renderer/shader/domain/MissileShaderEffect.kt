package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI

interface MissileShaderEffect {
    val effectSpec: ShaderEffectSpec

    fun upsert(engine: CombatEngineAPI, sink: ShaderSink, missile: MissileAPI): ShaderHandle?
}
