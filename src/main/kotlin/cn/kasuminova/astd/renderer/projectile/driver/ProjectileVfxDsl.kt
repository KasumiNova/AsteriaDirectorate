package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.MistComponent
import cn.kasuminova.astd.impl.render.bodyComponent
import cn.kasuminova.astd.impl.render.color
import cn.kasuminova.astd.impl.render.glowComponent
import cn.kasuminova.astd.impl.render.headComponent
import cn.kasuminova.astd.impl.render.length
import cn.kasuminova.astd.impl.render.renderEntity
import cn.kasuminova.astd.impl.render.ribbonComponent
import cn.kasuminova.astd.impl.render.sideWispComponent
import cn.kasuminova.astd.impl.render.trail
import cn.kasuminova.astd.impl.render.width
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.ASTDFloatRangeSpec
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxGlowLayerSpec
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxMistLayerSpec
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxSideWispLayerSpec
import cn.kasuminova.astd.impl.render.ASTDTrailEntitySpec
import cn.kasuminova.astd.impl.render.ASTDTrailLayerSpec
import cn.kasuminova.astd.impl.render.ASTDTrailRibbonDecorationSpec
import java.awt.Color

/**
 * 弹体特效的**唯一作者面**：手写 DSL 直接产出场景树 + 驱动策略，取代"从旧 Preset 反解 + 组件桥接"的过渡写法。
 * 一个 [projectileVfx] 块内：`trail{}` 定主拖尾风格（所有网格层据此取基宽/基色/中线），组件块（`glow`/`body`/
 * `head`/`mist`/`sideWisp`/`ribbon`）各自声明层参数并挂节点，`lifecycle`/`sampling`/`fade` 声明驱动策略。
 *
 * 组件节点内部复用旧渲染器的 `*ForTests` 纯网格数学（不手抄），本 DSL 只负责把作者旋钮折成渲染器所需的层 spec。
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

private fun ASTDColor.toAwt(): Color = Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))

@DslMarker
annotation class ProjectileVfxDslMarker

@ProjectileVfxDslMarker
fun projectileVfx(id: String, block: ProjectileVfxScope.() -> Unit): ProjectileVfx =
    ProjectileVfxScope(id).apply(block).build()

/**
 * DSL 作用域：收集主拖尾风格、各组件层 spec 与策略，[build] 时组装成 [ProjectileVfx]。
 * 节点组装推迟到 [build]，故组件块与 `trail{}` 的书写先后无关。
 */
@ProjectileVfxDslMarker
class ProjectileVfxScope(private val id: String) {

    private var trail: ASTDTrailLayerSpec? = null
    private var glow: List<ASTDProjectileVfxGlowLayerSpec>? = null
    private var hasBody = false
    private var head: ASTDProjectileVfxHeadLayerSpec? = null
    private var mist: List<ASTDProjectileVfxMistLayerSpec>? = null
    private var sideWisp: ASTDProjectileVfxSideWispLayerSpec? = null
    private var ribbon: ASTDTrailRibbonDecorationSpec? = null

    private val lifecycle = LifecycleBuilder()
    private val sampling = SamplingBuilder()
    private val fade = FadeBuilder()

    fun trail(block: TrailBuilder.() -> Unit) { trail = TrailBuilder().apply(block).build() }
    fun glow(block: GlowBuilder.() -> Unit) { glow = GlowBuilder().apply(block).build() }
    fun body() { hasBody = true }
    fun head(block: HeadBuilder.() -> Unit) { head = HeadBuilder().apply(block).build("${id}_head_0") }
    fun mist(block: MistBuilder.() -> Unit) { mist = listOf(MistBuilder().apply(block).build("${id}_mist_0")) }
    fun sideWisp(block: SideWispBuilder.() -> Unit) { sideWisp = SideWispBuilder().apply(block).build("${id}_side_wisp_0") }
    fun ribbon(block: RibbonBuilder.() -> Unit) { ribbon = RibbonBuilder().apply(block).build("${id}_ribbon_0") }

