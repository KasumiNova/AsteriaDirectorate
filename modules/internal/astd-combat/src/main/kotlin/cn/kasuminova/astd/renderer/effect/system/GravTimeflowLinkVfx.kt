package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.lens.system.GravTimeflowTuning
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.define.BoxDatabase
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.CurveEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 引力时流干涉器：自身与目标舰之间的多层时流弧线（BoxUtil [CurveEntity] 线条实体）。
 *
 * 结构：[ARC_STRANDS] 条弧股，每股拆 BODY（宽而淡的主体）+ GLOW（窄而亮的高光）两层；
 * 节点定义在局部空间（+X 为源舰 → 目标舰方向），带横向扰动形成电弧感，
 * 亮度脉冲沿弧向目标舰流动（[FLOW_SPEED]）。节点朝向进入 BoxUtil 变换前过
 * [BoxUtilCombatVfx.normalizeFacingDeg]。层 [CombatEngineLayers.BELOW_SHIPS_LAYER]——
 * 弧线被舰船盖住，不盖在舰船上（规格：purple/10-unique.md §1）。
 *
 * 生命周期：激活首帧 [attach]，持续期间每帧 [update] 跟随双舰位置重建节点并按
 * effectLevel 驱动整体 alpha（OUT 窗口自然淡出），持续结束/目标消亡/离场即 [dispose]
 * （快速淡出后由实体定时器自收）；低频事件级实体（每次激活 1 组，冷却 30s），
 * 不走实例池（对照 boxutil 实体池化规范的低频例外口径）。
 */
