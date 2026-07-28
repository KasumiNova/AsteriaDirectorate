package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderEntity
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.sin

/**
 * 共用锥面冲击特效的声明（规格 00-共享基建 §2.2-5：「顶点闪光 + 沿中轴扩散的冲击锥」，
 * 三案共用 RenderEntity 组件、参数化锥角/长度/调色）。
 *
 * 动机：正电子（缩小版蓝色锥面闪光）、贯星（大锥状冲击）、摧锋（后续接入）的锥面视觉
 * 收敛为同一套原型——顶点一口闪光 + 一束沿中轴扩散的锥形射线面（BoxUtil 渐变拖尾）。
 * 各案只调锥角/长度/调色/时长，不各自手写粒子拼法。
 */
data class ConeImpactVfxSpec(
    /** 锥顶点（世界坐标，su）。 */
    val origin: Vector2f,

    /** 锥中轴朝向（度，世界坐标系）。 */
    val facingDeg: Float,

    /** 锥半角（度）；合法域 (0, 90]，越界 clamp 并记 WARN。 */
    val halfAngleDeg: Float,

    /** 锥长（su）：射线全展开后的长度；非正属配置错误，记 WARN 且本次不生成特效。 */
    val length: Float,

    /** 冲击锥核心色（射线主体与顶点内闪）。 */
    val coreColor: Color,

    /** 冲击锥辉光色（射线发光端与顶点后尘）。 */
    val fringeColor: Color,

    /** 顶点闪光色（默认同核心色）。 */
    val flashColor: Color = coreColor,

    /** 特效总存续（秒，含淡出）；非正属配置错误，记 WARN 且本次不生成特效。 */
    val duration: Float = 0.45f,

    /** 锥体自顶点扩散到全长的时长（秒）；越界 clamp 到 (0, duration] 并记 WARN。 */
    val expandSeconds: Float = 0.14f,

    /** 收尾淡出时长（秒）；不小于 duration 时 clamp 到 duration/2 并记 WARN。 */
    val fadeOutSeconds: Float = 0.22f,

    /** 射线角间隔（度）：决定锥面射线密度；非正 clamp 到默认并记 WARN。 */
    val raySpacingDeg: Float = 12f,

    /** 绘制层（默认舰船上层，与冲击条纹惯例一致）。 */
    val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
)

/**
 * 共用锥面冲击特效入口（object 无状态）。
 *
 * 用法：各案在结算回调里 `ConeImpactVfx.spawn(engine, spec)` 一发即走——内部建一棵
 * 一次性 RenderEntity 树（[ConeImpactVfxComponent] 为根），交给 [OneShotVfxPlugin] 逐帧推进，
 * 到期自动收尾，调用方无需持有任何句柄。
 */
object ConeImpactVfx {
    private val log = Global.getLogger(ConeImpactVfx::class.java)

    /** 射线数下限（锥面至少成形的三条：中轴 + 两缘）。 */
    internal const val MIN_RAYS = 3

    /** 射线数上限（窄间隔大锥角的防爆闸）。 */
    internal const val MAX_RAYS = 25

    /** 单条射线基宽下限/上限（su），防止极窄锥看不见、极宽锥糊屏。 */
    internal const val RAY_WIDTH_MIN = 3f
    internal const val RAY_WIDTH_MAX = 42f

