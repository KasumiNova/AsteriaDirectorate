package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ASTDXc001HullModIds
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.sin

internal object ASTDAfterimageEffect {

    private const val ENGINE_KEY = ASTDXc001HullModIds.KEY_AFTERIMAGE_RENDERER

    private val log = AstdLog.logger

    private val warned = HashSet<String>()

    data class Snapshot(
        val spritePath: String,
        val location: Vector2f,
        val facing: Float,
        val width: Float,
        val height: Float,
        val color: Color,
        val startAlpha: Float,
        val duration: Float,
        val growth: Float,
        val glowSpritePath: String? = null,
        var age: Float = 0f,
    )

    fun ensureInstalled(engine: CombatEngineAPI) {
        getOrCreate(engine)
    }

    fun spawn(engine: CombatEngineAPI, snapshot: Snapshot) {
        val renderer = getOrCreate(engine)
        renderer.add(snapshot)
    }

    /** 以舰体贴图生成残影；舰船挂有发光层（*_bloom）时同步叠加发光层。location 为舰船逻辑中心。 */
    fun spawnShip(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        location: Vector2f,
        color: Color,
        startAlpha: Float,
        duration: Float,
        growth: Float = 0f,
    ) {
        val sprite = ship.spriteAPI
        val spritePath = ship.hullSpec?.spriteName
        if (sprite == null || spritePath == null) {
            warnOnce("ship:${ship.hullSpec?.hullId}", "[ASTD] 舰船残影缺少贴图信息（hull=${ship.hullSpec?.hullId}），本次残影缺席")
            return
        }
        val theta = Math.toRadians((ship.facing - 90f).toDouble())
        val offsetX = sprite.width / 2f - sprite.centerX
        val offsetY = sprite.height / 2f - sprite.centerY
        val center = Vector2f(
            location.x + (offsetX * cos(theta) - offsetY * sin(theta)).toFloat(),
            location.y + (offsetX * sin(theta) + offsetY * cos(theta)).toFloat(),
        )
        spawn(
            engine,
            Snapshot(
                spritePath = spritePath,
                location = center,
                facing = ship.facing,
                width = sprite.width,
                height = sprite.height,
                color = color,
                startAlpha = startAlpha,
                duration = duration,
                growth = growth,
                glowSpritePath = ShipGlowRenderer.overlayPathFor(ship),
            ),
        )
    }

    private fun warnOnce(site: String, message: String, t: Throwable? = null) {
        if (!warned.add(site)) return
        if (t != null) log.warn(message, t) else log.warn(message)
    }

    private fun getOrCreate(engine: CombatEngineAPI): Renderer {
        val existing = engine.customData[ENGINE_KEY] as? Renderer
        if (existing != null && !existing.isExpired) return existing

        val renderer = Renderer(engine)
        engine.addLayeredRenderingPlugin(renderer)
        engine.customData[ENGINE_KEY] = renderer
        return renderer
    }

    private class Renderer(private val engine: CombatEngineAPI) :
        BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) {
        private val snapshots = ArrayList<Snapshot>(64)
        private var expired = false

        fun add(snapshot: Snapshot) {
            if (expired) return
            snapshots += snapshot.copy(location = Vector2f(snapshot.location))
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> = EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun isExpired(): Boolean = expired

        override fun cleanup() {
            snapshots.clear()
            expired = true
        }

        override fun advance(amount: Float) {
            if (expired || engine.isPaused || amount <= 0f) return
            for (i in snapshots.size - 1 downTo 0) {
                val snapshot = snapshots[i]
                snapshot.age += amount
                if (snapshot.age >= snapshot.duration) {
                    snapshots.removeAt(i)
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired || layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER || snapshots.isEmpty()) return

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)

                for (snapshot in snapshots) {
                    val t = (snapshot.age / snapshot.duration).coerceIn(0f, 1f)
                    val fade = (1f - t) * (1f - t)
                    val alpha = (snapshot.startAlpha * fade).coerceIn(0f, 1f)
                    if (alpha <= 0.002f) continue

                    val scale = 1f + snapshot.growth * t
                    drawLayer(snapshot.spritePath, snapshot, alpha, scale)
                    snapshot.glowSpritePath?.let { drawLayer(it, snapshot, alpha, scale) }
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        private fun drawLayer(path: String, snapshot: Snapshot, alpha: Float, scale: Float) {
            val sprite = try {
                Global.getSettings().getSprite(path)
            } catch (t: Throwable) {
                warnOnce("sprite:$path", "[ASTD] 残影贴图读取失败 $path，该图层缺席", t)
                return
            }
            // getSprite 返回全局共享实例，绘制后必须还原尺寸，否则按增长缩放的残影会把原尺寸写回缓存
            val nativeWidth = sprite.width
            val nativeHeight = sprite.height
            sprite.setAdditiveBlend()
            sprite.alphaMult = alpha
            sprite.color = snapshot.color
            sprite.angle = snapshot.facing - 90f
            sprite.setSize(snapshot.width * scale, snapshot.height * scale)
            sprite.renderAtCenter(snapshot.location.x, snapshot.location.y)
            sprite.setSize(nativeWidth, nativeHeight)
        }
    }
}
