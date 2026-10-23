package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.lens.system.GravStormTuning
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import org.boxutil.define.BoxDatabase
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.CurveEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GravStormConeIndicator private constructor(
    private val ship: ShipAPI,
    private val outline: CurveEntity,
) {
    private var baseAlphas = FloatArray(0)

    private var lastRange = -1f

    fun update(range: Float, alpha: Float) {
        if (outline.hasDelete()) return
        if (abs(range - lastRange) > RANGE_REBUILD_EPSILON) rebuildNodes(range)
        outline.setStateVanilla(
            Vector2f(ship.location),
            BoxUtilCombatVfx.normalizeFacingDeg(ship.facing),
        )
        val a = alpha.coerceIn(0f, 1f)
        val nodes = outline.nodes ?: return
        for (i in nodes.indices) {
            val nodeAlpha = baseAlphas.getOrElse(i) { 0f } * a
            nodes[i].setAlpha(nodeAlpha)
            nodes[i].setEmissiveAlpha(nodeAlpha)
        }
        outline.setNodeRefreshAllFromCurrentIndex()
        outline.submitNodes()
    }

    fun dispose() {
        if (!outline.hasDelete()) outline.delete()
    }

    private fun rebuildNodes(range: Float) {
        lastRange = range
        val points = ArrayList<Vector2f>(ARC_SEGMENTS + 3)
        val alphas = ArrayList<Float>(ARC_SEGMENTS + 3)
        points += Vector2f(0f, 0f)
        alphas += 0f
        for (i in 0..ARC_SEGMENTS) {
            val angleDeg = -GravStormTuning.CONE_HALF_ANGLE_DEG + CONE_SWEEP_DEG * i / ARC_SEGMENTS
            val rad = Math.toRadians(angleDeg.toDouble())
            points += Vector2f((cos(rad) * range).toFloat(), (sin(rad) * range).toFloat())
            alphas += 1f
        }
        points += Vector2f(0f, 0f)
        alphas += 0f

        val nodes = ArrayList<NodeData>(points.size)
        for (i in points.indices) {
            val node = NodeData(points[i])
            node.setWidth(OUTLINE_WIDTH)
            node.setColor(OUTLINE_COLOR)
            node.setEmissiveColor(OUTLINE_COLOR)
            nodes += node
        }
        outline.nodes = nodes
        outline.setNodeRefreshAllFromCurrentIndex()
        outline.submitNodes()
        baseAlphas = FloatArray(alphas.size) { alphas[it] * OUTLINE_ALPHA }
    }

    companion object {
        private val log = Global.getLogger(GravStormConeIndicator::class.java)

        private const val CONE_SWEEP_DEG = 2f * GravStormTuning.CONE_HALF_ANGLE_DEG

        private const val ARC_SEGMENTS = 20

        private const val OUTLINE_WIDTH = 5f
        private const val OUTLINE_ALPHA = 0.55f

        private const val RANGE_REBUILD_EPSILON = 0.5f

        private const val FULL_SECONDS = 1e7f

        private const val LOG_ONCE_CREATE = "astd_grav_storm_cone_log_create_once"
        private const val LOG_ONCE_INVALID = "astd_grav_storm_cone_log_invalid_once"
        private const val LOG_ONCE_ADD = "astd_grav_storm_cone_log_add_once"

        private val OUTLINE_COLOR = Color(170, 100, 255, 255)

        fun attach(engine: CombatEngineAPI, ship: ShipAPI): GravStormConeIndicator? {
            BoxUtilCombatVfx.ensureReady(engine)

            val entity = try {
                CurveEntity()
            } catch (t: Throwable) {
                warnOnce(engine, LOG_ONCE_CREATE, "[ASTD] 引力磁暴描边选框建实体失败（${t.javaClass.simpleName}），提示圈视觉缺席", t)
                return null
            }
            if (!entity.isValid) {
                warnOnce(engine, LOG_ONCE_INVALID, "[ASTD] 引力磁暴描边选框实体无效（GL43/curve shader 不可用，ship=${ship.id}），提示圈视觉缺席")
                entity.delete()
                return null
            }
            entity.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
            entity.setAdditiveBlend()
            entity.materialData.setDiffuse(BoxDatabase.BUtil_ONE)
            entity.materialData.setEmissive(BoxDatabase.BUtil_ONE)
            entity.materialData.alphaToEmissive = 0f
            entity.materialData.isColorToEmissive = 0f
            entity.materialData.glowPower = 0.3f
            entity.materialData.isIgnoreIllumination = true
            entity.setGlobalTimer(0f, FULL_SECONDS, 0f)

            val state = BoxUtilCombatVfx.addEntity(engine, entity)
            if (state != 0) {
                warnOnce(engine, LOG_ONCE_ADD, "[ASTD] 引力磁暴描边选框 addEntity 失败（state=$state，ship=${ship.id}），提示圈视觉缺席")
                entity.delete()
                return null
            }
            return GravStormConeIndicator(ship, entity)
        }

        private fun warnOnce(engine: CombatEngineAPI, key: String, message: String, t: Throwable? = null) {
            if (engine.customData[key] == true) return
            engine.customData[key] = true
            if (t == null) log.warn(message) else log.warn(message, t)
        }
    }
}
