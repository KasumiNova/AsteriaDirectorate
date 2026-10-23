package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet
import kotlin.math.cos

object Xc002GhostWingsEffect {

    private const val ENGINE_KEY = "astd_xc_002_ghost_wings"

    const val HULL_ID = "astd_xc_002"
    const val SPRITE_DARK = "graphics/ships/astd_xc_002_ghost_dark.png"
    const val SPRITE_LIGHT = "graphics/ships/astd_xc_002_ghost_light.png"

    private const val WING_WIDTH = 208f
    private const val WING_HEIGHT = 273f

    private const val BREATH_PERIOD_MAX = 3.0f
    private const val BREATH_PERIOD_MIN = 1.0f

    private const val BREATH_ALPHA_MIN = 0.2f

    private const val AFTERIMAGE_INTERVAL = 0.1f
    private const val AFTERIMAGE_ALPHA = 0.1f
    private const val AFTERIMAGE_DURATION = 0.5f

    private val ACTIVE_TINT = Color(190, 140, 255)

    private val log = AstdLog.logger

    private val loadedSprites = mutableSetOf<String>()

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
        if (engine.customData[ENGINE_KEY] != null) return
        try {
            val plugin = Plugin(engine)
            engine.addLayeredRenderingPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
        } catch (t: Throwable) {
            log.warn("[ASTD] 虚数之翼渲染器安装失败", t)
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
        var tint: Color = Color.WHITE,
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

            val maxSpeed = ship.mutableStats?.maxSpeed?.modifiedValue ?: 0f
            val speedRatio = if (maxSpeed > 1f) (ship.velocity.length() / maxSpeed).coerceIn(0f, 1f) else 0f
            val period = BREATH_PERIOD_MAX + (BREATH_PERIOD_MIN - BREATH_PERIOD_MAX) * speedRatio
            att.breathPhase = (att.breathPhase + amount / period) % 1f
            att.alpha = BREATH_ALPHA_MIN + (1f - BREATH_ALPHA_MIN) *
                (0.5f - 0.5f * cos((att.breathPhase * 2f * Math.PI).toFloat()))

            val systemActive = ship.system?.state == ShipSystemAPI.SystemState.ACTIVE
            att.tint = if (systemActive) ACTIVE_TINT else Color.WHITE

            att.afterimageTimer += amount
            if (att.afterimageTimer >= AFTERIMAGE_INTERVAL && SPRITE_DARK in loadedSprites) {
                att.afterimageTimer -= AFTERIMAGE_INTERVAL
                ASTDAfterimageEffect.spawn(
                    engine,
                    ASTDAfterimageEffect.Snapshot(
                        spritePath = SPRITE_DARK,
                        location = Vector2f(ship.location),
                        facing = ship.facing,
                        width = WING_WIDTH,
                        height = WING_HEIGHT,
                        color = att.tint,
                        startAlpha = AFTERIMAGE_ALPHA,
                        duration = AFTERIMAGE_DURATION,
                        growth = 0f,
                    ),
                )
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
                    // BoxUtil 实体快照与舰船渲染存在时相差，本体必须在 render 阶段直接采样舰船位置和朝向。
                    renderWing(SPRITE_DARK, ship, att.tint, alpha)
                    renderWing(SPRITE_LIGHT, ship, att.tint, alpha)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        private fun renderWing(spritePath: String, ship: ShipAPI, tint: Color, alpha: Float) {
            if (spritePath !in loadedSprites) return
            val sprite = Global.getSettings().getSprite(spritePath)
            sprite.setAdditiveBlend()
            sprite.color = Color(tint.red, tint.green, tint.blue, 255)
            sprite.alphaMult = alpha
            sprite.setSize(WING_WIDTH, WING_HEIGHT)
            sprite.angle = ship.facing - 90f
            sprite.renderAtCenter(ship.location.x, ship.location.y)
        }
    }
}
