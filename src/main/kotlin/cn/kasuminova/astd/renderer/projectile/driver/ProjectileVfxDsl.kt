package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.impl.render.ASTDTrailLayerSpec
import cn.kasuminova.astd.impl.render.AnchorArcComponent
import cn.kasuminova.astd.impl.render.AnchorArcSpec
import cn.kasuminova.astd.impl.render.BoxFlareComponent
import cn.kasuminova.astd.impl.render.BoxFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.impl.render.TexTrailComponent
import cn.kasuminova.astd.impl.render.TexTrailSpec
import cn.kasuminova.astd.impl.render.headBloomComponent
import cn.kasuminova.astd.impl.render.renderEntity
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI

/** 弹体发射瞬间的附加动作钩子（如发射点扭曲特效）；由分发器在登记弹体成功后调用一次。 */
fun interface ProjectileVfxOnFireHook {
    fun onFire(engine: CombatEngineAPI, projectile: DamagingProjectileAPI)
}

/**
 * 弹体特效的**唯一作者面**：手写 DSL 直接产出场景树 + 驱动策略。
 * 一个 [projectileVfx] 块内：`trail{}` 定拖尾风格声明（弹头网格据此取基宽/基色，驱动取锚点长宽），
 * `head{}` 声明 bloom 弹头层参数，`texTrail` 声明贴图拖尾主体层（可多条叠层），
 * `lifecycle`/`sampling`/`fade` 声明驱动策略。
 *
 * 组件节点内部复用几何层的 `*ForTests` 纯网格数学（不手抄），本 DSL 只负责把作者旋钮折成渲染器所需的层 spec。
 * 每次生成弹体都重新调用构建函数（不缓存），以支持调试期字面量热交换（见设计 §7）。
 */
