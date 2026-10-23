package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLease
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseSpec
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.manager.TextureManager
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.IdentityHashMap
import kotlin.math.cos
import kotlin.math.sin

object ShipGlowRenderer {

    private const val ENGINE_KEY = "astd_ship_glow_renderer"

    private const val MEMORY_RESTORE_SCRIPT_ADDED = "\$astd_ship_glow_restore_script_added"

    private const val OVERLAY_SPRITE_PREFIX = "graphics/ships/"

    private val log = AstdLog.logger

    private class TextureInfo(val texId: Int, val uEnd: Float, val vEnd: Float)

    private val overlaySpritePaths = HashMap<String, String>()

    private val textures = HashMap<String, TextureInfo>()

    private val redVariantPaths = HashMap<String, String>()

    // weapon.sprite 是规格级共享实例，战斗内修改的颜色会残留到装配界面，必须保存并在战役侧恢复。
    private val suppressedSprites = IdentityHashMap<SpriteAPI, Color>()

    fun suppressVanillaSprite(sprite: SpriteAPI) {
        suppressedSprites.getOrPut(sprite) { Color(sprite.color.red, sprite.color.green, sprite.color.blue, sprite.color.alpha) }
        if (sprite.color.alpha != 0) {
            sprite.color = Color(sprite.color.red, sprite.color.green, sprite.color.blue, 0)
        }
    }

    fun restoreSuppressedSprites() {
        if (suppressedSprites.isEmpty()) return
        for ((sprite, color) in suppressedSprites) {
            try {
                sprite.color = color
            } catch (t: Throwable) {
                log.warn("[ASTD] 舰船覆盖发光层渲染器：贴图颜色恢复异常", t)
            }
        }
        suppressedSprites.clear()
    }

    fun ensureRestoreScriptRegistered() {
        val sector = Global.getSector() ?: return
        val mem = sector.memoryWithoutUpdate
        if (mem.getBoolean(MEMORY_RESTORE_SCRIPT_ADDED)) return
        sector.addScript(SpriteRestoreScript())
        mem.set(MEMORY_RESTORE_SCRIPT_ADDED, true)
    }

    class SpriteRestoreScript : EveryFrameScript {
        override fun isDone(): Boolean = false
        override fun runWhilePaused(): Boolean = true
        override fun advance(amount: Float) = restoreSuppressedSprites()
    }

    fun preloadTextures() {
        for (spec in Global.getSettings().allWeaponSpecs) {
            val id = spec.weaponId ?: continue
            if (spec.type != WeaponAPI.WeaponType.DECORATIVE) continue
            val path = spec.turretSpriteName ?: continue
            if (!path.startsWith(OVERLAY_SPRITE_PREFIX)) continue
            if (load(path)) overlaySpritePaths[id] = path
        }

        for (hull in Global.getSettings().allShipHullSpecs) {
            if (!hull.isPhase || hull.shipDefenseId != GravityPhaseVisualEffect.SYSTEM_ID) continue
            val overlayWeaponId = hull.builtInWeapons?.values?.firstOrNull { it in overlaySpritePaths } ?: continue
            val redPath = overlaySpritePaths.getValue(overlayWeaponId).removeSuffix(".png") + "_red.png"
            if (load(redPath)) redVariantPaths[overlayWeaponId] = redPath
        }
        log.info("[ASTD] 舰船覆盖发光层渲染器贴图预加载完成：基础 ${overlaySpritePaths.size} 张，红色变体 ${redVariantPaths.size} 张")
    }

    private fun load(path: String): Boolean {
        return try {
            Global.getSettings().loadTexture(path)
            val tex = TextureManager.loadTexture(path)
            if (tex[0] <= 0) {
                log.warn("[ASTD] 舰船覆盖发光层渲染器：BoxUtil 贴图上传失败 $path")
                return false
            }
            textures[path] = TextureInfo(tex[0], tex[4].toFloat() / tex[2], tex[5].toFloat() / tex[3])
            true
        } catch (t: Throwable) {
            log.warn("[ASTD] 舰船覆盖发光层渲染器：贴图预加载失败 $path", t)
            false
        }
    }

    fun isOverlayWeapon(weaponId: String): Boolean = weaponId in overlaySpritePaths

    fun setRecolor(ship: ShipAPI, blend: Float) {
        val engine = Global.getCombatEngine() ?: return
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return
        plugin.setRecolor(ship, blend)
    }

    fun onWeaponFrame(engine: CombatEngineAPI, weapon: WeaponAPI, baseColor: Color) {
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return
        plugin.track(weapon, baseColor)
    }