    fun lifecycle(block: LifecycleBuilder.() -> Unit) { lifecycle.apply(block) }
    fun sampling(block: SamplingBuilder.() -> Unit) { sampling.apply(block) }
    fun fade(block: FadeBuilder.() -> Unit) { fade.apply(block) }

    internal fun build(): ProjectileVfx {
        val trailLayer = trail ?: throw IllegalStateException("projectileVfx '$id' 必须声明 trail{} 主拖尾风格")
        val trailEntity = trailEntitySpec(trailLayer)

        val tree = renderEntity(id) {
            // 主拖尾：有 body 时由 body 网格沿中线充当拖尾主体（对齐旧 hasBodyForTrail），不另画 BoxUtil 平头直线拖尾；
            // 无 body 的弹体才落到 BoxUtil 直线拖尾兜底（当前 24 个 spec 均有 body，此分支为未来 body-less 预留）。
            if (!hasBody) {
                trail(id = "${id}_trail") {
                    color(trailLayer.color.toAwt()); width(trailLayer.width); length(trailLayer.length)
                }
            }
            mist?.let { addChild(MistComponent("${id}_mist", trailEntity, it)) }
            glow?.let { addChild(glowComponent("${id}_glow", trailEntity, it)) }
            if (hasBody) addChild(bodyComponent("${id}_body", trailEntity))
            sideWisp?.let { addChild(sideWispComponent("${id}_side_wisp", trailEntity, listOf(it))) }
            head?.let { addChild(headComponent("${id}_head", trailEntity, listOf(it), lifecycle.headSizeScale)) }
            ribbon?.let { addChild(ribbonComponent("${id}_ribbon", trailEntity, listOf(it))) }
        }

        val policy = ProjectileVfxDriverPolicy(
            minDistancePerNode = sampling.minStep,
            maxHistoryNodes = sampling.maxNodes,
            distanceWindow = sampling.window ?: trailLayer.length,
            historyFps = sampling.fps,
            durationSeconds = lifecycle.durationSeconds,
            dissolveStartRatio = lifecycle.dissolveStartRatio,
            layoutReferenceWidth = lifecycle.layoutReferenceWidth,
            hitFadeOutSeconds = fade.hitSeconds ?: fade.outSeconds,
            expireFadeOutSeconds = fade.expireSeconds ?: fade.outSeconds,
            removedFadeOutSeconds = fade.outSeconds,
            primaryTrailLength = trailLayer.length,
            primaryTrailStartWidth = trailLayer.startWidth,
        )
        return ProjectileVfx(tree, policy)
    }

    private fun trailEntitySpec(layer: ASTDTrailLayerSpec): ASTDTrailEntitySpec = ASTDTrailEntitySpec(
        layerId = "${id}_trail",
        id = "${id}_trail",
        nodes = emptyList(),
        layerSpec = layer,
        layers = listOf(layer),
        ribbonDecorations = emptyList(),
    )
}

/** 主拖尾风格：所有网格层据此取基宽/基色/中线；纹理/填充/闪烁等仅在 body-less 的 BoxUtil 直线拖尾兜底时生效。 */
@ProjectileVfxDslMarker
class TrailBuilder {
    private var width = 12f
    private var startWidth: Float? = null
    private var endWidth: Float? = null
    private var length = 420f
    private var color = rgba(0xFFFFFFFFL)
    private var endColor: ASTDColor? = null
    private var startEmissive: ASTDColor? = null
    private var endEmissive: ASTDColor? = null
    private var texturePixels = 1f
    private var textureSpeed = 4f
    private var fillStartAlpha = 1f
    private var fillEndAlpha = 1f
    private var fillStartFactor = 1f
    private var fillEndFactor = 1f
    private var stripLineMode = true
    private var blendMode = "additive"
    private var flickerSyncCode: Int? = null
    private var syncFlick = true
    private var flickMixValue = 1f
    private var flowWhenPaused = false
    private var flickWhenPaused = false

