package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 锥面冲击特效的「扩张弧」组件（计划 00-锥面冲击特效重做计划 §10.9 v4.3）：四道朝前的椭圆弧段，
 * 每道 = 一枚 [NODE_COUNT] 节点 TrailEntity 曲梁（beamcoreb/beamfringeb 贴图 + emissive 进原生
 * bloom 管线），取代 v2.2 的 OglEllipseRingRenderer GL_LINE_STRIP 硬边线弧——用户需求第二条
 * 「弧环需要真正的柔边」。
 *
 * 参数铁律（v4 重做硬约束）：**以 40ce3a5 现状代码为准逐值平移，v3 历史（6c2d56c）仅作结构参考，
 * 其观感参数一律不继承**。逐值平移自 v2.2 的项：
 * - 几何模型：轴位 0.3/0.5/0.7/0.9L、aSide = 轴位 × tan(halfAngle) × 0.85、bAlong = aSide × 0.5、
 *   弧心 90° 朝前、扫掠 130°、错峰 t=+0.03/0.07/0.11/0.15、单道存续 0.22/0.20/0.18/0.16s；
 * - 扩张模型：a/b 同额**绝对外扩** expandSpeed×t（260/287/313/340 su/s）——逐帧重写局部节点实现，
 *   而非 v3 的 scale 0.25→1.0 比例 ramp（比例 ramp 还会把梁宽随矩阵一起放大，违背 v2.2 恒线宽）；
 * - 宽度：v2.2 线宽 12/11/10/10px ÷ [VIEW_MULT_PX_PER_SU]（pl 场景遥测 viewMult≈1.5637 px/su）
 *   ≈ 7.7/7.0/6.4/6.4su 恒宽（内外道差档温和，**不继承** v3 的 1.0/0.85/0.70/0.55 激进分档）；
 * - 亮度：fringeColor alpha 120/95/75/55 逐道递减；时间包络 = v2.2 的 alpha×(1−t) 线性全程淡出
 *   （globalTimer fadeIn=0/full=0/fadeOut=duration，BoxUtil 淡出相恰为线性，语义逐字等价）；
 * - 逐顶点 alpha 包络：v2.2 arcAlphaEnvelope(t, 0.65)（smoothstep 双边、峰值偏前 0.65、两端归零
 *   治硬边收尾）用 fill 羽化逐值复刻——fillStart/EndAlpha=0、fillStartFactor=1−0.65=0.35、
 *   fillEndFactor=0.65 时着色器 fill 模型与原包络**逐点恒等**（smoothstep(1−v)=1−smoothstep(v)
 *   的补恒等式，乘积另一支在峰两侧恰恒为 1；等价性由 ConeArcComponentTest 网格断言）。
 * - v2.2 的双层线圈读感（外层 2× 线宽 ×0.35 alpha + 内层 1× 全 alpha「模拟轻微虚化」）由
 *   core/fringe 双贴图混合结构承接：beamcoreb 亮核 ≈ 内层线、beamfringeb 宽淡辉光 ≈ 外层线。
 *
 * emissive 增益 [ARC_EMISSIVE_GAIN]=1.0 为目检调档闸门（v2.2 GL 线弧不吃 bloom，1.0 起步留给
 * 实机目检对比后调档；**不继承** v3 的 1.35）。
 *
 * 失败语义：贴图加载/addEntity 失败记 WARN，本道弧视觉缺席（对齐扭曲层先例，无兜底）。
 */
