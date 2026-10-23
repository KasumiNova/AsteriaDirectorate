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

internal object LoadedMissileGlowLayer {

    private const val ENGINE_KEY = "astd_loaded_missile_glow_layer"
    private const val SCAN_INTERVAL = 0.5f

    private val log = Global.getLogger(LoadedMissileGlowLayer::class.java)

    private val GLOW_PATHS = mapOf(
        "astd_cuifeng_torpedo" to "graphics/weapons/astd_cuifeng_missile_glow.png",
        "astd_cuifeng_launcher" to "graphics/weapons/astd_cuifeng_missile_glow.png",
        "astd_ice_shard_mirv" to "graphics/weapons/astd_ice_shard_missile_glow.png",
        "astd_ice_shard_mirv_pod" to "graphics/weapons/astd_ice_shard_missile_glow.png",
    )

    private val loadedPaths = HashSet<String>()

    private val missingRenderDataWarned = HashSet<String>()

    fun preloadTextures() {
        for (path in GLOW_PATHS.values.toSet()) {
            try {
                Global.getSettings().loadTexture(path)
                loadedPaths += path
            } catch (t: Throwable) {
                log.warn("[ASTD] LoadedMissileGlowLayer texture preload failed: $path", t)
            }
        }
    }

    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return
        val plugin = Plugin(engine)
        engine.addLayeredRenderingPlugin(plugin)
        engine.customData[ENGINE_KEY] = plugin
    }

    private class Attachment(
        val weapon: WeaponAPI,
        val spritePath: String,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(
        CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    ) {

        private val attachments = LinkedHashMap<WeaponAPI, Attachment>()
        private var scanAcc = 0f
        private var firstRenderLogged = false

        override fun init(entity: CombatEntityAPI?) {
            super.init(entity)
            log.info("[ASTD] LoadedMissileGlowLayer active")
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> =
            EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun advance(amount: Float) {
            scanAcc += amount
            if (scanAcc >= SCAN_INTERVAL) {
                scanAcc = 0f
                scanWeapons()
            }

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val ship = it.next().value.weapon.ship
                if (ship == null || !engine.isEntityInPlay(ship)) {
                    it.remove()
                }
            }
        }

        private fun scanWeapons() {
            for (ship in engine.ships) {
                if (ship.isHulk) continue
                for (weapon in ship.allWeapons) {
                    if (weapon.slot?.isHidden == true) continue
                    val path = GLOW_PATHS[weapon.id] ?: continue
                    if (path !in loadedPaths) continue
                    if (attachments.containsKey(weapon)) continue
                    attachments[weapon] = Attachment(weapon, path)
                    log.info("[ASTD] LoadedMissileGlowLayer attached weapon=${weapon.id} path=$path weaponSlot=${weapon.slot?.id}")
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (attachments.isEmpty()) return
            if (!firstRenderLogged) {
                firstRenderLogged = true
                log.info("[ASTD] LoadedMissileGlowLayer first render, attachments=${attachments.size}")
            }

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                for ((_, att) in attachments) {
                    val weapon = att.weapon
                    val ship = weapon.ship ?: continue
                    if (ship.isHulk || weapon.isDisabled) continue
                    // 弹药耗尽后原版 brightness 可能保留旧值，必须按 ammo 跳过渲染。
                    if (weapon.ammo <= 0) continue
                    val renderData = weapon.missileRenderData
                    if (renderData == null) {
                        val weaponId = weapon.id
                        if (missingRenderDataWarned.add(weaponId)) {
                            log.warn("[ASTD] 挂载弹体光效：weapon=$weaponId 无 missileRenderData（缺失 RENDER_LOADED_MISSILES？），光效停摆")
                        }
                        continue
                    }
                    for (data in renderData) {
                        val brightness = data.brightness.coerceIn(0f, 1f)
                        if (brightness <= 0.002f) continue

                        val sprite = Global.getSettings().getSprite(att.spritePath)
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
