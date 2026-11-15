package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLease
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import org.magiclib.util.MagicUI
import java.awt.Color
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

object Xc002GhostWingsEffect {

    private const val ENGINE_KEY = "astd_xc_002_ghost_wings"

    private const val HUD_ENGINE_KEY = "astd_xc_002_ghost_wings_hud"

    const val HULL_ID = "astd_xc_002"
    const val SPRITE_DARK = "graphics/ships/astd_xc_002_ghost_dark.png"
    const val SPRITE_LIGHT = "graphics/ships/astd_xc_002_ghost_light.png"

    private const val WING_WIDTH = 202f
    private const val WING_HEIGHT = 264f

    private const val TIER_MID = 30f
    private const val TIER_HIGH = 80f

    private const val JITTER_AMPLITUDE = 2.5f

    private const val HUD_BAR_OFFSET_Y = 16f

    private const val LABEL_GAP = 5f

    private const val LABEL_FONT_SIZE = 10f

    private const val MAGICUI_TEXT_FONT_SIZE = 14f

    private const val LABEL_OFFSET_Y = -3.5f

    private const val AFTERIMAGE_INTERVAL_MID = 0.08f
    private const val AFTERIMAGE_ALPHA = 0.12f
    private const val AFTERIMAGE_DURATION = 0.4f

    private const val DARK_POOL_KEY = "xc002_ghost_wing_dark"
    private const val LIGHT_POOL_KEY = "xc002_ghost_wing_light"

    private val HUD_CHARGE_LOW = Color(120, 170, 255)
    private val HUD_CHARGE_READY = Color(150, 205, 255)
    private val HUD_BORDER = Color(30, 50, 90, 200)

    private val log = AstdLog.logger

    private val loadedSprites = mutableSetOf<String>()