    fun width(v: Float) { width = v }
    fun widths(start: Float, end: Float) { startWidth = start; endWidth = end }
    fun length(v: Float) { length = v }
    fun color(hex: Long) { color = rgba(hex) }
    fun tail(hex: Long) { endColor = rgba(hex) }
    fun emissive(start: Long, end: Long) { startEmissive = rgba(start); endEmissive = rgba(end) }
    fun texture(pixels: Float, speed: Float) { texturePixels = pixels; textureSpeed = speed }
    fun fill(startAlpha: Float, endAlpha: Float, startFactor: Float, endFactor: Float) {
        fillStartAlpha = startAlpha; fillEndAlpha = endAlpha; fillStartFactor = startFactor; fillEndFactor = endFactor
    }
    fun strip(on: Boolean = true) { stripLineMode = on }
    fun blend(mode: String) { blendMode = mode }
    fun flickerSync(code: Int) { flickerSyncCode = code; syncFlick = false; flickMixValue = 0f }
    fun pausedMotion(on: Boolean = true) { flowWhenPaused = on; flickWhenPaused = on }

    internal fun build(): ASTDTrailLayerSpec = ASTDTrailLayerSpec(
        width = width,
        color = color,
        length = length,
        startColor = color,
        endColor = endColor ?: color,
        startEmissive = startEmissive ?: color,
        endEmissive = endEmissive ?: color,
        startWidth = startWidth ?: width,
        endWidth = endWidth ?: width,
        texturePixels = texturePixels,
        textureSpeed = textureSpeed,
        fillStartAlpha = fillStartAlpha,
        fillEndAlpha = fillEndAlpha,
        fillStartFactor = fillStartFactor,
        fillEndFactor = fillEndFactor,
        stripLineMode = stripLineMode,
        blendMode = blendMode,
        flickerSyncCode = flickerSyncCode,
        syncFlick = syncFlick,
        flickMixValue = flickMixValue,
        flowWhenPaused = flowWhenPaused,
        flickWhenPaused = flickWhenPaused,
    )
}

/** 辉光：多子层各自的宽度倍率/透明/模糊/纵偏/头尾混色。层序即绘制内外序（先声明在内）。 */
@ProjectileVfxDslMarker
class GlowBuilder {
    private val layers = ArrayList<ASTDProjectileVfxGlowLayerSpec>()

    fun layer(scale: Float, alpha: Float, blur: Float, y: Float = 0f, mixTail: Float = 0.5f, mixHead: Float = 1f) {
        layers += ASTDProjectileVfxGlowLayerSpec(
            id = "glow_${layers.size}",
            widthScale = scale,
            alphaScale = alpha,
            blur = blur,
            yOffset = y,
            colorMixTail = mixTail,
            colorMixHead = mixHead,
        )
    }

    internal fun build(): List<ASTDProjectileVfxGlowLayerSpec> = layers.toList()
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