class GravTimeflowLinkVfx private constructor(
    private val source: ShipAPI,
    private val target: ShipAPI,
    private val arcs: List<ArcLayer>,
) {

    /** 单条弧股的双层实体与相位参数。 */
    private class ArcLayer(
        val body: CurveEntity,
        val glow: CurveEntity,
        /** 弧股相位偏移（横向扰动与流动脉冲错峰）。 */
        val phase: Float,
        /** 弧股随机种子（高频抖动错位）。 */
        val seed: Float,
    )

    private var time = 0f
    private var dead = false

    /**
     * 每帧驱动：跟随双舰位置重建全部弧股节点（局部 +X 指向目标），
     * 横向扰动 + 向目标流动的亮度脉冲；[effectLevel] 作为整体 alpha 包络
     * （IN 渐入 / ACTIVE 满值 / OUT 渐出）。
     */
    fun update(elapsed: Float, effectLevel: Float) {
        if (dead) return
        time += elapsed

        val from = source.location
        val to = target.location
        val dist = Misc.getDistance(from, to)
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(Misc.getAngleInDegrees(from, to))
        val amp = (dist * LATERAL_AMP_DIST_FRACTION).coerceAtMost(LATERAL_AMP_MAX)
        val alphaEnv = effectLevel.coerceIn(0f, 1f)

        for (arc in arcs) {
            updateArc(arc.body, facing, dist, amp, alphaEnv, arc.phase, arc.seed, ArcStyle.BODY)
            updateArc(arc.glow, facing, dist, amp, alphaEnv, arc.phase, arc.seed, ArcStyle.GLOW)
        }
    }

    /** 收口：全部弧股快速淡出（实体定时器自收，dispose 幂等）。 */
    fun dispose() {
        if (dead) return
        dead = true
        for (arc in arcs) {
            if (!arc.body.hasDelete()) arc.body.setGlobalTimer(0f, 0f, FADE_OUT_SECONDS)
            if (!arc.glow.hasDelete()) arc.glow.setGlobalTimer(0f, 0f, FADE_OUT_SECONDS)
        }
    }

    private enum class ArcStyle { BODY, GLOW }

    /** 单条弧股逐帧重建：位置/朝向、节点横向扰动、流向目标的亮度脉冲。 */
    private fun updateArc(
        entity: CurveEntity,
        facing: Float,
        dist: Float,
        amp: Float,
        alphaEnv: Float,
        phase: Float,
        seed: Float,
        style: ArcStyle,
    ) {
        if (entity.hasDelete()) return
        entity.setStateVanilla(Vector2f(source.location), facing)

        val nodes = entity.nodes ?: return
        val lastIdx = nodes.size - 1
        if (lastIdx <= 0) return

        val flowHead = frac(time * FLOW_SPEED + phase)
        for (i in 0..lastIdx) {
            val t = i.toFloat() / lastIdx.toFloat()
            val envelope = sin(PI.toFloat() * t)
            val sway = sin(2f * PI.toFloat() * (t * SWAY_WAVES + time * SWAY_SPEED + phase)) * amp
            val chatter = sin(t * 43.7f + time * CHATTER_SPEED + seed * 17.3f) * amp * CHATTER_AMP_FRACTION
            val lateral = envelope * (sway + chatter)

            // 亮度脉冲沿 t 向目标（t=1）流动；环绕距离保证脉冲从末端绕回起点时不跳变
            var pulseDist = abs(t - flowHead)
            if (pulseDist > 0.5f) pulseDist = 1f - pulseDist
            val pulse = (1f - pulseDist / PULSE_WIDTH).coerceIn(0f, 1f)

            // 弧两端收细收敛，中段满宽
            val tipEnv = (t / TIP_FRACTION).coerceIn(0f, 1f) * ((1f - t) / TIP_FRACTION).coerceIn(0f, 1f)
            val node = nodes[i]
            node.setLocation(t * dist, lateral)
            node.width = (if (style == ArcStyle.BODY) BODY_WIDTH else GLOW_WIDTH) * (0.35f + 0.65f * tipEnv)

            val brightness = 0.45f + 0.55f * pulse
            val baseAlpha = (if (style == ArcStyle.BODY) BODY_ALPHA else GLOW_ALPHA) * alphaEnv * tipEnv
            node.setColor(lerpColor(COLOR_DIM, COLOR_BRIGHT, brightness, baseAlpha))
            val emissiveAlpha = baseAlpha * (if (style == ArcStyle.GLOW) 1f else EMISSIVE_ALPHA_BODY_FRACTION)
            node.setEmissiveColor(lerpColor(COLOR_EMISSIVE_DIM, COLOR_EMISSIVE_BRIGHT, brightness, emissiveAlpha))
        }
        entity.setNodeRefreshAllFromCurrentIndex()
        entity.submitNodes()
    }

    companion object {
        private val log = Global.getLogger(GravTimeflowLinkVfx::class.java)

        /** 弧股条数（每股 BODY + GLOW 两层）。 */
        private const val ARC_STRANDS = 3

        /** 每股节点数。 */
        private const val NODE_COUNT = 24

        /** 常驻实体时长（秒）：生命周期由系统脚本显式驱动 dispose，不自然到期。 */
        private const val FULL_SECONDS = 1e7f

        /** dispose 快速淡出（秒）。 */
        private const val FADE_OUT_SECONDS = 0.4f

        /** 横向扰动：主波幅上限（su）与随链路距离的比例。 */
        private const val LATERAL_AMP_MAX = 45f
        private const val LATERAL_AMP_DIST_FRACTION = 0.12f

        /** 横向扰动：主波数（沿弧）与推进速度（周/秒）、高频抖动幅度占比与速度（弧度/秒）。 */
        private const val SWAY_WAVES = 1.5f
        private const val SWAY_SPEED = 0.9f
        private const val CHATTER_AMP_FRACTION = 0.35f
        private const val CHATTER_SPEED = 11f

        /** 亮度脉冲流向目标的速度（全弧/秒）与脉冲宽度（占弧长比例）。 */
        private const val FLOW_SPEED = 0.8f
        private const val PULSE_WIDTH = 0.3f

        /** 弧两端收敛区（占弧长比例）。 */
        private const val TIP_FRACTION = 0.12f

        /** 线宽（su）与基准 alpha。 */
        private const val BODY_WIDTH = 7f
        private const val GLOW_WIDTH = 3f
        private const val BODY_ALPHA = 0.30f
        private const val GLOW_ALPHA = 0.65f
        private const val EMISSIVE_ALPHA_BODY_FRACTION = 0.5f

        /** 配色（透镜协议紫，对照 GravStormSystemStats 电弧/jitter 用色）。 */
        private val COLOR_DIM = Color(120, 70, 200)
        private val COLOR_BRIGHT = Color(205, 150, 255)
        private val COLOR_EMISSIVE_DIM = Color(150, 100, 230)
        private val COLOR_EMISSIVE_BRIGHT = Color(240, 220, 255)

        /** 一次性 WARN 闩键（engine.customData，随战斗清理）。 */
        private const val LOG_ONCE_CREATE = "astd_grav_timeflow_link_log_create_once"
        private const val LOG_ONCE_INVALID = "astd_grav_timeflow_link_log_invalid_once"
        private const val LOG_ONCE_ADD = "astd_grav_timeflow_link_log_add_once"

        /** 创建并注册全部弧股实体；实体无效或注册失败记 WARN（每场战斗每类一次）并返回 null，本次弧线视觉缺席但系统机制照常。 */
        fun attach(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI): GravTimeflowLinkVfx? {
            BoxUtilCombatVfx.ensureReady(engine)

            val arcs = ArrayList<ArcLayer>(ARC_STRANDS)
            for (i in 0 until ARC_STRANDS) {
                val body = createArc(engine, additive = false, glowPower = 0.25f)
                val glow = createArc(engine, additive = true, glowPower = 0.6f)
                if (body == null || glow == null) {
                    body?.delete()
                    glow?.delete()
                    for (arc in arcs) {
                        arc.body.delete()
                        arc.glow.delete()
                    }
                    return null
                }
                arcs += ArcLayer(body, glow, phase = i.toFloat() / ARC_STRANDS, seed = i * 7.13f)
            }
            return GravTimeflowLinkVfx(source, target, arcs)
        }

        /** 单条弧实体：纯色线条（BUtil_ONE 白底贴图，颜色全部由节点色驱动，对照 GravStormConeIndicator 口径）。 */
        private fun createArc(engine: CombatEngineAPI, additive: Boolean, glowPower: Float): CurveEntity? {
            val entity = try {
                CurveEntity()
            } catch (t: Throwable) {
                warnOnce(engine, LOG_ONCE_CREATE, "[ASTD] ${GravTimeflowTuning.SYSTEM_ID} 时流弧线建实体失败（${t.javaClass.simpleName}），弧线视觉缺席", t)
                return null
            }
            if (!entity.isValid) {
                warnOnce(engine, LOG_ONCE_INVALID, "[ASTD] ${GravTimeflowTuning.SYSTEM_ID} 时流弧线实体无效（GL43/curve shader 不可用），弧线视觉缺席")
                entity.delete()
                return null
            }
            entity.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
            if (additive) entity.setAdditiveBlend() else entity.setNormalBlend()
            entity.materialData.setDiffuse(BoxDatabase.BUtil_ONE)
            entity.materialData.setEmissive(BoxDatabase.BUtil_ONE)
            entity.materialData.alphaToEmissive = 0f
            entity.materialData.isColorToEmissive = 0f
            entity.materialData.glowPower = glowPower
            entity.materialData.isIgnoreIllumination = true
            entity.setGlobalTimer(0f, FULL_SECONDS, 0f)

            val nodes = ArrayList<NodeData>(NODE_COUNT)
            repeat(NODE_COUNT) {
                val node = NodeData(Vector2f(0f, 0f))
                node.setWidth(BODY_WIDTH)
                node.setColor(COLOR_DIM)
                node.setEmissiveColor(COLOR_EMISSIVE_DIM)
                nodes += node
            }
            entity.nodes = nodes
            entity.setNodeRefreshAllFromCurrentIndex()
            entity.submitNodes()

            val state = BoxUtilCombatVfx.addEntity(engine, entity)
            if (state != 0) {
                warnOnce(engine, LOG_ONCE_ADD, "[ASTD] ${GravTimeflowTuning.SYSTEM_ID} 时流弧线 addEntity 失败（state=$state），弧线视觉缺席")
                entity.delete()
                return null
            }
            return entity
        }

        private fun frac(x: Float): Float = x - x.toInt() + if (x < 0f) 1f else 0f

        private fun lerpColor(a: Color, b: Color, t: Float, alpha: Float): Color {
            val cl = t.coerceIn(0f, 1f)
            return Color(
                (a.red + (b.red - a.red) * cl).toInt(),
                (a.green + (b.green - a.green) * cl).toInt(),
                (a.blue + (b.blue - a.blue) * cl).toInt(),
                (alpha.coerceIn(0f, 1f) * 255f).toInt(),
            )
        }

        /** 一次性 WARN（engine.customData 闩，每场战斗每类失败一次，防逐帧刷屏；对齐 BoxUtilCombatVfx 先例口径）。 */
        private fun warnOnce(engine: CombatEngineAPI, key: String, message: String, t: Throwable? = null) {
            if (engine.customData[key] == true) return
            engine.customData[key] = true
            if (t == null) log.warn(message) else log.warn(message, t)
        }
    }
}
