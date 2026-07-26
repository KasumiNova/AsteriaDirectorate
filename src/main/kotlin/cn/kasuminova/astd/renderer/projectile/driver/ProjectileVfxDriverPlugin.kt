package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ProjectileHostImpl
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.input.InputEventAPI
import java.util.IdentityHashMap

/**
 * 新 RenderEntity 管线的战斗每帧插件：持有各弹体的 [ProjectileVfxDriver]，逐帧推进、剔除已结束者。
 * 与旧 `ASTDProjectileVfxRuntimePlugin` 并存——仅 [ProjectileVfxSpecs] 中已写手写 DSL 的 spec 走这条新路，其余仍走旧管线。
 */
class ProjectileVfxDriverPlugin : BaseEveryFrameCombatPlugin() {

    private val driversByProjectile = IdentityHashMap<DamagingProjectileAPI, ProjectileVfxDriver>()
    private var engine: CombatEngineAPI? = null

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        driversByProjectile.values.forEach { it.dispose() }
        driversByProjectile.clear()
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val combatEngine = engine ?: return
        if (combatEngine.isPaused) return
        val iterator = driversByProjectile.entries.iterator()
        while (iterator.hasNext()) {
            val driver = iterator.next().value
            driver.advance(combatEngine, amount)
            if (driver.state == ProjectileVfxDriverState.Removed) iterator.remove()
        }
    }

    companion object {
        const val ENGINE_KEY: String = "astd_projectile_vfx_driver_plugin"

        /** 该 projectileSpecId 是否已迁移到新管线（有手写 DSL）。派发处据此在新/旧管线间分流。 */
        fun isMigrated(projectileSpecId: String): Boolean = ProjectileVfxSpecs.has(projectileSpecId)

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
            if (plugin.driversByProjectile.containsKey(projectile)) return false
            val spec = ProjectileVfxSpecs.build(projectileSpecId) ?: return false
            plugin.driversByProjectile[projectile] = ProjectileVfxDriverImpl(ProjectileHostImpl(projectile), spec.tree, spec.policy)
            return true
        }

        /** 测试入口：该引擎当前登记到新管线的弹体驱动数（对称旧 `ASTDProjectileVfxRuntimeManager.trackedCountForTests`）。 */
        internal fun trackedCountForTests(engine: CombatEngineAPI): Int =
            (engine.customData[ENGINE_KEY] as? ProjectileVfxDriverPlugin)?.driversByProjectile?.size ?: 0
    }
}
