package cn.kasuminova.astd.renderer.effect.system

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.EnumSet

internal object GeminiDemRackVisuals {

    private const val ENGINE_KEY = "astd_gemini_dem_rack_visuals"

    private val RACK_WEAPON_IDS = setOf(
        "astd_gemini_dem_launcher",
        "astd_gemini_dem_pod",
    )

    private val BARREL_GLOW_PATHS = listOf(
        "graphics/weapons/astd_gemini_dem_missile_glow_red.png",
        "graphics/weapons/astd_gemini_dem_missile_glow_blue.png",
    )

    private val BARREL_TINTS = listOf(
        Color(255, 125, 115, 255),
        Color(135, 170, 255, 255),
    )

    private val log = Global.getLogger(GeminiDemRackVisuals::class.java)

    private val loadedPaths = HashSet<String>()

    fun preloadTextures() {
        for (path in BARREL_GLOW_PATHS) {
            try {
                Global.getSettings().loadTexture(path)
                loadedPaths += path
            } catch (t: Throwable) {
                log.warn("[ASTD] GeminiDemRackVisuals texture preload failed: $path", t)
            }
        }
    }

    fun onWeaponFrame(engine: CombatEngineAPI, weapon: WeaponAPI) {
        val weaponId = weapon.spec?.weaponId ?: return
        if (weaponId !in RACK_WEAPON_IDS) return

        ensureInstalled(engine)
        getPlugin(engine)?.track(weapon)

        val renderData = weapon.missileRenderData
        if (renderData == null) {
            if (missingRenderDataWarned.add(weaponId)) {
                log.warn("[ASTD] 双子星 DEM 导轨：weapon=$weaponId 无 missileRenderData（缺失 RENDER_LOADED_MISSILES？），弹体染色/发光停摆")
            }
            return
        }
        val loaded = isSalvoLoaded(weapon)
        for ((index, data) in renderData.withIndex()) {
            val tint = BARREL_TINTS.getOrNull(index) ?: break
            try {
                // 原版冷却期只更新已击发炮管的 brightness，未击发炮管会残留亮度，因此冷却期必须同时隐藏两管。
                data.sprite.color = if (loaded) tint
                else Color(tint.red, tint.green, tint.blue, 0)
            } catch (t: Throwable) {
                log.warn("[ASTD] 双子星 DEM 导轨：弹体染色失败 weapon=$weaponId barrel=$index", t)
            }
        }
    }

    private fun isSalvoLoaded(weapon: WeaponAPI): Boolean =
        weapon.cooldownRemaining <= 0f && weapon.ammo >= BARREL_TINTS.size

    private val missingRenderDataWarned = HashSet<String>()

    private fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return
        val plugin = Plugin(engine)
        engine.addLayeredRenderingPlugin(plugin)
        engine.customData[ENGINE_KEY] = plugin
    }

    private fun getPlugin(engine: CombatEngineAPI): Plugin? = engine.customData[ENGINE_KEY] as? Plugin

    private class Plugin(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(
        CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    ) {

        private val weapons = LinkedHashSet<WeaponAPI>()
        private var firstRenderLogged = false

        fun track(weapon: WeaponAPI) {
            weapons += weapon
        }

        override fun init(entity: CombatEntityAPI?) {
            super.init(entity)
            log.info("[ASTD] GeminiDemRackVisuals active")
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> =
            EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun advance(amount: Float) {
            val it = weapons.iterator()
            while (it.hasNext()) {
                val ship = it.next().ship
                if (ship == null || !engine.isEntityInPlay(ship)) {
                    it.remove()
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (weapons.isEmpty()) return
            if (!firstRenderLogged) {
                firstRenderLogged = true
                log.info("[ASTD] GeminiDemRackVisuals first render, weapons=${weapons.size}")
            }

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                for (weapon in weapons) {
                    val ship = weapon.ship ?: continue
                    if (ship.isHulk || weapon.isDisabled) continue
                    if (!isSalvoLoaded(weapon)) continue
                    val renderData = weapon.missileRenderData ?: continue
                    for ((index, data) in renderData.withIndex()) {
                        val glowPath = BARREL_GLOW_PATHS.getOrNull(index) ?: break
                        if (glowPath !in loadedPaths) continue
                        val brightness = data.brightness.coerceIn(0f, 1f)
                        if (brightness <= 0.002f) continue

                        val sprite = Global.getSettings().getSprite(glowPath)
                        sprite.setAdditiveBlend()
                        sprite.color = Color(255, 255, 255, 255)
                        sprite.alphaMult = brightness * viewport.alphaMult
                        sprite.setSize(data.sprite.width, data.sprite.height)
                        sprite.angle = data.missileFacing - 90f
                        val center = data.missileCenterLocation ?: continue
                        sprite.renderAtCenter(center.x, center.y)
                    }
                }
            } finally {
                GL11.glPopAttrib()
            }
        }
    }
}
