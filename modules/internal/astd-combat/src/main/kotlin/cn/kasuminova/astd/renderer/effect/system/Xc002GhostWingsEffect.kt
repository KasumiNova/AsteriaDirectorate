package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import org.magiclib.util.MagicUI
import java.awt.Color
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 星翼-级虚数光翼渲染：按虚数充能档位驱动呼吸频率与残影密度，贴图始终保持原色。
 * 光翼 alpha 越高抖动幅度越大。玩家舰的充能条由独立的每帧插件在 UI 坐标阶段绘制。
 */
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

    private const val BREATH_PERIOD_LOW = 3.0f
    private const val BREATH_PERIOD_MID = 2.0f
    private const val BREATH_PERIOD_HIGH = 1.2f

    private const val BREATH_ALPHA_MIN = 0.15f

    private const val JITTER_AMPLITUDE = 2.5f

    /** drawHUDStatusBar 的 HUD 纵向偏移：所有 HUD 状态条都从舰船屏幕坐标上方 16px 起。 */
    private const val HUD_BAR_OFFSET_Y = 16f

    /** 状态词与充能条左缘的水平间距。 */
    private const val LABEL_GAP = 5f

    /** 状态词字号，与 MagicUI 状态文本的 10 号字体一致。 */
    private const val LABEL_FONT_SIZE = 10f

    /** MagicUI.getTextWidth 以 14 号字体量取，换算到状态词字号需按比例缩放。 */
    private const val MAGICUI_TEXT_FONT_SIZE = 14f

    /** 状态词纵向微调：10 号字在 5px 高条带上做视觉居中（条顶距锚点 15px，字高 10px）。 */
    private const val LABEL_OFFSET_Y = -3.5f

    private const val AFTERIMAGE_INTERVAL_MID = 0.08f
    private const val AFTERIMAGE_INTERVAL_OVERDRIVE = 0.04f
    private const val AFTERIMAGE_ALPHA = 0.12f
    private const val AFTERIMAGE_DURATION = 0.4f
    private const val AFTERIMAGE_DURATION_OVERDRIVE = 0.5f

    private val HUD_CHARGE_LOW = Color(120, 170, 255)
    private val HUD_CHARGE_READY = Color(150, 205, 255)
    private val HUD_OVERDRIVE = Color(110, 225, 255)
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
                engine.addLayeredRenderingPlugin(plugin)
                engine.customData[ENGINE_KEY] = plugin
            } catch (t: Throwable) {
                log.warn("[ASTD] 虚数之翼渲染器安装失败", t)
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
        var breathPhase: Float = 0f,
        var afterimageTimer: Float = 0f,
        var alpha: Float = 0f,
        var jitterX: Float = 0f,
        var jitterY: Float = 0f,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(
        CombatEngineLayers.ABOVE_SHIPS_LAYER,
    ) {

        private val attachments = LinkedHashMap<Int, Attachment>()
        private var firstRenderLogged = false

        fun track(ship: ShipAPI) {
            val key = System.identityHashCode(ship)
            if (attachments.containsKey(key)) return
            if (ship.isHulk) return
            attachments[key] = Attachment(ship)
            log.info("[ASTD] 虚数之翼光翼挂载已创建：ship=${ship.hullSpec?.hullId}")
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> =
            EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun advance(amount: Float) {
            if (attachments.isEmpty()) return
            if (engine.isPaused) return

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val att = it.next().value
                val ship = att.ship
                if (ship.isHulk || !engine.isEntityInPlay(ship)) {
                    it.remove()
                    continue
                }
                updateAttachment(att, amount)
            }
        }

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship
            val pool = ImaginaryCharge.of(ship)
            val chargeFrac = (pool.charge / ImaginaryWingsTuning.CHARGE_MAX).coerceIn(0f, 1f)
            val overdrive = pool.inOverdrive

            val period = when {
                overdrive || pool.charge >= TIER_HIGH -> BREATH_PERIOD_HIGH
                pool.charge >= TIER_MID -> BREATH_PERIOD_MID
                else -> BREATH_PERIOD_LOW
            }
            att.breathPhase = (att.breathPhase + amount / period) % 1f
            val alphaCap = 0.5f + 0.5f * chargeFrac
            att.alpha = BREATH_ALPHA_MIN + (alphaCap - BREATH_ALPHA_MIN) *
                (0.5f - 0.5f * cos((att.breathPhase * 2f * Math.PI).toFloat()))

            val amplitude = JITTER_AMPLITUDE * att.alpha
            att.jitterX = (Random.nextFloat() * 2f - 1f) * amplitude
            att.jitterY = (Random.nextFloat() * 2f - 1f) * amplitude

            val afterimageInterval = if (overdrive) AFTERIMAGE_INTERVAL_OVERDRIVE else AFTERIMAGE_INTERVAL_MID
            val afterimageDuration = if (overdrive) AFTERIMAGE_DURATION_OVERDRIVE else AFTERIMAGE_DURATION
            if (pool.charge >= TIER_MID && SPRITE_DARK in loadedSprites) {
                att.afterimageTimer += amount
                if (att.afterimageTimer >= afterimageInterval) {
                    att.afterimageTimer = 0f
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
                            duration = afterimageDuration,
                            growth = 0f,
                        ),
                    )
                }
            } else {
                att.afterimageTimer = 0f
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_LAYER) return
            if (attachments.isEmpty()) return
            if (!firstRenderLogged) {
                firstRenderLogged = true
                log.info("[ASTD] 虚数之翼光翼首次渲染，attachments=${attachments.size}")
            }

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                for ((_, att) in attachments) {
                    val ship = att.ship
                    if (ship.isHulk || !engine.isEntityInPlay(ship)) continue
                    val alpha = att.alpha * viewport.alphaMult
                    if (alpha <= 0.002f) continue
                    renderWing(SPRITE_DARK, att, alpha)
                    renderWing(SPRITE_LIGHT, att, alpha)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        private fun renderWing(spritePath: String, att: Attachment, alpha: Float) {
            if (spritePath !in loadedSprites) return
            val ship = att.ship
            val sprite = Global.getSettings().getSprite(spritePath)
            sprite.setAdditiveBlend()
            sprite.color = Color.WHITE
            sprite.alphaMult = alpha
            sprite.setSize(WING_WIDTH, WING_HEIGHT)
            sprite.angle = ship.facing - 90f
            sprite.renderAtCenter(ship.location.x + att.jitterX, ship.location.y + att.jitterY)
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
            val overdrive = pool.inOverdrive
            val fill = if (overdrive) {
                (pool.overdriveRemaining / ImaginaryWingsTuning.OVERDRIVE_SECONDS).coerceIn(0f, 1f)
            } else {
                (pool.charge / ImaginaryWingsTuning.CHARGE_MAX).coerceIn(0f, 1f)
            }
            val color = when {
                overdrive -> HUD_OVERDRIVE
                pool.charge < ImaginaryWingsTuning.OVERDRAFT_THRESHOLD -> HUD_CHARGE_LOW
                else -> HUD_CHARGE_READY
            }
            MagicUI.drawHUDStatusBar(ship, fill, color, HUD_BORDER, 0f, null, null, true)
            // 状态词独立绘制在条下方，与 drawHUDStatusBar 使用同一套屏幕坐标与 HUD 偏移
            if (MagicUI.TODRAW10 == null) {
                if (labelFontMissing.compareAndSet(false, true)) {
                    log.warn("[ASTD] MagicUI 小字号字体缺失（TODRAW10 为空），虚数之翼充能条的状态词无法绘制")
                }
                return
            }
            val label = if (overdrive) {
                I18n.t(
                    I18n.Categories.MOD, "hud.imaginary_wings.line.overdrive",
                    "value" to (pool.overdriveRemaining * 10f).roundToInt() / 10f,
                )
            } else {
                I18n.t(
                    I18n.Categories.MOD, "hud.imaginary_wings.line.charge",
                    "value" to pool.charge.roundToInt(),
                )
            }
            // 状态词贴在充能条左缘：MagicUI 的状态条与状态文本都先加同一份 HUD 偏移、再整体乘 UI_SCALING，
            // 因此按未缩放单位算好条左缘后直接减文字宽度即可，HUD 偏移与缩放都会自然抵消
            val screenX = viewport.convertWorldXtoScreenX(ship.location.x).toInt().toFloat()
            val screenY = viewport.convertWorldYtoScreenY(ship.location.y).toInt().toFloat()
            val textWidth = MagicUI.getTextWidth(label) * (LABEL_FONT_SIZE / MAGICUI_TEXT_FONT_SIZE)
            val anchor = Vector2f(
                screenX - textWidth - LABEL_GAP,
                screenY + HUD_BAR_OFFSET_Y + LABEL_OFFSET_Y,
            )
            MagicUI.addStatusText(ship, label, color, anchor)
        }
    }
}
