package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.renderer.effect.system.WeaponGlowLayer.preloadTextures
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

/**
 * 武器充能发光层：按充能进度渲染 `_charge` / `_hp_charge` 叠加贴图。
 * 常驻微光（`_glow_ambient`）已烘入武器底图，不再走渲染层。
 */
internal object WeaponGlowLayer {

    private const val ENGINE_KEY = "astd_weapon_glow_layer"
    private const val SCAN_INTERVAL = 0.5f

    private const val CHARGE_MIN = 0.001f

    private const val TURRET_SUFFIX = "_charge"
    private const val HARDPOINT_SUFFIX = "_hp_charge"

    private val log = Global.getLogger(WeaponGlowLayer::class.java)

    private val CHARGE_WEAPON_IDS = setOf(
        "astd_piercing_lance",
    )

    private val loadedPaths = HashSet<String>()

    fun preloadTextures() {
        for (weaponId in CHARGE_WEAPON_IDS) {
            for (suffix in listOf(TURRET_SUFFIX, HARDPOINT_SUFFIX)) {
                val path = "graphics/weapons/${weaponId}${suffix}.png"
                try {
                    Global.getSettings().loadTexture(path)
                    loadedPaths += path
                } catch (t: Throwable) {
                    log.warn("[ASTD] WeaponGlowLayer texture preload failed: $path", t)
                }
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
        val width: Float,
        val height: Float,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(
        CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    ) {

        private val attachments = LinkedHashMap<WeaponAPI, Attachment>()
        private var scanAcc = 0f

        override fun init(entity: CombatEntityAPI?) {
            super.init(entity)
            log.info("[ASTD] WeaponGlowLayer active")
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
                val weapon = it.next().value.weapon
                val ship = weapon.ship
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
                    val weaponId = weapon.id ?: continue
                    if (weaponId !in CHARGE_WEAPON_IDS) continue
                    if (attachments.containsKey(weapon)) continue
                    val attachment = loadGlowSprite(weapon, weaponId) ?: continue
                    attachments[weapon] = attachment
                }
            }
        }

        private fun loadGlowSprite(weapon: WeaponAPI, weaponId: String): Attachment? {
            val path = if (weapon.slot?.isHardpoint == true) {
                "graphics/weapons/${weaponId}${HARDPOINT_SUFFIX}.png"
            } else {
                "graphics/weapons/${weaponId}${TURRET_SUFFIX}.png"
            }
            if (path !in loadedPaths) return null
            val sprite = Global.getSettings().getSprite(path)
            log.info("[ASTD] WeaponGlowLayer attached weapon=$weaponId path=$path weaponSlot=${weapon.slot?.id} hardpoint=${weapon.slot?.isHardpoint}")
            return Attachment(weapon, path, sprite.width, sprite.height)
        }

        private var firstRenderLogged = false

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (!firstRenderLogged) {
                firstRenderLogged = true
                log.info("[ASTD] WeaponGlowLayer first render, attachments=${attachments.size}")
            }
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                for ((_, att) in attachments) {
                    val weapon = att.weapon
                    val ship = weapon.ship ?: continue
                    if (ship.isHulk) continue
                    val baseSprite = weapon.sprite
                    if (baseSprite == null || baseSprite.width <= 0.01f || baseSprite.height <= 0.01f) continue
                    if (weapon.isFiring || weapon.cooldownRemaining > 0.01f) continue
                    if (weapon.chargeLevel <= CHARGE_MIN) continue
                    val alpha = weapon.chargeLevel * viewport.alphaMult

                    // getSprite 返回共享实例，其他渲染方可能修改其状态，必须逐帧重取并重置。
                    val sprite = Global.getSettings().getSprite(att.spritePath)
                    sprite.setAdditiveBlend()
                    sprite.color = Color(255, 255, 255, 255)
                    sprite.alphaMult = alpha
                    sprite.setSize(att.width, att.height)
                    if (weapon.slot?.isHardpoint == true) {
                        sprite.setCenter(att.width / 2f, att.height / 4f)
                    } else {
                        sprite.setCenter(att.width / 2f, att.height / 2f)
                    }
                    sprite.angle = weapon.currAngle - 90f
                    sprite.renderAtCenter(weapon.location.x, weapon.location.y)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }
    }
}