class ProjectileVfx(
    val tree: RenderEntity,
    val policy: ProjectileVfxDriverPolicy,
    /** 发射瞬间附加动作（如发射点扭曲特效）；null 则无。 */
    val onFire: ProjectileVfxOnFireHook? = null,
)

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
    private val boxFlares = ArrayList<Pair<String, BoxFlareSpec>>()
    private val anchorArcs = ArrayList<Pair<String, AnchorArcSpec>>()
    private var onFireHook: ProjectileVfxOnFireHook? = null

    private val lifecycle = LifecycleBuilder()
    private val sampling = SamplingBuilder()
    private val fade = FadeBuilder()

    fun trail(block: TrailBuilder.() -> Unit) { trail = TrailBuilder().apply(block).build() }
    fun head(block: HeadBuilder.() -> Unit) { head = HeadBuilder().apply(block).build() }

    /** 叠加一条贴图拖尾主体层（复刻 MagicTrail：平铺滚动贴图 + CPU 折线带体），可多次调用按 [TexTrailBuilder.layer] 叠层。 */
    fun texTrail(name: String, texturePath: String, block: TexTrailBuilder.() -> Unit) {
        texTrails += name to TexTrailBuilder(texturePath).apply(block).build()
    }

    /** 挂一枚 BoxUtil 光斑（跟随弹体视觉头部；offsetX 负值可锚回弹体中心）。 */
    fun boxFlare(name: String, block: BoxFlareBuilder.() -> Unit) {
        boxFlares += name to BoxFlareBuilder().apply(block).build()
    }

    /** 拉一条原版 EMP 锚点电弧：发射点（attach 时捕获的固定位置）→ 弹体头部（每帧跟随），随弹体生命周期存续。 */
    fun anchorArc(name: String, block: AnchorArcBuilder.() -> Unit) {
        anchorArcs += name to AnchorArcBuilder().apply(block).build()
    }

    /** 发射瞬间附加动作（如发射点扭曲特效）：登记弹体成功后由分发器调用一次。 */
    fun onFire(hook: ProjectileVfxOnFireHook) { onFireHook = hook }

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
            boxFlares.forEach { (name, spec) -> addChild(BoxFlareComponent("${id}_boxflare_$name", spec)) }
            anchorArcs.forEach { (name, spec) -> addChild(AnchorArcComponent("${id}_anchorarc_$name", spec)) }
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
            headLeadWorld = lifecycle.headLeadWorld,
        )
        return ProjectileVfx(tree, policy, onFireHook)
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
 * 贴图约定同 astd_trails_*：X=横向、Y=带长向（REPEAT），形在 alpha 通道、RGB 近白。
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
    private var wobbleAmplitude = 0f
    private var wobbleWavelength = 90f
    private var wobbleScroll = 0f
    private var wobblePhase = 0f
    private var lifetimeSeconds = 0f
    private var dissolveStart = 0.6f
    private var twistMaxAngleDeg = 0f
    private var twistTurnDegPerSec = 0f
    private var twistWavelength = 0f

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

    /** 节点数下限（沿带长均匀分布，弯道平滑度）：实际渲染节点数按可见带长动态细分，本值为短带保底。 */
    fun nodes(count: Int) { nodeCount = count }

    /** 图案平铺周期（世界单位）与滚动速度（世界单位/秒，0 不滚动）。 */
    fun tile(length: Float, scroll: Float) { tileLength = length; scrollSpeed = scroll }

    /** 带体整体向后退的距离（世界单位）：带体头部亮端退到弹头网格之后，让弹头尖在带体前露出。 */
    fun recede(v: Float) { recede = v }

    /**
     * 带体横向扰动（复刻 MagicTrail dispersion）：正弦叠加横向漂移让带体散开摆动。
     * [amplitude] 峰值振幅（世界单位，建议 ≤ 带宽 1/4），[wavelength] 主波长（带长向），
     * [scroll] 图案沿带长平移速度（su/s，0 静止），[phase] 初始相位（弧度，叠层错相用）。
     * 不调用即不扰动，观感与旧行为逐点一致。
     */
    fun wobble(amplitude: Float, wavelength: Float, scroll: Float = 0f, phase: Float = 0f) {
        wobbleAmplitude = amplitude; wobbleWavelength = wavelength; wobbleScroll = scroll; wobblePhase = phase
    }

    /**
     * 逐节点寿命覆写（秒，0 = 自动按「预期带长/实测速度」估算）与消散起点（年龄进度 0..1）。
     * 节点按年龄老去：dissolveStart 前满亮，之后线性消散到寿命尽头；弹体消亡后尾先消、头后消。
     */
    fun lifetime(seconds: Float, dissolveStart: Float = 0.6f) {
        lifetimeSeconds = seconds; this.dissolveStart = dissolveStart
    }

    /** 消散起点（年龄进度 0..1）：单独调消散起点时用；等价 [lifetime] 的第二参数。 */
    fun dissolveStart(ratio: Float) { dissolveStart = ratio }

    /**
     * 平面内随机扭转（复刻 MagicTrail 段落自旋观感）：带体沿距头弧长取平滑值噪声角 ∈ ±[maxAngleDeg]
     * （弧长桶种子 + smoothstep 桶间过渡：带体系跨帧稳定不闪，前后段自动衔接无折点），随节点年龄按
     * [turnDegPerSec] 累积扭转。[wavelength] 为噪声空间波长（世界单位，0 = 与贴图平铺周期同频）。
     * θ=±90° 时该处完全折向带长向。与 wobble（横向平移扰动）正交可叠加；不调用即关闭。
     */
    fun twist(maxAngleDeg: Float, turnDegPerSec: Float = 0f, wavelength: Float = 0f) {
        twistMaxAngleDeg = maxAngleDeg; twistTurnDegPerSec = turnDegPerSec; twistWavelength = wavelength
    }

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
        wobbleAmplitude = wobbleAmplitude,
        wobbleWavelength = wobbleWavelength,
        wobbleScroll = wobbleScroll,
        wobblePhase = wobblePhase,
        lifetimeSeconds = lifetimeSeconds,
        dissolveStart = dissolveStart,
        twistMaxAngleDeg = twistMaxAngleDeg,
        twistWavelength = twistWavelength,
        twistTurnDegPerSec = twistTurnDegPerSec,
    )
}

/** 生命周期策略：飞行时长/溶解起点比/弹头尺寸倍率/布局参考宽度/拖尾锚点前移。 */
@ProjectileVfxDslMarker
class LifecycleBuilder {
    var durationSeconds = 1.25f; private set
    var dissolveStartRatio = 0.6f; private set
    var headSizeScale = 1.5f; private set
    var layoutReferenceWidth = 1280f; private set
    var headLeadWorld: Float? = null; private set

    fun duration(v: Float) { durationSeconds = v }
    fun dissolveAt(v: Float) { dissolveStartRatio = v }
    fun headScale(v: Float) { headSizeScale = v }
    fun layoutRef(v: Float) { layoutReferenceWidth = v }

