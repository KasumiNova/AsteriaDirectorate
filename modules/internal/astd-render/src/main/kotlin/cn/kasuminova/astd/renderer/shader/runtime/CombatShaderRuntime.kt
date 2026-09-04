package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI

/**
 * Combat-scoped shader runtime host.
 *
 * A combat engine owns one runtime instance. The runtime owns submissions and
 * installs one layered plugin that performs all shader rendering.
 */
class CombatShaderRuntime private constructor(private val host: ShaderRuntimeHost) {
    private val queue = ShaderRenderQueue()
    private val plugin = ShaderLayerPlugin(queue)

    val sink: ShaderSink = ShaderSink(queue)
    var isExpired: Boolean = false
        private set

    init {
        host.addLayeredRenderingPlugin(plugin)
    }

    fun advance(amount: Float) {
        if (isExpired || host.isPaused) return
        queue.advance(amount)
    }

    fun cleanup() {
        queue.clear()
        plugin.cleanup()
        isExpired = true
    }

    /** 测试入口（跨模块测试可达，故 public）：该层当前的提交快照。 */
    fun snapshotsForTests(layer: ShaderEffectLayer): List<ShaderSubmission> = queue.snapshotsForLayer(layer)

    companion object {
        const val ENGINE_KEY = "astd_shader_runtime"

        fun ensure(engine: CombatEngineAPI): CombatShaderRuntime = ensure(CombatEngineShaderRuntimeHost(engine))

        fun ensure(host: ShaderRuntimeHost): CombatShaderRuntime {
            val existing = host.customData[ENGINE_KEY] as? CombatShaderRuntime
            if (existing != null && !existing.isExpired) return existing
            val runtime = CombatShaderRuntime(host)
            host.customData[ENGINE_KEY] = runtime
            return runtime
        }
    }
}

/**
 * Small host boundary used to keep runtime tests free of Starsector proxies.
 */
interface ShaderRuntimeHost {
    val customData: MutableMap<String, Any?>
    val isPaused: Boolean
    fun addLayeredRenderingPlugin(plugin: BaseCombatLayeredRenderingPlugin)
}

private class CombatEngineShaderRuntimeHost(private val engine: CombatEngineAPI) : ShaderRuntimeHost {
    override val customData: MutableMap<String, Any?>
        get() = engine.customData

    override val isPaused: Boolean
        get() = engine.isPaused

    override fun addLayeredRenderingPlugin(plugin: BaseCombatLayeredRenderingPlugin) {
        engine.addLayeredRenderingPlugin(plugin)
    }
}
