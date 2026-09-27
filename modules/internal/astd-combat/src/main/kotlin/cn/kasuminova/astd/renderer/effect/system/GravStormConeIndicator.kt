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

/**
 * 引力磁暴发生器：充能期间的锥状射程描边选框（BoxUtil [CurveEntity] 线条实体，无填充）。
 *
 * 几何：射程半径处 ±30° 圆弧描边（[ARC_SEGMENTS] 段折线逼近）+ 两条径向侧边线，
 * 侧边线 alpha 沿长度渐变（舰心端 0 → 外缘满值），整体 alpha 由调用方按充能进度驱动
 * （显著低于旧填充扇形贴图口径）。节点定义在局部空间（+X 为舰船前方，facing 0 = 正东），
 * 进入 BoxUtil 变换前 facing 过 [BoxUtilCombatVfx.normalizeFacingDeg]。
 *
 * 生命周期：充能首帧 [attach]，充能中每帧 [update] 跟随舰位/朝向、射程变化时重建节点
 * 并按充能进度调 alpha，充能结束（释放/打断/系统取消）即 [dispose]；
 * 低频事件级实体（每次激活 1 个，冷却 24s），不走实例池（对照 boxutil 实体池化规范的低频例外口径）。
 */
class GravStormConeIndicator private constructor(
    private val ship: ShipAPI,
    private val outline: CurveEntity,
) {
    /** 节点基准 alpha（描边满值 1、舰心端点 0）；update 时乘以充能进度 alpha。 */
    private var baseAlphas = FloatArray(0)

    /** 上一帧重建节点时的射程（range 只在 systemRangeBonus 变化时才变，避免每帧重建）。 */
    private var lastRange = -1f

    /** 每帧跟随：位置/朝向；射程变化时重建节点；alpha 按充能进度缩放。 */
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

    /** 充能结束收口：删除实体（下次充能重新 attach）。 */
    fun dispose() {
        if (!outline.hasDelete()) outline.delete()
    }

    /** 按当前射程重建描边节点：舰心 → -30° 外缘 → 圆弧至 +30° 外缘 → 舰心。 */
    private fun rebuildNodes(range: Float) {
        lastRange = range
        val points = ArrayList<Vector2f>(ARC_SEGMENTS + 3)
        val alphas = ArrayList<Float>(ARC_SEGMENTS + 3)
        // 侧边线 A：舰心（alpha 0）→ -30° 外缘（满值），沿长度线性渐变
        points += Vector2f(0f, 0f)
        alphas += 0f
        for (i in 0..ARC_SEGMENTS) {
            val angleDeg = -GravStormTuning.CONE_HALF_ANGLE_DEG + CONE_SWEEP_DEG * i / ARC_SEGMENTS
            val rad = Math.toRadians(angleDeg.toDouble())
            points += Vector2f((cos(rad) * range).toFloat(), (sin(rad) * range).toFloat())
            alphas += 1f
        }
        // 侧边线 B：+30° 外缘（满值）→ 舰心（alpha 0）
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

        /** 锥状扇形总张角（度）：半角口径唯一来源 [GravStormTuning.CONE_HALF_ANGLE_DEG]（±30° = 60° 锥）。 */
        private const val CONE_SWEEP_DEG = 2f * GravStormTuning.CONE_HALF_ANGLE_DEG

        /** 圆弧折线段数（3° 一段）。 */
        private const val ARC_SEGMENTS = 20

        /** 描边线宽（su）与描边满值 alpha（整体显著低于旧填充贴图口径）。 */
        private const val OUTLINE_WIDTH = 5f
        private const val OUTLINE_ALPHA = 0.55f

        /** 射程重建阈值（su）：变化超过本值才重建节点。 */
        private const val RANGE_REBUILD_EPSILON = 0.5f

        /** 常驻实体时长（秒）：生命周期由系统脚本显式驱动 dispose，不自然到期。 */
        private const val FULL_SECONDS = 1e7f

        /** 一次性 WARN 闩键（engine.customData，随战斗清理）。 */
        private const val LOG_ONCE_CREATE = "astd_grav_storm_cone_log_create_once"
        private const val LOG_ONCE_INVALID = "astd_grav_storm_cone_log_invalid_once"
        private const val LOG_ONCE_ADD = "astd_grav_storm_cone_log_add_once"

        /** 描边配色（透镜协议紫）。 */
        private val OUTLINE_COLOR = Color(170, 100, 255, 255)

        /**
         * 创建并注册描边选框实体；建实体/注册失败或实体无效（CurveEntity 依赖 GL43 与
         * curve shader，构造时即定论）记 WARN（每场战斗每类一次）并返回 null，
         * 本次提示圈缺席但系统机制照常。
         */
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
            // 纯色线条：BUtil_ONE 白底贴图，颜色全部由节点色驱动（对照 AttachedBeamSpriteRingRenderer 口径）
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

        /** 一次性 WARN（engine.customData 闩，每场战斗每类失败一次，防逐帧刷屏；对齐 BoxUtilCombatVfx 先例口径）。 */
        private fun warnOnce(engine: CombatEngineAPI, key: String, message: String, t: Throwable? = null) {
            if (engine.customData[key] == true) return
            engine.customData[key] = true
            if (t == null) log.warn(message) else log.warn(message, t)
        }
    }
}
