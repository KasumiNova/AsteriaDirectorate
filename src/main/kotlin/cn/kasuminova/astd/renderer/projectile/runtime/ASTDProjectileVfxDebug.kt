package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPreset
import com.fs.starfarer.api.Global
import org.json.JSONObject

object ASTDProjectileVfxDebug {
    private const val CONFIG_PATH = "data/config/astd_projectile_vfx_debug.json"
    private val log = Global.getLogger(ASTDProjectileVfxDebug::class.java)

    data class Visibility(
        val trail: Boolean = true,
        val head: Boolean = true,
        val glow: Boolean = true,
        val mist: Boolean = true,
        val sideWisps: Boolean = true,
        val ribbon: Boolean = true,
        val logLayoutOnce: Boolean = false,
    )

    private var cached: Visibility? = null
    private val loggedPresets = HashSet<String>()

    fun visibility(): Visibility {
        cached?.let { return it }
        val loaded = loadVisibility()
        cached = loaded
        return loaded
    }

    fun resetForTests() {
        cached = null
        loggedPresets.clear()
    }

    fun layersEnabledForTests(preset: ASTDProjectileVfxPreset, visibility: Visibility = Visibility()): List<String> {
        val enabled = ArrayList<String>()
        if (visibility.trail && preset.trailEntities.isNotEmpty()) enabled += "trail"
        if (visibility.glow && preset.glowLayers.any { it.enabled } && preset.trailEntities.isNotEmpty()) enabled += "glow"
        if (visibility.sideWisps && preset.sideWispLayers.any { it.enabled } && preset.trailEntities.isNotEmpty()) enabled += "sideWisps"
        if (visibility.head && preset.headLayers.any { it.enabled } && preset.trailEntities.isNotEmpty()) enabled += "head"
        if (visibility.mist && preset.mistLayers.any { it.enabled } && preset.trailEntities.isNotEmpty()) enabled += "mist"
        if (visibility.ribbon && preset.ribbonDecorations.any { it.enabled }) enabled += "ribbon"
        return enabled
    }

    fun logLayoutOnce(preset: ASTDProjectileVfxPreset, context: ASTDProjectileVfxRenderContext, visibility: Visibility = visibility()) {
        if (!visibility.logLayoutOnce || !loggedPresets.add(preset.id)) return
        val baseLayer = preset.trailEntities.firstOrNull()?.layers?.firstOrNull() ?: return
        log.info(
            "ASTD projectile VFX layout preset=${preset.id} projectile=${context.projectileSpecId} " +
                "visibleLength=${context.visibleLength} widthBase=${ASTDProjectileVfxLayout.widthBase(baseLayer)} " +
                "facing=${context.renderFacing} layers=${layersEnabledForTests(preset, visibility).joinToString(",")}",
        )
    }

    private fun loadVisibility(): Visibility {
        return try {
            val json = Global.getSettings()?.loadJSON(CONFIG_PATH) as? JSONObject ?: return Visibility()
            Visibility(
                trail = json.optBoolean("trail", true),
                head = json.optBoolean("head", true),
                glow = json.optBoolean("glow", true),
                mist = json.optBoolean("mist", true),
                sideWisps = json.optBoolean("sideWisps", true),
                ribbon = json.optBoolean("ribbon", true),
                logLayoutOnce = json.optBoolean("logLayoutOnce", false),
            )
        } catch (ex: Exception) {
            Visibility()
        }
    }
}
