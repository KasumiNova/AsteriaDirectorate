package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.impl.render.ASTDTrailLayerSpec
import cn.kasuminova.astd.impl.render.TexTrailComponent
import cn.kasuminova.astd.impl.render.TexTrailSpec
import cn.kasuminova.astd.impl.render.headBloomComponent
import cn.kasuminova.astd.impl.render.renderEntity

/**
 * 弹体特效的**唯一作者面**：手写 DSL 直接产出场景树 + 驱动策略。
 * 一个 [projectileVfx] 块内：`trail{}` 定拖尾风格声明（弹头网格据此取基宽/基色，驱动取锚点长宽），
 * `head{}` 声明 bloom 弹头层参数，`texTrail` 声明贴图拖尾主体层（可多条叠层），
 * `lifecycle`/`sampling`/`fade` 声明驱动策略。
 *
 * 组件节点内部复用几何层的 `*ForTests` 纯网格数学（不手抄），本 DSL 只负责把作者旋钮折成渲染器所需的层 spec。
 * 每次生成弹体都重新调用构建函数（不缓存），以支持调试期字面量热交换（见设计 §7）。
 */
class ProjectileVfx(val tree: RenderEntity, val policy: ProjectileVfxDriverPolicy)

/** 颜色字面量：0xRRGGBBAA。 */
internal fun rgba(hex: Long): ASTDColor = ASTDColor(
    ((hex shr 24) and 0xFF) / 255f,
    ((hex shr 16) and 0xFF) / 255f,
    ((hex shr 8) and 0xFF) / 255f,
    (hex and 0xFF) / 255f,
)

@DslMarker
annotation class ProjectileVfxDslMarker

@ProjectileVfxDslMarker
fun projectileVfx(id: String, block: ProjectileVfxScope.() -> Unit): ProjectileVfx =
    ProjectileVfxScope(id).apply(block).build()

/**
 * DSL 作用域：收集拖尾风格声明、弹头层 spec、贴图拖尾与策略，[build] 时组装成 [ProjectileVfx]。
 * 节点组装推迟到 [build]，故组件块与 `trail{}` 的书写先后无关。
 */
@ProjectileVfxDslMarker
class ProjectileVfxScope(private val id: String) {

    private var trail: ASTDTrailLayerSpec? = null
    private var head: ASTDProjectileVfxHeadLayerSpec? = null
    private val texTrails = ArrayList<Pair<String, TexTrailSpec>>()

    private val lifecycle = LifecycleBuilder()
    private val sampling = SamplingBuilder()
    private val fade = FadeBuilder()

    fun trail(block: TrailBuilder.() -> Unit) { trail = TrailBuilder().apply(block).build() }
    fun head(block: HeadBuilder.() -> Unit) { head = HeadBuilder().apply(block).build() }

    /** 叠加一条贴图拖尾主体层（复刻 MagicTrail：平铺滚动贴图 + CPU 折线带体），可多次调用按 [TexTrailBuilder.layer] 叠层。 */
    fun texTrail(name: String, texturePath: String, block: TexTrailBuilder.() -> Unit) {
        texTrails += name to TexTrailBuilder(texturePath).apply(block).build()
    }

    fun lifecycle(block: LifecycleBuilder.() -> Unit) { lifecycle.apply(block) }
    fun sampling(block: SamplingBuilder.() -> Unit) { sampling.apply(block) }
    fun fade(block: FadeBuilder.() -> Unit) { fade.apply(block) }