    fun isReady(engine: CombatEngineAPI): Boolean = engine.customData[ENGINE_KEY] is Plugin

    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return
        try {
            BoxUtilCombatVfx.ensureReady(engine)
            val plugin = Plugin(engine)
            engine.addPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
            log.info("[ASTD] ShipGlowRenderer installed")
        } catch (t: Throwable) {
            log.warn("[ASTD] ShipGlowRenderer install failed", t)
        }
    }

    private class Attachment(
        val ship: ShipAPI,
        val weaponId: String,
        val base: SpriteLease,
        var recolor: SpriteLease?,
        var recolorBlend: Float = 0f,
        var baseColor: Color = Color.WHITE,
        val centerOffsetX: Float,
        val centerOffsetY: Float,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {

        private val attachments = LinkedHashMap<Int, Attachment>()
        private val missingRecolorWarned = HashSet<String>()

        private val failedShips = HashSet<Int>()

        fun track(weapon: WeaponAPI, baseColor: Color) {
            val ship = weapon.ship ?: return
            val key = System.identityHashCode(ship)
            val existing = attachments[key]
            if (existing != null) {
                existing.baseColor = baseColor
                return
            }
            if (ship.isHulk || key in failedShips) return
            val attachment = try {
                createAttachment(ship, weapon, baseColor)
            } catch (t: Throwable) {
                log.warn("[ASTD] 舰船覆盖发光层渲染器：实体创建异常（ship=${ship.hullSpec?.hullId}），该舰不再重试", t)
                null
            } ?: run {
                failedShips += key
                return
            }
            attachments[key] = attachment
            log.info("[ASTD] 舰船覆盖发光层实体已创建：ship=${ship.hullSpec?.hullId} weapon=${attachment.weaponId} " +
                "size=${ship.spriteAPI?.width}x${ship.spriteAPI?.height} tex=${textures[overlaySpritePaths[attachment.weaponId]]?.texId}")
        }

        fun setRecolor(ship: ShipAPI, blend: Float) {
            val att = attachments[System.identityHashCode(ship)] ?: return
            att.recolorBlend = blend.coerceIn(0f, 1f)
        }

        private fun createAttachment(
            ship: ShipAPI,
            weapon: WeaponAPI,
            baseColor: Color,
        ): Attachment? {
            val sprite = ship.spriteAPI ?: return null
            val path = overlaySpritePaths[weapon.spec?.weaponId] ?: return null
            val base = createEntity(ship, path) ?: return null
            return Attachment(
                ship = ship,
                weaponId = weapon.spec.weaponId,
                base = base,
                recolor = null,
                baseColor = baseColor,
                centerOffsetX = sprite.width / 2f - sprite.centerX,
                centerOffsetY = sprite.height / 2f - sprite.centerY,
            )
        }

        private fun createEntity(ship: ShipAPI, path: String): SpriteLease? {
            val tex = textures[path] ?: return null
            val shipSprite = ship.spriteAPI ?: return null
            // FIXED_2D 单实例路径在当前 BoxUtil/SSOptimizer 环境不产生像素，必须使用实体本体变换的非实例化渲染。
            return PooledCombatVfx.checkoutSprite(
                engine,
                SpriteLeaseKey(
                    layer = CombatEngineLayers.ABOVE_SHIPS_LAYER,
                    textureKey = "tex:$path",
                    additive = true,
                    instanced = false,
                    capacity = 8,
                ),
                SpriteLeaseSpec(
                    location = Vector2f(ship.location),
                    facingDeg = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f),
                    texId = tex.texId,
                    uvEndX = tex.uEnd,
                    uvEndY = tex.vEnd,
                    bindEmissive = true,
                    baseSizeHalfWidth = shipSprite.width / 2f,
                    baseSizeHalfHeight = shipSprite.height / 2f,
                    color = Color(1f, 1f, 1f, 0f),
                    emissiveColor = Color(0.6f, 0.6f, 0.6f, 0f),
                    glowPower = 0.05f,
                    alphaToEmissive = 1f,
                    isAdditionEmissive = true,
                    isIgnoreIllumination = true,
                ),
            )
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (attachments.isEmpty()) return

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val att = it.next().value
                val ship = att.ship
                if (ship.isHulk || !engine.isEntityInPlay(ship)) {
                    att.base.release(0f)
                    att.recolor?.release(0f)
                    it.remove()
                    continue
                }
                updateAttachment(att)
            }
        }

        private fun updateAttachment(att: Attachment) {
            val ship = att.ship
            val facing = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f)
            val theta = Math.toRadians(facing.toDouble())
            val loc = Vector2f(
                ship.location.x + (att.centerOffsetX * cos(theta) - att.centerOffsetY * sin(theta)).toFloat(),
                ship.location.y + (att.centerOffsetX * sin(theta) + att.centerOffsetY * cos(theta)).toFloat(),
            )

            val blend = att.recolorBlend
            if (blend > 0f && att.recolor == null) {
                val redPath = redVariantPaths[att.weaponId]
                if (redPath != null) {
                    att.recolor = createEntity(ship, redPath)
                    if (att.recolor != null) {
                        log.info("[ASTD] 舰船覆盖发光层换色实体已创建：ship=${ship.hullSpec?.hullId} path=$redPath")
                    }
                } else if (missingRecolorWarned.add(att.weaponId)) {
                    log.warn("[ASTD] 舰船覆盖发光层渲染器：${att.weaponId} 无红色变体预载，相位换色缺席")
                }
            }

            val baseAlpha = 1f - blend
            att.base.entity.setStateVanilla(loc, facing)
            att.base.entity.materialData.setColor(att.baseColor)
            att.base.entity.materialData.setColorAlpha(baseAlpha)
            att.base.entity.materialData.setEmissiveColorAlpha(baseAlpha)

            att.recolor?.let {
                it.entity.setStateVanilla(loc, facing)
                it.entity.materialData.setColorAlpha(blend)
                it.entity.materialData.setEmissiveColorAlpha(blend)
            }
        }
    }
}