    /** 拖尾锚点前移量（世界单位）：不调用 = 自动取弹体 spec.length/2（对齐原版螺栓视觉头部）；0 = 锚回弹体中心。 */
    fun headLead(v: Float) { headLeadWorld = v }
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

/** BoxUtil 光斑（lens-flare）：尺寸/双色/形态/朝向偏移/闪烁速度/锚点偏移。 */
@ProjectileVfxDslMarker
class BoxFlareBuilder {
    private var width = 120f
    private var height = 14f
    private var coreColor = rgba(0xFFFFFFFFL)
    private var fringeColor = rgba(0x99D9FFFFL)
    private var glowPower = 1f
    private var discRatio = 4f
    private var flickerRate = 1.2f
    private var noisePower = 0.1f
    private var style = BoxFlareStyle.SMOOTH_DISC
    private var facingOffsetDeg = 0f
    private var fixedFacingDeg: Float? = null
    private var offsetX = 0f

    /** 光斑全尺寸（世界单位）：w 沿朝向、h 横向；w >> h 即水平光条。 */
    fun size(w: Float, h: Float) { width = w; height = h }

    /** 核心/边缘色（0xRRGGBBAA）。 */
    fun colors(core: Long, fringe: Long) { coreColor = rgba(core); fringeColor = rgba(fringe) }

    /** bloom 强度（0..1+）与盘厚（越大越薄）。 */
    fun glow(power: Float, discRatio: Float = 4f) { glowPower = power; this.discRatio = discRatio }

    /** 闪烁速度倍率（1 = BoxUtil 默认；0/负值不合法，取 >0）。 */
    fun flicker(rate: Float) { flickerRate = rate.coerceAtLeast(0.05f) }

    /** 边缘噪点强度（0 = 关闭）。 */
    fun noise(power: Float) { noisePower = power.coerceAtLeast(0f) }

    /** 光斑形态（如 [BoxFlareStyle.SHARP] 锐边 streak）与朝向偏移（度；90 = 垂直于飞行方向的横向亮条）。 */
    fun style(s: BoxFlareStyle, facingOffsetDeg: Float = 0f) {
        style = s; this.facingOffsetDeg = facingOffsetDeg
    }

    /** 固定世界朝向（度；设置后忽略宿主 facing 与朝向偏移，0 = 恒水平）。 */
    fun fixedFacing(deg: Float) { fixedFacingDeg = deg }

    /** 局部 x 偏移（负 = 向尾）：headLead 前移锚点后用 -headLead 锚回弹体中心。 */
    fun offset(v: Float) { offsetX = v }

    internal fun build(): BoxFlareSpec = BoxFlareSpec(
        width = width,
        height = height,
        coreColor = coreColor,
        fringeColor = fringeColor,
        glowPower = glowPower,
        discRatio = discRatio,
        flickerRate = flickerRate,
        noisePower = noisePower,
        style = style,
        facingOffsetDeg = facingOffsetDeg,
        fixedFacingDeg = fixedFacingDeg,
        offsetX = offsetX,
    )
}

/** 锚点电弧（原版 EMP 电弧：发射点 → 弹体头部，固定两端拉伸）：粗细/边缘色/核心色/重铺间隔。 */
@ProjectileVfxDslMarker
class AnchorArcBuilder {
    private var thickness = 10f
    private var fringeColor = rgba(0x78BEFFC0L)
    private var coreColor = rgba(0xF0F8FFF0L)
    private var respawnSeconds = 0.1f

    /** 电弧粗细（世界单位，spawnEmpArcVisual thickness）。 */
    fun thickness(v: Float) { thickness = v.coerceAtLeast(0.1f) }

    /** 边缘色 / 核心色（0xRRGGBBAA）。 */
    fun colors(fringe: Long, core: Long) { fringeColor = rgba(fringe); coreColor = rgba(core) }

    /** 重铺间隔（秒）：原版电弧自带折线噪声与明灭闪烁，按本间隔重铺保持连续观感。 */
    fun respawn(seconds: Float) { respawnSeconds = seconds.coerceAtLeast(0.01f) }

    internal fun build(): AnchorArcSpec = AnchorArcSpec(
        thickness = thickness,
        fringeColor = fringeColor,
        coreColor = coreColor,
        respawnSeconds = respawnSeconds,
    )
}