    internal fun build(): ProjectileVfx {
        val trailLayer = trail
        // head{} 弹头以 trail{} 为基宽/基色来源；texTrail 自足，不需要 trail{}。
        if (trailLayer == null && head != null) {
            throw IllegalStateException("projectileVfx '$id' 声明了 head{} 弹头（以 trail{} 为基宽/基色来源），必须声明 trail{} 拖尾风格")
        }

        val tree = renderEntity(id) {
            if (trailLayer != null) {
                head?.let {
                    // 弹头恒并入 bloom 管线（同一离屏提取+模糊+合成）：弹头与拖尾能量同源，
                    // 接缝处光晕连续——直绘弹头不进 bloom，能量天然低于带体，调色抹不平接缝色差
                    addChild(headBloomComponent("${id}_head", trailLayer, listOf(it), lifecycle.headSizeScale))
                }
            }
            texTrails.forEach { (name, spec) -> addChild(TexTrailComponent("${id}_textrail_$name", spec)) }
        }

        // 拖尾驱动锚点（可视长度/历史窗口的基准长宽）：有 trail{} 取其长宽；
        // 无 trail{}（texTrail 即拖尾主体）时取 sampling.window 与最宽一条 texTrail。
        val anchorLength: Float
        val anchorWidth: Float
        if (trailLayer != null) {
            anchorLength = trailLayer.length
            anchorWidth = trailLayer.startWidth
        } else {
            if (texTrails.isEmpty()) {
                throw IllegalStateException("projectileVfx '$id' 未声明 trail{}，且没有任何 texTrail 拖尾主体")
            }
            anchorLength = sampling.window
                ?: throw IllegalStateException("projectileVfx '$id' 未声明 trail{}，sampling.window 必须显式声明作为拖尾距离窗口")
            anchorWidth = texTrails.maxOf { it.second.width }
        }

        val policy = ProjectileVfxDriverPolicy(
            minDistancePerNode = sampling.minStep,
            maxHistoryNodes = sampling.maxNodes,
            distanceWindow = sampling.window ?: anchorLength,
            historyFps = sampling.fps,
            durationSeconds = lifecycle.durationSeconds,
            dissolveStartRatio = lifecycle.dissolveStartRatio,
            layoutReferenceWidth = lifecycle.layoutReferenceWidth,
            hitFadeOutSeconds = fade.hitSeconds ?: fade.outSeconds,
            expireFadeOutSeconds = fade.expireSeconds ?: fade.outSeconds,
            removedFadeOutSeconds = fade.outSeconds,
            primaryTrailLength = anchorLength,
            primaryTrailStartWidth = anchorWidth,
        )
        return ProjectileVfx(tree, policy)
    }
}

/** 拖尾风格声明：弹头网格据此取基宽/基色，驱动取锚点长宽（可视长度/历史窗口基准）。 */
@ProjectileVfxDslMarker
class TrailBuilder {
    private var startWidth = 12f
    private var length = 420f
    private var startColor = rgba(0xFFFFFFFFL)
    private var startEmissive = rgba(0xFFFFFFFFL)
    private var endColor = rgba(0xFFFFFFFFL)

    fun width(v: Float) { startWidth = v }
    fun length(v: Float) { length = v }
    fun color(hex: Long) { startColor = rgba(hex) }
    fun tail(hex: Long) { endColor = rgba(hex) }
    fun emissive(hex: Long) { startEmissive = rgba(hex) }

    internal fun build(): ASTDTrailLayerSpec = ASTDTrailLayerSpec(
        startWidth = startWidth,
        length = length,
        startColor = startColor,
        startEmissive = startEmissive,
        endColor = endColor,
    )
}

/** 弹头：收拢亮头的长宽/肩后比/壳三色（内→中→外）/模糊。 */
@ProjectileVfxDslMarker
class HeadBuilder {
    private var length = 120f
    private var width = 24f
    private var shoulder = 0.5f
    private var rear = 0.95f
    private var blur = 0.35f
    private var alpha = 1f
    private var shellStart = rgba(0x00000014L)
    private var shellMid = rgba(0xB8F0FF75L)
    private var shellEnd = rgba(0xFFFFFFFAL)

    fun length(v: Float) { length = v }
    fun width(v: Float) { width = v }
    fun shoulder(v: Float) { shoulder = v }
    fun rear(v: Float) { rear = v }
    fun blur(v: Float) { blur = v }
    fun alpha(v: Float) { alpha = v }
    fun shell(start: Long, mid: Long, end: Long) { shellStart = rgba(start); shellMid = rgba(mid); shellEnd = rgba(end) }

    internal fun build(): ASTDProjectileVfxHeadLayerSpec = ASTDProjectileVfxHeadLayerSpec(
        length = length,
        width = width,
        shoulderRatio = shoulder,
        rearRatio = rear,
        shellColorStart = shellStart,
        shellColorMid = shellMid,
        shellColorEnd = shellEnd,
        blur = blur,
        alphaScale = alpha,
    )
}

