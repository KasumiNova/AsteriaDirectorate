package cn.kasuminova.astd.renderer.projectile

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.input.InputEventAPI

class ASTDProjectileVfxRuntimePlugin : BaseEveryFrameCombatPlugin() {

    companion object {
        const val ENGINE_KEY: String = "astd_projectile_vfx_runtime_plugin"

        fun ensureInstalled(engine: CombatEngineAPI) {
            if (engine.customData[ENGINE_KEY] == null) {
                val plugin = ASTDProjectileVfxRuntimePlugin()
                engine.addPlugin(plugin)
                engine.customData[ENGINE_KEY] = plugin
            }
        }
    }

    private var engine: CombatEngineAPI? = null

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        ASTDProjectileVfxRuntimeManager.clear()
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val combatEngine = engine ?: return
        if (combatEngine.isPaused) return

        ASTDProjectileVfxRuntimeManager.advance(combatEngine, amount)
    }
}