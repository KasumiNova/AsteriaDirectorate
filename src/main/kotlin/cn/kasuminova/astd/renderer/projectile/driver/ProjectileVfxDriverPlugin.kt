package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ProjectileHostImpl
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.input.InputEventAPI
import java.util.IdentityHashMap

/**
 * 新 RenderEntity 管线的战斗每帧插件：持有各弹体的 [ProjectileVfxDriver]，逐帧推进、剔除已结束者。
 */
class ProjectileVfxDriverPlugin : BaseEveryFrameCombatPlugin() {

    /** 一枚已登记弹体：驱动 + 其 projectileSpecId（遥测归因用）。 */
    private class TrackedVfx(val specId: String, val driver: ProjectileVfxDriver)

    private val trackedByProjectile = IdentityHashMap<DamagingProjectileAPI, TrackedVfx>()
    private var engine: CombatEngineAPI? = null

    /** 最近一帧推进过帧的弹体 specId 与其遥测（自动化取证用）。 */
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

        /**
         * 按 projectileSpecId 的手写 DSL 现构建一棵新树 + 策略，登记一枚弹体到新管线。
         * 已登记或该 spec 未迁移则忽略。
         * @return 是否新登记成功。
         */
        fun track(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, projectileSpecId: String): Boolean {
            ensureInstalled(engine)
            val plugin = engine.customData[ENGINE_KEY] as? ProjectileVfxDriverPlugin ?: return false
            if (plugin.trackedByProjectile.containsKey(projectile)) return false
            val spec = ProjectileVfxSpecs.build(projectileSpecId) ?: return false
            plugin.trackedByProjectile[projectile] =
                TrackedVfx(projectileSpecId, ProjectileVfxDriverImpl(ProjectileHostImpl(projectile), spec.tree, spec.policy))
            return true
        }

        /** 插件级遥测快照（自动化取证用）。插件未安装或尚无帧时各字段为空/零。 */
        fun telemetrySnapshot(engine: CombatEngineAPI): ProjectileVfxTelemetrySnapshot {
            val plugin = engine.customData[ENGINE_KEY] as? ProjectileVfxDriverPlugin
                ?: return ProjectileVfxTelemetrySnapshot(0, null, 0f, 0f, 0f, 0f)
            val telemetry = plugin.lastTelemetry
            return ProjectileVfxTelemetrySnapshot(
                trackedCount = plugin.trackedByProjectile.size,
                lastProjectileSpecId = plugin.lastSpecId,
                lastElapsed = telemetry?.elapsed ?: 0f,
                lastVisibleLength = telemetry?.visibleLength ?: 0f,
                lastBeamAlpha = telemetry?.beamAlpha ?: 0f,
                lastWorldUnitsPerPixel = telemetry?.worldUnitsPerPixel ?: 0f,
            )
        }

        /** 测试入口：该引擎当前登记到新管线的弹体驱动数。 */
        internal fun trackedCountForTests(engine: CombatEngineAPI): Int =
            (engine.customData[ENGINE_KEY] as? ProjectileVfxDriverPlugin)?.trackedByProjectile?.size ?: 0
    }
}

/**
 * 弹体 VFX 管线的插件级遥测快照（自动化取证用，见 ASTDAutomationCombatPlugin）。
 *
 * @param trackedCount 当前在册驱动数。
 * @param lastProjectileSpecId 最近一帧推进过帧的弹体 specId；无则为 null。
 * @param lastElapsed 该驱动的累计推进秒数。
 * @param lastVisibleLength 该帧拖尾可视长度。
 * @param lastBeamAlpha 该帧整体透明度系数。
 * @param lastWorldUnitsPerPixel 该帧世界/像素换算比例。
 */
data class ProjectileVfxTelemetrySnapshot(
    val trackedCount: Int,
    val lastProjectileSpecId: String?,
    val lastElapsed: Float,
    val lastVisibleLength: Float,
    val lastBeamAlpha: Float,
    val lastWorldUnitsPerPixel: Float,
)