    /**
     * 生成一发锥面冲击特效。返回驱动插件（调用方通常不感知；返回供测试与调试定位）。
     * 入参非法（length/duration 非正）时记 WARN 并返回 null，不产出半成品特效。
     */
    fun spawn(engine: CombatEngineAPI, spec: ConeImpactVfxSpec): OneShotVfxPlugin? {
        if (spec.length.isNaN() || spec.length <= 0f) {
            log.warn("锥面冲击特效 length 非正（${spec.length}），属配置错误，本次不生成特效")
            return null
        }
        if (spec.duration.isNaN() || spec.duration <= 0f) {
            log.warn("锥面冲击特效 duration 非正（${spec.duration}），属配置错误，本次不生成特效")
            return null
        }

        var halfAngle = spec.halfAngleDeg
        if (halfAngle.isNaN() || halfAngle <= 0f || halfAngle > 90f) {
            val clamped = if (halfAngle.isNaN()) 30f else halfAngle.coerceIn(1f, 90f)
            log.warn("锥面冲击特效 halfAngleDeg 越界（${spec.halfAngleDeg}），clamp 到 $clamped")
            halfAngle = clamped
        }
        var expand = spec.expandSeconds
        if (expand.isNaN() || expand <= 0f || expand > spec.duration) {
            val clamped = if (expand.isNaN() || expand <= 0f) spec.duration * 0.3f else spec.duration
            log.warn("锥面冲击特效 expandSeconds 越界（${spec.expandSeconds}），clamp 到 $clamped")
            expand = clamped
        }
        var fadeOut = spec.fadeOutSeconds
        if (fadeOut.isNaN() || fadeOut <= 0f || fadeOut >= spec.duration) {
            val clamped = spec.duration * 0.5f
            log.warn("锥面冲击特效 fadeOutSeconds 越界（${spec.fadeOutSeconds}），clamp 到 $clamped")
            fadeOut = clamped
        }
        var spacing = spec.raySpacingDeg
        if (spacing.isNaN() || spacing <= 0f) {
            log.warn("锥面冲击特效 raySpacingDeg 非法（${spec.raySpacingDeg}），clamp 到 12")
            spacing = 12f
        }

        val rayOffsets = layoutRayOffsets(halfAngle, spacing)
        val rayWidth = rayBaseWidth(spec.length, halfAngle, rayOffsets.size)

        val tree: RenderEntity = ConeImpactVfxComponent(
            id = "cone_impact_vfx@" + System.identityHashCode(spec),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
            length = spec.length,
            duration = spec.duration,
            expandSeconds = expand,
            fadeOutSeconds = fadeOut,
            rayOffsetsDeg = rayOffsets,
            rayBaseWidth = rayWidth,
            coreColor = spec.coreColor,
            fringeColor = spec.fringeColor,
            flashColor = spec.flashColor,
            layer = spec.layer,
        )
        val host = PointHost(
            hostId = "cone@" + System.identityHashCode(tree),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
        )
        // 驱动寿命 = 视觉总长 + 收尾余量：BoxUtil 全局定时器在 duration 时刻已删完句柄，
        // 余量只保证驱动不与渲染器抢同一帧。
        val plugin = OneShotVfxPlugin(engine, host, tree, spec.duration + 0.15f)
        engine.addPlugin(plugin)
        return plugin
    }

    /**
     * 锥面射线布局（纯函数）：相对中轴的偏角序列，奇数条、含中轴 0 与 ±halfAngle 两缘，
     * 条数由角间隔推导并 clamp 到 [MIN_RAYS, MAX_RAYS]。
     */
    internal fun layoutRayOffsets(halfAngleDeg: Float, spacingDeg: Float): List<Float> {
        val count = (ceil((halfAngleDeg * 2f) / spacingDeg).toInt() + 1).coerceIn(MIN_RAYS, MAX_RAYS)
        val odd = if (count % 2 == 0) count + 1 else count
        val step = (halfAngleDeg * 2f) / (odd - 1)
        return (0 until odd).map { -halfAngleDeg + step * it }
    }

    /**
     * 单条射线基宽（纯函数）：锥端弧长均分到每条射线的弦宽 ×0.8（留缝隙读感），
     * clamp 到 [RAY_WIDTH_MIN, RAY_WIDTH_MAX]。
     */
    internal fun rayBaseWidth(length: Float, halfAngleDeg: Float, rayCount: Int): Float {
        val chord = 2f * length * sin(Math.toRadians(halfAngleDeg.toDouble())).toFloat()
        return (chord / rayCount.coerceAtLeast(1) * 0.8f).coerceIn(RAY_WIDTH_MIN, RAY_WIDTH_MAX)
    }
}
