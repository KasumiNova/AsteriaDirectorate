package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ProjectileHostImpl
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.input.InputEventAPI
import java.util.IdentityHashMap

class ProjectileVfxDriverPlugin : BaseEveryFrameCombatPlugin() {

    private class TrackedVfx(val specId: String, val driver: ProjectileVfxDriver)

    private val trackedByProjectile = IdentityHashMap<DamagingProjectileAPI, TrackedVfx>()
    private var engine: CombatEngineAPI? = null

    private var lastSpecId: String? = null
    private var lastTelemetry: ProjectileVfxDriverTelemetry? = null

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        trackedByProjectile.values.forEach { it.driver.dispose() }
        trackedByProjectile.clear()
        lastSpecId = null
        lastTelemetry = null
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val combatEngine = engine ?: return
        if (combatEngine.isPaused) return
        val iterator = trackedByProjectile.entries.iterator()
        while (iterator.hasNext()) {
            val tracked = iterator.next().value
            tracked.driver.advance(combatEngine, amount)
            tracked.driver.telemetry?.let { telemetry ->
                lastSpecId = tracked.specId
                lastTelemetry = telemetry
            }
            if (tracked.driver.state == ProjectileVfxDriverState.Removed) iterator.remove()
        }
    }

    companion object {
        const val ENGINE_KEY: String = "astd_projectile_vfx_driver_plugin"
        fun ensureInstalled(engine: CombatEngineAPI) {
            if (engine.customData[ENGINE_KEY] == null) {
                val plugin = ProjectileVfxDriverPlugin()
                engine.addPlugin(plugin)
                engine.customData[ENGINE_KEY] = plugin
            }
        }

        fun track(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, projectileSpecId: String): Boolean {
            ensureInstalled(engine)
            val plugin = engine.customData[ENGINE_KEY] as? ProjectileVfxDriverPlugin ?: return false
            if (plugin.trackedByProjectile.containsKey(projectile)) return false
            val spec = ProjectileVfxRegistry.build(projectileSpecId, projectile.weapon?.range) ?: return false
            plugin.trackedByProjectile[projectile] =
                TrackedVfx(
                    projectileSpecId,
                    ProjectileVfxDriverImpl(
                        ProjectileHostImpl(projectile),
                        ProjectileVfxTreeAssembler.assemble(spec.tree),
                        spec.policy,
                    ),
                )
            spec.onFire.forEach { it.onFire(engine, projectile) }
            return true
        }

        fun telemetrySnapshot(engine: CombatEngineAPI): ProjectileVfxTelemetrySnapshot {
            val plugin = engine.customData[ENGINE_KEY] as? ProjectileVfxDriverPlugin
                ?: return ProjectileVfxTelemetrySnapshot(0, null, 0f)
            val telemetry = plugin.lastTelemetry
            return ProjectileVfxTelemetrySnapshot(
                trackedCount = plugin.trackedByProjectile.size,
                lastProjectileSpecId = plugin.lastSpecId,
                lastElapsed = telemetry?.elapsed ?: 0f,
            )
        }

        fun trackedCountForTests(engine: CombatEngineAPI): Int =
            (engine.customData[ENGINE_KEY] as? ProjectileVfxDriverPlugin)?.trackedByProjectile?.size ?: 0
    }
}

data class ProjectileVfxTelemetrySnapshot(
    val trackedCount: Int,
    val lastProjectileSpecId: String?,
    val lastElapsed: Float,
)