/**
 * 贴图拖尾（复刻 MagicTrail）：CPU 折线带体 + 平铺滚动贴图图案的拖尾主体层。
 * 贴图约定同 gr_trails_*：X=横向、Y=带长向（REPEAT），形在 alpha 通道、RGB 近白。
 */
@ProjectileVfxDslMarker
class TexTrailBuilder(private val texturePath: String) {
    private var layer = 1
    private var width = 12f
    private var headColor = rgba(0xFFFFFFEBL)
    private var midColor: ASTDColor? = null
    private var midT = 0.25f
    private var tailColor = rgba(0x0A1C380FL)
    private var nodeCount = 24
    private var tileLength = 180f
    private var scrollSpeed = 0f
    private var recede = 0f

    /** 叠层序号：同弹体多条贴图拖尾的绘制先后（1 垫底、2 其上，以此类推）。 */
    fun layer(v: Int) { layer = v }

    /** 拖尾全宽（世界单位）。 */
    fun width(v: Float) { width = v }

    /** 头尾颜色（0xRRGGBBAA）：头部亮端 → 尾部暗端，逐节点插值。 */
    fun colors(head: Long, tail: Long) { headColor = rgba(head); midColor = null; tailColor = rgba(tail) }

    /** 三段上色（0xRRGGBBAA）：白热头 → [midAt] 处签名色中段 → 暗尾。 */
    fun colors(head: Long, mid: Long, tail: Long, midAt: Float) {
        headColor = rgba(head); midColor = rgba(mid); tailColor = rgba(tail); midT = midAt
    }

    /** 节点数（沿带长均匀分布，弯道平滑度）。 */
    fun nodes(count: Int) { nodeCount = count }

    /** 图案平铺周期（世界单位）与滚动速度（世界单位/秒，0 不滚动）。 */
    fun tile(length: Float, scroll: Float) { tileLength = length; scrollSpeed = scroll }

    /** 带体整体向后退的距离（世界单位）：带体头部亮端退到弹头网格之后，让弹头尖在带体前露出。 */
    fun recede(v: Float) { recede = v }

    internal fun build(): TexTrailSpec = TexTrailSpec(
        width = width,
        texturePath = texturePath,
        layer = layer,
        headColor = headColor,
        midColor = midColor,
        midT = midT,
        tailColor = tailColor,
        nodeCount = nodeCount,
        tileLength = tileLength,
        scrollSpeed = scrollSpeed,
        recede = recede,
    )
}

/** 生命周期策略：飞行时长/溶解起点比/弹头尺寸倍率/布局参考宽度。 */
@ProjectileVfxDslMarker
class LifecycleBuilder {
    var durationSeconds = 1.25f; private set
    var dissolveStartRatio = 0.6f; private set
    var headSizeScale = 1.5f; private set
    var layoutReferenceWidth = 1280f; private set

    fun duration(v: Float) { durationSeconds = v }
    fun dissolveAt(v: Float) { dissolveStartRatio = v }
    fun headScale(v: Float) { headSizeScale = v }
    fun layoutRef(v: Float) { layoutReferenceWidth = v }
}

/** 采样策略：历史帧率/最大节点数/最小步距/距离窗口（省略窗口则用 trail.length）。 */
@ProjectileVfxDslMarker
class SamplingBuilder {
    var fps = 60f; private set
    var maxNodes = 96; private set
    var minStep = 2f; private set
    var window: Float? = null; private set

    fun fps(v: Float) { fps = v }
    fun maxNodes(v: Int) { maxNodes = v }
    fun minStep(v: Float) { minStep = v }
    fun window(v: Float) { window = v }
}

/** 淡出策略：默认淡出秒数 + 命中/过期各自秒数（省略则同默认）。 */
@ProjectileVfxDslMarker
class FadeBuilder {
    var outSeconds = 0.15f; private set
    var hitSeconds: Float? = null; private set
    var expireSeconds: Float? = null; private set

    fun out(v: Float) { outSeconds = v }
    fun hit(v: Float) { hitSeconds = v }
    fun expire(v: Float) { expireSeconds = v }
}
