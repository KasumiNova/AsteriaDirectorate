package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderEntity
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 共用锥面冲击特效的声明（计划 00-锥面冲击特效重做计划 §10.7 v2.2：「顶点闪光 + 扭曲 +
 * 锥化刺束簇 + 四道朝前弧段环 + 三批三角碎片」，三案共用 RenderEntity 组件、参数化锥角/长度/调色）。
 *
 * 动机：正电子（缩小版蓝色锥面闪光）、贯星（大锥状冲击）、摧锋（后续接入）的锥面视觉
 * 收敛为同一套原型。离散射线扇面与 v1 楔块扇面相继经实机评审判死后，v2 定案为
 * AOD7 开火 + 命中配方（多道椭圆弧 + 扭曲 + spray 刺束 + 碎片 + 闪光）的锥化变体。
 * 各案只调锥角/长度/调色/时长，不各自手写粒子拼法。
 */
data class ConeImpactVfxSpec(
    /** 锥顶点（世界坐标，su）。 */
    val origin: Vector2f,

    /** 锥中轴朝向（度，世界坐标系）。 */
    val facingDeg: Float,

    /** 锥半角（度）；合法域 (0, 90]，越界 clamp 并记 WARN。 */
    val halfAngleDeg: Float,

    /** 锥长（su）：楔块全展开后的半径；非正属配置错误，记 WARN 且本次不生成特效。 */
    val length: Float,

    /** 冲击锥核心色（顶点闪光内核）。 */
    val coreColor: Color,

    /** 冲击锥辉光色（弧段环、刺束与三角碎片主体）。 */
    val fringeColor: Color,

    /** 顶点闪光色（默认同核心色）。 */
    val flashColor: Color = coreColor,

    /** 特效总存续（秒，含淡出）；非正属配置错误，记 WARN 且本次不生成特效。 */
    val duration: Float = 0.45f,

    /** 锥体自顶点扩散到全长的时长（秒）；越界 clamp 到 (0, duration] 并记 WARN。 */
    val expandSeconds: Float = 0.14f,

    /** 收尾淡出时长（秒）；不小于 duration 时 clamp 到 duration/2 并记 WARN。 */
    val fadeOutSeconds: Float = 0.22f,
)

/**
 * 共用锥面冲击特效入口（object 无状态）。
 *
 * 用法：各案在结算回调里 `ConeImpactVfx.spawn(engine, spec)` 一发即走——内部建一棵
 * 一次性 RenderEntity 树（[ConeImpactVfxComponent] 为根做错峰调度 + [StrikeSprayComponent] 刺束
 * + [ConeShardComponent] 碎片两子节点），交给 [OneShotVfxPlugin] 逐帧推进，到期自动收尾，
 * 调用方无需持有任何句柄。
 */
object ConeImpactVfx {
    private val log = Global.getLogger(ConeImpactVfx::class.java)

    /** 驱动 TTL 下限（秒）：树内自驱动层最长期（碎片 0.65s / 刺束针错峰 0.033+寿命 0.62s）+ 收尾余量。 */
    private const val MIN_DRIVER_TTL = 0.70f

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
        // expand/fadeOut 在 v2 暂不被视觉层消费（v1 楔块包络已随实机评审退役），
        // 字段保留于 spec 供后续层复用；此处仅保留越界 WARN 的配置错误可见性。
        if (spec.expandSeconds.isNaN() || spec.expandSeconds <= 0f || spec.expandSeconds > spec.duration) {
            log.warn("锥面冲击特效 expandSeconds 越界（${spec.expandSeconds}），v2 视觉层未消费该字段，请检查配置")
        }
        if (spec.fadeOutSeconds.isNaN() || spec.fadeOutSeconds <= 0f || spec.fadeOutSeconds >= spec.duration) {
            log.warn("锥面冲击特效 fadeOutSeconds 越界（${spec.fadeOutSeconds}），v2 视觉层未消费该字段，请检查配置")
        }

        val tree: RenderEntity = ConeImpactVfxComponent(
            id = "cone_impact_vfx@" + System.identityHashCode(spec),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
            halfAngleDeg = halfAngle,
            length = spec.length,
            coreColor = spec.coreColor,
            fringeColor = spec.fringeColor,
            flashColor = spec.flashColor,
        )
        val host = PointHost(
            hostId = "cone@" + System.identityHashCode(tree),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
        )
        // 驱动寿命 = 视觉总长 + 收尾余量，且不得短于树内自驱动层最长期 + 余量：
        // 弧/闪光/扭曲为自管理粒子与实体（存续与树无关），但三角碎片与刺束针由树内组件
        // 逐帧积分、detach 即摘除/删除，树提前收尾会把还在飞散的碎片与针整批掐掉。
        val plugin = OneShotVfxPlugin(engine, host, tree, maxOf(spec.duration + 0.15f, MIN_DRIVER_TTL))
        engine.addPlugin(plugin)
        return plugin
    }
}