    internal fun build(id: String): ASTDProjectileVfxHeadLayerSpec = ASTDProjectileVfxHeadLayerSpec(
        id = id,
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

/** 薄雾：blob 数、rx/ry/透明区间、噪声/漂移、起止色。 */
@ProjectileVfxDslMarker
class MistBuilder {
    private var blobs = 32
    private var rxMin = 2f
    private var rxMax = 6f
    private var ryMin = 0.5f
    private var ryMax = 1.6f
    private var alphaMin = 0.02f
    private var alphaMax = 0.08f
    private var noise = 5f
    private var drift = 0.3f
    private var lengthScale = 1f
    private var widthScale = 1f
    private var colorStart = rgba(0x00000010L)
    private var colorEnd = rgba(0xFFFFFFFFL)

    fun blobs(v: Int) { blobs = v }
    fun rx(min: Float, max: Float) { rxMin = min; rxMax = max }
    fun ry(min: Float, max: Float) { ryMin = min; ryMax = max }
    fun alpha(min: Float, max: Float) { alphaMin = min; alphaMax = max }
    fun noise(v: Float) { noise = v }
    fun drift(v: Float) { drift = v }
    fun lengthScale(v: Float) { lengthScale = v }
    fun widthScale(v: Float) { widthScale = v }
    fun colors(start: Long, end: Long) { colorStart = rgba(start); colorEnd = rgba(end) }

    internal fun build(id: String): ASTDProjectileVfxMistLayerSpec = ASTDProjectileVfxMistLayerSpec(
        id = id,
        blobCount = blobs,
        lengthScale = lengthScale,
        widthScale = widthScale,
        rxRange = ASTDFloatRangeSpec(rxMin, rxMax),
        ryRange = ASTDFloatRangeSpec(ryMin, ryMax),
        alphaRange = ASTDFloatRangeSpec(alphaMin, alphaMax),
        noiseScale = noise,
        driftSpeed = drift,
        colorStart = colorStart,
        colorEnd = colorEnd,
    )
}

/** 侧翼流苏：多偏移丝线、宽度倍率/透明/模糊、起止长度比、色。 */
@ProjectileVfxDslMarker
class SideWispBuilder {
    private var offsets = listOf(-1.5f, 1.5f)
    private var width = 0.2f
    private var alpha = 0.24f
    private var blur = 10f
    private var lengthStart = 0.64f
    private var lengthEnd = 0.28f
    private var color = rgba(0x73B3FFB8L)

    fun offsets(vararg values: Float) { offsets = values.toList() }
    fun width(v: Float) { width = v }
    fun alpha(v: Float) { alpha = v }
    fun blur(v: Float) { blur = v }
    fun length(startRatio: Float, endRatio: Float) { lengthStart = startRatio; lengthEnd = endRatio }
    fun color(hex: Long) { color = rgba(hex) }

    internal fun build(id: String): ASTDProjectileVfxSideWispLayerSpec = ASTDProjectileVfxSideWispLayerSpec(
        id = id,
        offsets = offsets,
        widthScale = width,
        alphaScale = alpha,
        blur = blur,
        lengthStartRatio = lengthStart,
        lengthEndRatio = lengthEnd,
        color = color,
    )
}

/** 飘带：频率/振幅/波型（sine|noise）+ 噪声尺度、厚度/透明/模糊、起止/主三色。 */
@ProjectileVfxDslMarker
class RibbonBuilder {
    private var frequency = 1.1f
    private var amplitude = 1.35f
    private var waveType = "sine"
    private var noiseScale = 1f
    private var thickness = 1f
    private var alpha = 1f
    private var blur = 0f
    private var startColor = rgba(0xFFFFFFFFL)
    private var endColor = rgba(0xFFFFFFFFL)
    private var color = rgba(0xFFFFFFFFL)

    fun frequency(v: Float) { frequency = v }
    fun amplitude(v: Float) { amplitude = v }
    fun wave(type: String, scale: Float = 1f) { waveType = type; noiseScale = scale }
    fun thickness(v: Float) { thickness = v }
    fun alpha(v: Float) { alpha = v }
    fun blur(v: Float) { blur = v }
    fun colors(start: Long, end: Long, main: Long) { startColor = rgba(start); endColor = rgba(end); color = rgba(main) }

    internal fun build(id: String): ASTDTrailRibbonDecorationSpec = ASTDTrailRibbonDecorationSpec(
        frequency = frequency,
        amplitude = amplitude,
        id = id,
        thickness = thickness,
        alphaScale = alpha,
        waveType = waveType,
        noiseScale = noiseScale,
        blur = blur,
        startColor = startColor,
        endColor = endColor,
        color = color,
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
