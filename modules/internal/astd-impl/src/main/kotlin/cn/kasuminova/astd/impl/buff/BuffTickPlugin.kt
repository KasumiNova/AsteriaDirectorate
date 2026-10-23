package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.impl.buff.BuffTickPlugin.Companion.ENGINE_PLUGIN_KEY
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.input.InputEventAPI

class BuffTickPlugin : BaseEveryFrameCombatPlugin() {

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val engine = Global.getCombatEngine() ?: return
        tick(engine, amount)
    }

    internal fun tick(engine: CombatEngineAPI, amount: Float) {
        if (engine.isPaused) return
        for (ship in engine.ships) {
            if (ship == null) continue
            val host = ship.customData[BuffHostImpl.HOST_KEY] as? BuffHostImpl ?: continue
            host.tick(ship, amount)
        }
    }

    companion object {
        const val ENGINE_PLUGIN_KEY = "astd_buff_tick_plugin"

        fun ensure(engine: CombatEngineAPI?) {
            if (engine == null) return
            if (engine.customData[ENGINE_PLUGIN_KEY] is BuffTickPlugin) return
            val plugin = BuffTickPlugin()
            engine.customData[ENGINE_PLUGIN_KEY] = plugin
            engine.addPlugin(plugin)
        }
    }
}
