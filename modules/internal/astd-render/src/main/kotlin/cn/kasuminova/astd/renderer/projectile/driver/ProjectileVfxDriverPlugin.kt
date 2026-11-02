package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ProjectileHostImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.input.InputEventAPI
import java.util.IdentityHashMap

/** 每帧推进已登记弹体的特效树，并补挂未经开火管线登记的弹体。 */
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
        adoptUntracked(combatEngine)
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

    /**
     * 为未经过武器开火管线登记的弹体补挂特效。
     *
     * 原版仅在 MissileWeapon 的开火路径调用 OnFireEffectPlugin，CombatEngineAPI.spawnProjectile
     * 直接走 ProjectileFactory，因此复制、召唤、脚本生成等外部弹体永远不会触发 onFire。
     * 这里每帧扫描一次弹体列表补齐，已登记弹体由 IdentityHashMap 去重。
     */
    private fun adoptUntracked(combatEngine: CombatEngineAPI) {
        val projectiles = combatEngine.projectiles
        if (projectiles.isEmpty()) return
        // 列表由引擎维护，spec.onFire 可能在本轮扫描中生成新弹体，先拷贝避免并发修改。
        val snapshot = ArrayList(projectiles)
        for (projectile in snapshot) {
            if (trackedByProjectile.containsKey(projectile)) continue
            // 已进入消散/命中结算的弹体不再补挂，否则会重新生成已经淡出的特效。
            if (projectile.isExpired || projectile.isFading || !combatEngine.isEntityInPlay(projectile)) continue
            val specId = projectile.projectileSpecId
            if (specId.isNullOrBlank() || !ProjectileVfxRegistry.has(specId)) continue
            if (attach(combatEngine, projectile, specId)) {
                logAdoptedOnce(combatEngine, specId, projectile)
            }
        }
    }

    private fun logAdoptedOnce(combatEngine: CombatEngineAPI, specId: String, projectile: DamagingProjectileAPI) {
        val key = ADOPT_LOG_KEY_PREFIX + specId
        if (combatEngine.customData[key] != null) return
        combatEngine.customData[key] = true
        log.info(
            "[ASTD] projectile vfx adopted without onFire: spec=$specId " +
                    "weapon=${projectile.weapon?.id ?: "none"} owner=${projectile.source?.id ?: "none"}",
        )
    }

    private fun attach(combatEngine: CombatEngineAPI, projectile: DamagingProjectileAPI, specId: String): Boolean {
        if (trackedByProjectile.containsKey(projectile)) return false
        val spec = ProjectileVfxRegistry.build(specId, projectile.weapon?.range) ?: return false
        trackedByProjectile[projectile] =
            TrackedVfx(
                specId,
                ProjectileVfxDriverImpl(
                    ProjectileHostImpl(projectile),
                    ProjectileVfxTreeAssembler.assemble(spec.tree),
                    spec.policy,
                ),
            )
        spec.onFire.forEach { it.onFire(combatEngine, projectile) }
        return true
    }

    companion object {
        const val ENGINE_KEY: String = "astd_projectile_vfx_driver_plugin"
        private const val ADOPT_LOG_KEY_PREFIX: String = "astd_vfx_log_adopt_once:"
        private val log = Global.getLogger(ProjectileVfxDriverPlugin::class.java)
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
            return plugin.attach(engine, projectile, projectileSpecId)
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
