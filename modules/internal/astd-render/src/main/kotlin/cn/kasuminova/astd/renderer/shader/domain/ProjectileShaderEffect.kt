package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI

interface ProjectileShaderEffect {
    val effectSpec: ShaderEffectSpec

    fun spawn(engine: CombatEngineAPI, sink: ShaderSink, projectile: DamagingProjectileAPI): ShaderHandle?

    fun advance(
        engine: CombatEngineAPI,
        sink: ShaderSink,
        projectile: DamagingProjectileAPI,
        handle: ShaderHandle,
    ): ShaderHandle?
}
