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

/**
 * 武器常驻发光层管理器。
 *
 * 原版 `.wpn` 的 `turretGlowSprite`/`hardpointGlowSprite` 只在开火/充能时显示（动画类型 GLOW 系），
 * 没有常驻发光槽位；本管理器为登记武器补一层常亮发光贴图，以加法混合叠在武器之上。
 *
 * 贴图约定：`graphics/weapons/astd_<短名>_glow_ambient.png`（炮塔）与 `astd_<短名>_hp_glow_ambient.png`（挂点），
 * 与武器底图同画布同朝向，只含发光部位。
 */
internal object WeaponAmbientGlowManager {

    private const val ENGINE_KEY = "astd_weapon_ambient_glow_manager"
    private const val SCAN_INTERVAL = 0.5f

    private val log = Global.getLogger(WeaponAmbientGlowManager::class.java)

    /** 登记常驻发光的武器 id；贴图路径按命名约定派生。 */
    private val WEAPON_IDS = setOf(
        "astd_charge_needle",
        "astd_heavy_charge_needle",
        "astd_heavy_ion_pulse",
        "astd_qiongjue_phase_railgun",
    )

    /**
     * 预加载全部常驻发光贴图。
     * SSOptimizer 延迟加载下，未被任何地方引用的贴图不会上传 GL，
     * 裸 getSprite 拿到的是 textureID=0 的空壳，渲染不出任何内容。
     */
    fun preloadTextures() {
        for (weaponId in WEAPON_IDS) {
            for (suffix in listOf("_glow_ambient", "_hp_glow_ambient")) {
                val path = "graphics/weapons/${weaponId}${suffix}.png"
                try {
                    Global.getSettings().loadTexture(path)
                } catch (t: Throwable) {
                    log.warn("[ASTD] WeaponAmbientGlowManager texture preload failed: $path", t)
                }
            }
        }
    }

    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return

        try {
            val plugin = Plugin(engine)
            engine.addLayeredRenderingPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
        } catch (t: Throwable) {
            engine.customData[ENGINE_KEY] = false
            log.warn("[ASTD] WeaponAmbientGlowManager install failed", t)
        }
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
            log.info("[ASTD] WeaponAmbientGlowManager active")
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
                val weapon = it.next().key
                val ship = weapon.ship
                if (ship == null || !engine.isEntityInPlay(ship)) {
                    it.remove()
                }
            }
        }

        private fun scanWeapons() {
            val ships = try {
                engine.ships
            } catch (t: Throwable) {
                log.warn("[ASTD] WeaponAmbientGlowManager scan failed", t)
                return
            }

            for (ship in ships) {
                if (ship.isHulk) continue
                for (weapon in ship.allWeapons) {
                    val weaponId = weapon.id ?: continue
                    if (weaponId !in WEAPON_IDS) continue
                    if (attachments.containsKey(weapon)) continue

                    val attachment = loadGlowSprite(weapon, weaponId) ?: continue
                    attachments[weapon] = attachment
                }
            }
        }

        private fun loadGlowSprite(weapon: WeaponAPI, weaponId: String): Attachment? {
            val path = if (weapon.slot?.isHardpoint == true) {
                "graphics/weapons/${weaponId}_hp_glow_ambient.png"
            } else {
                "graphics/weapons/${weaponId}_glow_ambient.png"
            }
            return try {
                val sprite = Global.getSettings().getSprite(path)
                log.info("[ASTD] WeaponAmbientGlowManager attached weapon=$weaponId path=$path slot=${weapon.slot?.id} hardpoint=${weapon.slot?.isHardpoint}")
                Attachment(weapon, path, sprite.width, sprite.height)
            } catch (t: Throwable) {
                log.warn("[ASTD] WeaponAmbientGlowManager glow sprite load failed: $path", t)
                null
            }
        }

        private var firstRenderLogged = false

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (!firstRenderLogged) {
                firstRenderLogged = true
                log.info("[ASTD] WeaponAmbientGlowManager first render, attachments=${attachments.size}")
            }
            val alpha = viewport.alphaMult
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                for ((_, att) in attachments) {
                    val weapon = att.weapon
                    val ship = weapon.ship ?: continue
                    if (ship.isHulk) continue

                    // SpriteAPI 为全局共享缓存实例，尺寸/混合状态可能被其他渲染方改写，须逐帧重取并重置
                    val sprite = Global.getSettings().getSprite(att.spritePath)
                    sprite.setAdditiveBlend()
                    sprite.color = Color(255, 255, 255, 255)
                    sprite.alphaMult = alpha
                    sprite.setSize(att.width, att.height)
                    sprite.angle = weapon.currAngle - 90f
                    sprite.renderAtCenter(weapon.location.x, weapon.location.y)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }
    }
}
