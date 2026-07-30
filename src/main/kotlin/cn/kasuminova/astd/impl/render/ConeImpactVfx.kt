package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderEntity
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 共用锥面冲击特效的声明（计划 00-锥面冲击特效重做计划：「顶点闪光 + 顶点烟雾 +
 * 连续扇形楔块 + 三道扩张弧」，三案共用 RenderEntity 组件、参数化锥角/长度/调色）。
 *
 * 动机：正电子（缩小版蓝色锥面闪光）、贯星（大锥状冲击）、摧锋（后续接入）的锥面视觉
 * 收敛为同一套原型。离散射线扇面（等角距、同亮同灭、色温割裂）经实机评审判死后重做为：
 * 楔块主体 = 连续扇形三角网格（texTrail 管线，渐变贴图 + UV 滚动破均匀），
 * 扩张弧 = OglEllipseRingRenderer 弧段（波前推进读感），顶点烟雾/闪光 = vanilla 粒子。
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

    /** 冲击锥核心色（楔块底层与顶点内闪）。 */
    val coreColor: Color,

    /** 冲击锥辉光色（楔块图案层、扩张弧与顶点烟雾）。 */
    val fringeColor: Color,

    /** 顶点闪光色（默认同核心色）。 */
    val flashColor: Color = coreColor,

    /** 特效总存续（秒，含淡出）；非正属配置错误，记 WARN 且本次不生成特效。 */
    val duration: Float = 0.45f,

    /** 锥体自顶点扩散到全长的时长（秒）；越界 clamp 到 (0, duration] 并记 WARN。 */
    val expandSeconds: Float = 0.14f,

    /** 收尾淡出时长（秒）；不小于 duration 时 clamp 到 duration/2 并记 WARN。 */
    val fadeOutSeconds: Float = 0.22f,

    /** 楔块底层贴图（填充/体积）：云状噪声带，带宽居中对称（计划 §2.2 实测配对 v 带 [−0.60, +0.56]）。 */
    val wedgeTexturePath: String = "graphics/fx/astd_trails_contrail.png",

    /** 楔块图案层贴图（能量/动感）：近全宽混乱条纹，滚动更快、alpha 更低（v 带 [−0.76, +0.90]）。 */
    val wedgePatternTexturePath: String = "graphics/fx/astd_trails_surge.png",

    /** 楔块底层花纹径向外滚速度（su/s）；图案层 ×1.7 为组件常量（MagicTrail 多层错参手法）。 */
    val textureScrollSpeed: Float = 300f,
)

/**
 * 共用锥面冲击特效入口（object 无状态）。
 *
 * 用法：各案在结算回调里 `ConeImpactVfx.spawn(engine, spec)` 一发即走——内部建一棵
 * 一次性 RenderEntity 树（[ConeImpactVfxComponent] 为根 + 两枚 [ConeWedgeComponent] 楔块子节点），
 * 交给 [OneShotVfxPlugin] 逐帧推进，到期自动收尾，调用方无需持有任何句柄。
 */
object ConeImpactVfx {
    private val log = Global.getLogger(ConeImpactVfx::class.java)

    /** 楔块角向每列固定抖动的上界（度）：spawn 时随机一次、生命周期不变（破等角距机械感）。 */
    internal const val WEDGE_JITTER_DEG = 2f

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

        val angularSegs = wedgeAngularSegments(halfAngle)
        val jitter = FloatArray(angularSegs + 1) {
            MathUtils.getRandomNumberInRange(-WEDGE_JITTER_DEG, WEDGE_JITTER_DEG)
        }

        val tree: RenderEntity = ConeImpactVfxComponent(
            id = "cone_impact_vfx@" + System.identityHashCode(spec),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
            halfAngleDeg = halfAngle,
            length = spec.length,
            duration = spec.duration,
            expandSeconds = expand,
            fadeOutSeconds = fadeOut,
            coreColor = spec.coreColor,
            fringeColor = spec.fringeColor,
            flashColor = spec.flashColor,
            wedgeTexturePath = spec.wedgeTexturePath,
            wedgePatternTexturePath = spec.wedgePatternTexturePath,
            textureScrollSpeed = spec.textureScrollSpeed,
            angularSegs = angularSegs,
            angularJitter = jitter,
        )
        val host = PointHost(
            hostId = "cone@" + System.identityHashCode(tree),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
        )
        // 驱动寿命 = 视觉总长 + 收尾余量：楔块淡出包络在 duration 内完成，余量只保证驱动
        // 不与渲染器抢同一帧；弧/烟雾/闪光全为自管理粒子，存续与树无关。
        val plugin = OneShotVfxPlugin(engine, host, tree, spec.duration + 0.15f)
        engine.addPlugin(plugin)
        return plugin
    }
}
