package cn.kasuminova.astd.renderer.effect.hullmods

import cn.kasuminova.astd.combat.shipsystems.ASTDNegentropyEdgeState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil

/** 断熵弧刃舰体下方充能条渲染器；世界坐标固定朝向，不随舰船朝向旋转。 */
object ASTDNegentropyChargeBarRenderer {
    private const val KEY = "astd_negentropy_charge_bar_renderer"

    fun ensure(engine: CombatEngineAPI) {
        if (engine.customData[KEY] is Renderer) return
        val renderer = Renderer(engine)
        engine.addLayeredRenderingPlugin(renderer)
        engine.customData[KEY] = renderer
    }

    private class Renderer(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_SHIPS_LAYER) {
        private var expired = false

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> = EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun isExpired(): Boolean = expired

        override fun cleanup() {
            expired = true
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired || layer != CombatEngineLayers.ABOVE_SHIPS_LAYER) return
            val ships = try { engine.ships } catch (_: Throwable) { null } ?: return
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_LINE_BIT)
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glDisable(GL11.GL_CULL_FACE)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                GL11.glLineWidth(2.2f)
                for (ship in ships) {
                    if (!ASTDNegentropyEdgeState.isNegentropyEdge(ship) || ship.isHulk || !ship.isAlive) continue
                    renderFor(ship)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        private fun renderFor(ship: ShipAPI) {
            val charge = ASTDNegentropyEdgeState.getDisplayCharge(ship)
            if (charge <= 0.005f && ASTDNegentropyEdgeState.getCharge(ship) <= 0.005f) return
            val burst = ASTDNegentropyEdgeState.getCollapseWindowLevel(ship) > 0f
            val base = if (burst) Color(255, 142, 54, 220) else Color(92, 210, 255, 210)
            val core = if (burst) Color(255, 235, 180, 235) else Color(235, 252, 255, 230)
            val cx = ship.location.x
            val cy = ship.location.y - ship.collisionRadius - 36.8f
            val radius = (ship.collisionRadius * 0.54f).coerceIn(39f, 78f)
            val sweep = 122.5f
            val start = 270f - sweep * 0.5f
            drawArc(cx, cy, radius, start, sweep, 44, 8.8f, Color(base.red, base.green, base.blue, 48))
            drawArc(cx, cy, radius, start, sweep * charge, 44, 8.8f, base)
            drawArc(cx, cy, radius - 4.0f, start, sweep * charge, 44, 1.8f, core)
        }

        private fun drawArc(cx: Float, cy: Float, radius: Float, startDeg: Float, sweepDeg: Float, stepsIn: Int, width: Float, color: Color) {
            if (sweepDeg <= 0f) return
            val steps = maxOf(stepsIn, ceil(sweepDeg / 2.5f).toInt(), 2)
            GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
            val halfWidth = width * 0.5f
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP)
            for (i in 0..steps) {
                val a = Math.toRadians((startDeg + sweepDeg * i / steps).toDouble())
                val x = cos(a).toFloat()
                val y = sin(a).toFloat()
                GL11.glVertex2f(cx + x * (radius + halfWidth), cy + y * (radius + halfWidth))
                GL11.glVertex2f(cx + x * (radius - halfWidth), cy + y * (radius - halfWidth))
            }
            GL11.glEnd()
            drawCap(cx, cy, radius, startDeg, halfWidth, color)
            drawCap(cx, cy, radius, startDeg + sweepDeg, halfWidth, color)
        }

        private fun drawCap(cx: Float, cy: Float, radius: Float, angleDeg: Float, capRadius: Float, color: Color) {
            val a = Math.toRadians(angleDeg.toDouble())
            val capCx = cx + cos(a).toFloat() * radius
            val capCy = cy + sin(a).toFloat() * radius
            GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
            GL11.glBegin(GL11.GL_TRIANGLE_FAN)
            GL11.glVertex2f(capCx, capCy)
            for (i in 0..14) {
                val theta = Math.PI * 2.0 * i / 14.0
                GL11.glVertex2f(capCx + cos(theta).toFloat() * capRadius, capCy + sin(theta).toFloat() * capRadius)
            }
            GL11.glEnd()
        }
    }
}
