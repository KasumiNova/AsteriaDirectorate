package cn.kasuminova.astd.renderer.effect.system

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet

internal object ArcFlareAfterimageManager {

    private const val ENGINE_KEY = "astd_arc_flare_afterimage_renderer"

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
        var age: Float = 0f,
    )

    fun ensureInstalled(engine: CombatEngineAPI) {
        getOrCreate(engine)
    }

    fun spawn(engine: CombatEngineAPI, snapshot: Snapshot) {
        val renderer = getOrCreate(engine)
        renderer.add(snapshot)
    }

    private fun getOrCreate(engine: CombatEngineAPI): Renderer {
        val existing = engine.customData[ENGINE_KEY] as? Renderer
        if (existing != null && !existing.isExpired) return existing

        val renderer = Renderer(engine)
        engine.addLayeredRenderingPlugin(renderer)
        engine.customData[ENGINE_KEY] = renderer
        return renderer
    }

    private class Renderer(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) {
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
                    val sprite = try {
                        Global.getSettings().getSprite(snapshot.spritePath)
                    } catch (_: Throwable) {
                        null
                    } ?: continue

                    val t = (snapshot.age / snapshot.duration).coerceIn(0f, 1f)
                    val fade = (1f - t) * (1f - t)
                    val alpha = (snapshot.startAlpha * fade).coerceIn(0f, 1f)
                    if (alpha <= 0.002f) continue

                    val scale = 1f + snapshot.growth * t
                    sprite.setAdditiveBlend()
                    sprite.alphaMult = alpha
                    sprite.color = Color(snapshot.color.red, snapshot.color.green, snapshot.color.blue, (255f * alpha).toInt().coerceIn(0, 255))
                    sprite.angle = snapshot.facing - 90f
                    sprite.setSize(snapshot.width * scale, snapshot.height * scale)
                    sprite.renderAtCenter(snapshot.location.x, snapshot.location.y)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }
    }
}