package cn.kasuminova.astd.renderer.shader.runtime

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin

internal class FakeShaderRuntimeHost : ShaderRuntimeHost {
    override val customData: MutableMap<String, Any?> = HashMap()
    override var isPaused: Boolean = false
    val addedPlugins = ArrayList<BaseCombatLayeredRenderingPlugin>()

    override fun addLayeredRenderingPlugin(plugin: BaseCombatLayeredRenderingPlugin) {
        addedPlugins += plugin
    }
}