class ConeArcComponent(
    id: String,
    private val origin: Vector2f,
    facingDeg: Float,
    halfAngleDeg: Float,
    length: Float,
    private val fringeColor: Color,
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(ConeArcComponent::class.java)

    /**
     * 锥中轴朝向（归一化到 [0,360)，internal 供单测断言负角入参归一化）：
     * 构造期几何（弧心/碎片散布）用任意角度都正确，但 BoxUtil 实体变换对负角会镜像反转。
     */
    internal val facingDeg: Float = BoxUtilCombatVfx.normalizeFacingDeg(facingDeg)

    /** 四道弧（构造期几何参数定死；internal 供单测断言错峰激活/几何域/宽度锚定值/包络映射）。 */
    internal val arcs = ArrayList<Arc>(ARC_COUNT)

    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var spritesAttempted = false
    private var driveWarned = false

    init {
        for (i in 0 until ARC_COUNT) {
            val dist = length * AXIS_FRACS[i]
            val center = MathUtils.getPointOnCircumference(origin, dist, facingDeg)
            val aSide = dist * tan(Math.toRadians(halfAngleDeg.toDouble())).toFloat() * SIDE_MUL
            arcs += Arc(
                delay = DELAYS[i],
                duration = DURATIONS[i],
                center = center,
                aSide0 = aSide,
                bAlong0 = aSide * B_RATIO,
                expandSpeed = EXPAND_SPEEDS[i],
                widthSu = WIDTHS_SU[i],
                alphaNorm = ALPHAS[i] / 255f,
            )
        }
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val dt = amount.coerceAtLeast(0f)
        for (arc in arcs) {
            arc.elapsed += dt
            if (!arc.activated) {
                if (arc.elapsed < arc.delay) continue
                activateArc(engine, arc)
            }
            val entity = arc.entity ?: continue
            if (entity.hasDelete() || entity.isGlobalTimerOver) continue
            val driveAge = arc.elapsed - arc.delay
            // 实体 globalTimer 全程线性淡出、duration 到点自灭，到期停止节点推送。
            if (driveAge >= arc.duration) continue
            try {
                rewriteNodes(arc, entity, driveAge)
            } catch (t: Throwable) {
                if (!driveWarned) {
                    driveWarned = true
                    log.warn("锥面弧曲梁节点推送异常（id=$id），本组件后续推送静默（组件级去重）", t)
                }
            }
        }
    }

    override fun onDetachSelf() {
        for (arc in arcs) {
            arc.entity?.delete()
            arc.entity = null
        }
    }

    /**
     * 逐帧重写局部节点实现 v2.2 的绝对外扩：a = aSide0 + expandSpeed×t、b = bAlong0 + expandSpeed×t
     * （局部 x=沿向、b 缩放；y=侧向、a 缩放；实体变换恒等，梁宽因此在世界系恒定——v2.2 恒线宽语义）。
     */
    private fun rewriteNodes(arc: Arc, entity: TrailEntity, driveAge: Float) {
        val a = expandedHalfAxis(arc.aSide0, arc.expandSpeed, driveAge)
        val b = expandedHalfAxis(arc.bAlong0, arc.expandSpeed, driveAge)
        val nodes = entity.nodes
        for (i in UNIT_NODES.indices) {
            val u = UNIT_NODES[i]
            nodes[i].set(b * u.x, a * u.y)
        }
        entity.setNodeRefreshIndex(0)
        entity.setNodeRefreshAllFromCurrentIndex()
        entity.submitNodes()
    }

    /** 激活一道弧：建多节点 TrailEntity 曲梁（贴图/addEntity 失败记 WARN，本道视觉缺席）。 */
    private fun activateArc(engine: CombatEngineAPI, arc: Arc) {
        arc.activated = true
        val pair = loadSprites() ?: return
        try {
            val entity = TrailEntity()
            for (u in UNIT_NODES) entity.addNode(Vector2f(arc.bAlong0 * u.x, arc.aSide0 * u.y))
            entity.submitNodes()
            entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
            entity.setAdditiveBlend()
            // v2.2 时间包络 alpha×(1−t)：fadeIn/full 为 0 时 BoxUtil 直接进线性淡出相，逐字等价。
            entity.setGlobalTimer(0f, 0f, arc.duration)
            entity.setStartWidth(arc.widthSu)
            entity.setEndWidth(arc.widthSu)
            entity.setMixFactor(ARC_MIX_FACTOR)
            entity.setStartColor(1f, 1f, 1f, arc.alphaNorm)
            entity.setEndColor(1f, 1f, 1f, arc.alphaNorm)
            entity.setStartEmissive(1f, 1f, 1f, arc.alphaNorm * ARC_EMISSIVE_GAIN)
            entity.setEndEmissive(1f, 1f, 1f, arc.alphaNorm * ARC_EMISSIVE_GAIN)
            val mat = entity.materialData
            mat.setAlphaToEmissive(0f)
            mat.setColorToEmissive(0f)
            mat.setGlowPower(1f)
            mat.setColor(fringeColor)
            mat.setEmissiveColor(fringeColor)
            mat.setDiffuse(pair.first)
            mat.setEmissive(pair.second)
            // 逐顶点 alpha 包络的 fill 复刻（恒等映射见类文档）：两端归零、峰值偏前 0.65。
            entity.setFillStartAlpha(0f)
            entity.setFillStartFactor(FILL_START_FACTOR)
            entity.setFillEndAlpha(0f)
            entity.setFillEndFactor(FILL_END_FACTOR)
            // 实体变换恒等（锚弧心、朝弹道、缩放 1）：扩张靠逐帧节点重写，梁宽不随矩阵放大。
            entity.setStateVanilla(arc.center, facingDeg, UNIT_SCALE)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
            if (state != 0) {
                log.warn("锥面弧曲梁注册失败（addEntity 返回 $state，id=$id），本道弧视觉缺席")
                entity.delete()
                return
            }
            arc.entity = entity
        } catch (t: Throwable) {
            log.warn("锥面弧曲梁生成异常（id=$id），本道弧视觉缺席", t)
        }
    }

    /** 贴图对（core/fringe）懒加载：失败记 WARN 一次，本组件弧视觉缺席。 */
    private fun loadSprites(): Pair<SpriteAPI, SpriteAPI>? {
        if (!spritesAttempted) {
            spritesAttempted = true
            sprites = BeamSprites.load()
            if (sprites == null) {
                log.warn("锥面弧曲梁贴图加载失败（id=$id），本组件弧视觉缺席")
            }
        }
        return sprites
    }

    /** 一道弧：v2.2 几何（弧心/两侧半轴初值/绝对外扩速度/恒宽/亮度档）、错峰时刻与后端曲梁句柄。 */
    internal class Arc(
        val delay: Float,
        val duration: Float,
        val center: Vector2f,
        val aSide0: Float,
        val bAlong0: Float,
        val expandSpeed: Float,
        val widthSu: Float,
        val alphaNorm: Float,
        var elapsed: Float = 0f,
        var activated: Boolean = false,
        var entity: TrailEntity? = null,
    )

    companion object {
        /** 弧道数与错峰时刻（0.04s 接力：t=+0.03/0.07/0.11/0.15，多层锥状壳体读感）。逐值平移 v2.2。 */
        const val ARC_COUNT = 4
        val DELAYS = floatArrayOf(0.03f, 0.07f, 0.11f, 0.15f)

        /** 轴位（锥长比例）与单道存续（秒）。逐值平移 v2.2。 */
        val AXIS_FRACS = floatArrayOf(0.3f, 0.5f, 0.7f, 0.9f)
        val DURATIONS = floatArrayOf(0.22f, 0.20f, 0.18f, 0.16f)

        /** 绝对外扩速度（su/s，a/b 同额）。逐值平移 v2.2。 */
        val EXPAND_SPEEDS = floatArrayOf(260f, 287f, 313f, 340f)

        /** 逐道亮度档（fringeColor alpha，内亮外淡）。逐值平移 v2.2。 */
        val ALPHAS = intArrayOf(120, 95, 75, 55)

        /** v2.2 线宽（px）：12/11/10/10（「至少需要 10px」；v2.2 里超驱动上限会被 clamp，此处按名义值平移）。 */
        val LINE_WIDTHS_PX = floatArrayOf(12f, 11f, 10f, 10f)

        /**
         * px→su 换算锚：pl 场景遥测 viewMult≈1.5637 px/su（v2.2 实机目检同场景）——
         * su 宽 = px ÷ 本值；12/11/10/10px ≈ 7.67/7.03/6.40/6.40su。
         */
        const val VIEW_MULT_PX_PER_SU = 1.5637f

        /** 恒宽表（su）：[LINE_WIDTHS_PX] ÷ [VIEW_MULT_PX_PER_SU]，internal 供单测锚定断言。 */
        internal val WIDTHS_SU = FloatArray(ARC_COUNT) { LINE_WIDTHS_PX[it] / VIEW_MULT_PX_PER_SU }

        /** 弧侧向半轴 = 轴上位置 × tan(halfAngle) × 本值（贴锥面轮廓内）；沿向 = 侧向 × [B_RATIO]。逐值平移 v2.2。 */
        const val SIDE_MUL = 0.85f
        const val B_RATIO = 0.5f

        /** 弧扫掠角（度）与弧心参数角（90° = 局部 +x 朝前最前点）、节点数。逐值平移 v2.2（节点数为曲梁结构参数）。 */
        const val SWEEP_DEG = 130f
        const val CENTER_DEG = 90f
        const val NODE_COUNT = 20

        /**
         * 逐顶点 alpha 包络峰值位置（v2.2 arcAlphaPeakPos=0.65，峰值偏前，两端渐隐治硬边）。
         * fill 映射：fillStartFactor = 1−峰值、fillEndFactor = 峰值（与 v2.2 包络逐点恒等，见类文档）。
         */
        const val ALPHA_PEAK_POS = 0.65f
        const val FILL_START_FACTOR = 1f - ALPHA_PEAK_POS
        const val FILL_END_FACTOR = ALPHA_PEAK_POS

        /**
         * emissive 增益（**目检调档闸门**）：v2.2 GL 线弧不吃 bloom，1.0 起步等实机目检对比后调档；
         * 不继承 v3 的 1.35。
         */
        const val ARC_EMISSIVE_GAIN = 1.0f

        /** 曲梁 mixFactor（中性：start==end 同色同宽，混合幂不影响插值）。 */
        private const val ARC_MIX_FACTOR = 1.0f

        /** 实体恒等缩放（扩张走节点重写，不走矩阵缩放，保梁宽恒定）。 */
        private val UNIT_SCALE = Vector2f(1f, 1f)

        /** 局部单位圆弧节点表（x=沿向/sinθ，y=侧向/cosθ，θ 自 25° 到 155°，θ=90° 为朝前最前点）。 */
        internal val UNIT_NODES: List<Vector2f> = unitArcNodes()

        /** v2.2 绝对外扩（纯函数）：半轴 = 初值 + expandSpeed×年龄（a/b 同额，非比例缩放）。 */
        internal fun expandedHalfAxis(half0: Float, expandSpeed: Float, ageSeconds: Float): Float =
            half0 + expandSpeed * ageSeconds.coerceAtLeast(0f)

        /** 局部单位圆弧节点（纯函数）：[NODE_COUNT] 个节点均布于 [CENTER_DEG]±[SWEEP_DEG]/2 参数弧。 */
        internal fun unitArcNodes(): List<Vector2f> {
            val startDeg = CENTER_DEG - SWEEP_DEG * 0.5f
            val step = SWEEP_DEG / (NODE_COUNT - 1).toFloat()
            return (0 until NODE_COUNT).map { i ->
                val rad = Math.toRadians((startDeg + step * i).toDouble())
                Vector2f(sin(rad).toFloat(), cos(rad).toFloat())
            }
        }
    }
}