    private val labelFontMissing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun preloadTextures() {
        var loaded = 0
        for (path in listOf(SPRITE_DARK, SPRITE_LIGHT)) {
            try {
                Global.getSettings().loadTexture(path)
                loadedSprites += path
                loaded++
            } catch (t: Throwable) {
                log.warn("[ASTD] 虚数之翼光翼贴图预加载失败 $path", t)
            }
        }
        log.info("[ASTD] 虚数之翼光翼贴图预加载完成：$loaded/2")
    }

    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] == null) {
            try {
                val plugin = Plugin(engine)
                engine.addPlugin(plugin)
                engine.customData[ENGINE_KEY] = plugin
            } catch (t: Throwable) {
                log.warn("[ASTD] 虚数之翼光翼插件安装失败", t)
            }
        }
        if (engine.customData[HUD_ENGINE_KEY] == null) {
            try {
                val hud = HudPlugin(engine)
                engine.addPlugin(hud)
                engine.customData[HUD_ENGINE_KEY] = hud
            } catch (t: Throwable) {
                log.warn("[ASTD] 虚数之翼充能条插件安装失败", t)
            }
        }
    }

    fun track(engine: CombatEngineAPI, ship: ShipAPI) {
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return
        plugin.track(ship)
    }

    private class Attachment(
        val ship: ShipAPI,
        val dark: SpriteLease,
        val light: SpriteLease,
        var breathPhase: Float = 0f,
        var afterimageTimer: Float = 0f,
        var alpha: Float = 0f,
        var jitterX: Float = 0f,
        var jitterY: Float = 0f,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {

        private val attachments = LinkedHashMap<Int, Attachment>()

        private val failedShips = HashSet<Int>()

        fun track(ship: ShipAPI) {
            val key = System.identityHashCode(ship)
            if (attachments.containsKey(key) || key in failedShips) return
            if (ship.isHulk) return
            val attachment = createAttachment(ship)
            if (attachment == null) {
                failedShips += key
                log.warn("[ASTD] 虚数之翼光翼未挂载（ship=${ship.hullSpec?.hullId}，租约检出失败）")
                return
            }
            attachments[key] = attachment
            log.info("[ASTD] 虚数之翼光翼挂载已创建：ship=${ship.hullSpec?.hullId}，attachments=${attachments.size}")
        }

        private fun createAttachment(ship: ShipAPI): Attachment? {
            val facing = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f)
            val dark = checkoutWing(ship, SPRITE_DARK, DARK_POOL_KEY, facing) ?: return null
            val light = checkoutWing(ship, SPRITE_LIGHT, LIGHT_POOL_KEY, facing)
            if (light == null) {
                dark.release(0f)
                return null
            }
            return Attachment(ship, dark, light)
        }

        private fun checkoutWing(ship: ShipAPI, spritePath: String, poolKey: String, facing: Float): SpriteLease? {
            if (spritePath !in loadedSprites) {
                log.warn("[ASTD] 虚数之翼光翼贴图未预加载，跳过该层：$spritePath")
                return null
            }
            return PooledCombatVfx.checkoutSprite(
                engine,
                SpriteLeaseKey(
                    layer = CombatEngineLayers.ABOVE_SHIPS_LAYER,
                    textureKey = poolKey,
                    additive = true,
                    instanced = false,
                    capacity = 8,
                ),
                SpriteLeaseSpec(
                    location = Vector2f(ship.location),
                    facingDeg = facing,
                    spritePath = spritePath,
                    bindEmissive = false,
                    baseSizeHalfWidth = WING_WIDTH / 2f,
                    baseSizeHalfHeight = WING_HEIGHT / 2f,
                    color = Color(255, 255, 255, 0),
                    glowPower = 0f,
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
                    att.dark.release(0f)
                    att.light.release(0f)
                    it.remove()
                    continue
                }
                if (engine.isPaused) continue
                updateAttachment(att, amount)
            }
        }

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship
            val pool = ImaginaryCharge.of(ship)
            val charge = pool.charge
            val period = when {
                charge >= TIER_HIGH -> 0.5f
                charge >= TIER_MID -> 1.5f - 0.5f * (charge - TIER_MID) / (TIER_HIGH - TIER_MID)
                else -> 3f - 0.5f * charge / TIER_MID
            }
            val alphaMin = when {
                charge >= TIER_HIGH -> 0.85f
                charge >= TIER_MID -> 0.45f
                else -> 0.15f
            }
            val alphaMax = when {
                charge >= TIER_HIGH -> 1f
                charge >= TIER_MID -> 0.85f
                else -> 0.45f
            }
            att.breathPhase = (att.breathPhase + amount / period) % 1f
            att.alpha = alphaMin + (alphaMax - alphaMin) *
                (0.5f - 0.5f * cos((att.breathPhase * 2f * Math.PI).toFloat()))

            val amplitude = JITTER_AMPLITUDE * att.alpha
            att.jitterX = (Random.nextFloat() * 2f - 1f) * amplitude
            att.jitterY = (Random.nextFloat() * 2f - 1f) * amplitude

            val alpha = att.alpha * (engine.viewport?.alphaMult ?: 1f)
            val facing = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f)
            val loc = Vector2f(ship.location.x + att.jitterX, ship.location.y + att.jitterY)
            for (lease in arrayOf(att.dark, att.light)) {
                lease.entity.setStateVanilla(loc, facing)
                lease.entity.materialData.colorAlpha = alpha
            }

            if (pool.charge >= TIER_MID && SPRITE_DARK in loadedSprites) {
                att.afterimageTimer += amount
                if (att.afterimageTimer >= AFTERIMAGE_INTERVAL_MID) {
                    att.afterimageTimer %= AFTERIMAGE_INTERVAL_MID
                    ASTDAfterimageEffect.spawn(
                        engine,
                        ASTDAfterimageEffect.Snapshot(
                            spritePath = SPRITE_DARK,
                            location = Vector2f(ship.location),
                            facing = ship.facing,
                            width = WING_WIDTH,
                            height = WING_HEIGHT,
                            color = Color.WHITE,
                            startAlpha = AFTERIMAGE_ALPHA,
                            duration = AFTERIMAGE_DURATION,
                            growth = 0f,
                        ),
                    )
                }
            } else {
                att.afterimageTimer = 0f
            }
        }
    }

    private class HudPlugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {

        override fun renderInUICoords(viewport: ViewportAPI) {
            val ship = engine.playerShip ?: return
            if (!ship.isAlive || ship.isHulk) return
            if (ship.hullSpec?.hullId != HULL_ID && ship.hullSpec?.baseHullId != HULL_ID) return
            val pool = ImaginaryCharge.peek(ship) ?: return

            // MagicUI 文字路径在现有投影上追加 glOrtho 且不改回矩阵模式与视口，必须以单位矩阵起步并整体隔离
            GL11.glPushAttrib(GL11.GL_TRANSFORM_BIT or GL11.GL_VIEWPORT_BIT or GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_CURRENT_BIT)
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glPushMatrix()
            GL11.glLoadIdentity()
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPushMatrix()
            GL11.glLoadIdentity()
            try {
                drawHudBar(ship, pool)
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW)
                GL11.glPopMatrix()
                GL11.glMatrixMode(GL11.GL_PROJECTION)
                GL11.glPopMatrix()
                GL11.glPopAttrib()
            }
        }

        private fun drawHudBar(ship: ShipAPI, pool: ImaginaryCharge) {
            val viewport = engine.viewport ?: return
            val fill = (pool.charge / ImaginaryWingsTuning.CHARGE_MAX).coerceIn(0f, 1f)
            val color = if (pool.charge > ImaginaryWingsTuning.EXCHANGE_THRESHOLD) HUD_CHARGE_READY else HUD_CHARGE_LOW
            MagicUI.drawHUDStatusBar(ship, fill, color, HUD_BORDER, 0f, null, null, true)

            if (MagicUI.TODRAW10 == null) {
                if (labelFontMissing.compareAndSet(false, true)) {
                    log.warn("[ASTD] MagicUI 小字号字体缺失（TODRAW10 为空），虚数之翼充能条的状态词无法绘制")
                }
                return
            }
            val percent = I18n.t(I18n.Categories.MOD, "hud.imaginary_wings.line.percent", "value" to pool.charge.roundToInt())
            val label = I18n[I18n.Categories.MOD, "hud.imaginary_wings.line.label"]
            val screenX = viewport.convertWorldXtoScreenX(ship.location.x).toInt().toFloat()
            val screenY = viewport.convertWorldYtoScreenY(ship.location.y).toInt().toFloat()
            val width = maxOf(MagicUI.getTextWidth(percent), MagicUI.getTextWidth(label)) *
                (LABEL_FONT_SIZE / MAGICUI_TEXT_FONT_SIZE)
            val x = screenX - width - LABEL_GAP
            val y = screenY + HUD_BAR_OFFSET_Y + LABEL_OFFSET_Y
            MagicUI.addStatusText(ship, percent, color, Vector2f(x, y + 6f))
            MagicUI.addStatusText(ship, label, color, Vector2f(x, y - 6f))
        }
    }
}
