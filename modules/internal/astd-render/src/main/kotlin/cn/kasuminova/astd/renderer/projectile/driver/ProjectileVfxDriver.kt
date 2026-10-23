package cn.kasuminova.astd.renderer.projectile.driver

import com.fs.starfarer.api.combat.CombatEngineAPI

enum class ProjectileVfxDriverState { Active, Fading, Removed }

data class ProjectileVfxDriverTelemetry(
    val elapsed: Float,
)

interface ProjectileVfxDriver {

    val state: ProjectileVfxDriverState

    val telemetry: ProjectileVfxDriverTelemetry?

    fun advance(engine: CombatEngineAPI, amount: Float)

    fun dispose()
}
