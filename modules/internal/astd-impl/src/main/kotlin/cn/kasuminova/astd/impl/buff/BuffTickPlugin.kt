package cn.kasuminova.astd.impl.buff

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.input.InputEventAPI

/**
 * Buff 引擎级每帧心跳：驱动所有已登记 [BuffHostImpl] 的 advance 与回收。
 *
 * 动机：叠层衰减、宿主失效回收（hulk/换装/isHostValid=false）需要一个统一的每帧驱动点；
 * 引擎级单插件注册表沿用 `StackingShipBuffs.ensurePlugin` 同款模式（`engine.customData` 存引用去重）。
 *
 * 遍历成本：每帧 `engine.ships` × 惰性登记（无 Buff 的船 customData 无 [BuffHostImpl.HOST_KEY]，直接跳过）。
 * 暂停（`engine.isPaused`）直接跳过整帧。
 */
class BuffTickPlugin : BaseEveryFrameCombatPlugin() {

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val engine = Global.getCombatEngine() ?: return
        tick(engine, amount)
    }

    /**
     * 每帧心跳主体（与 [advance] 分离以便单元测试直接注入引擎）。
     */
    internal fun tick(engine: CombatEngineAPI, amount: Float) {
        if (engine.isPaused) return
        for (ship in engine.ships) {
            if (ship == null) continue
            val host = ship.customData[BuffHostImpl.HOST_KEY] as? BuffHostImpl ?: continue
            host.tick(ship, amount)
        }
    }

    companion object {
        /** 插件在 engine.customData 上的去重登记键。 */
        const val ENGINE_PLUGIN_KEY = "astd_buff_tick_plugin"

        /**
         * 确保引擎上登记了心跳插件（幂等，按 [ENGINE_PLUGIN_KEY] 去重）。
         * 引擎不可用（仅测试环境/非战斗场景）时跳过，下一次可用调用补登记。
         */
        fun ensure(engine: CombatEngineAPI?) {
            if (engine == null) return
            if (engine.customData[ENGINE_PLUGIN_KEY] is BuffTickPlugin) return
            val plugin = BuffTickPlugin()
            engine.customData[ENGINE_PLUGIN_KEY] = plugin
            engine.addPlugin(plugin)
        }
    }
}
