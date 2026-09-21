package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.AnchorArcSpec
import cn.kasuminova.astd.impl.render.BoltSpec
import cn.kasuminova.astd.impl.render.BoxFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.impl.render.StaticTrailSpec
import cn.kasuminova.astd.impl.render.TrailDriftRange
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI

/** 弹体发射瞬间的附加动作钩子（如发射点扭曲特效）；由分发器在登记弹体成功后调用一次。 */
fun interface ProjectileVfxOnFireHook {
    fun onFire(engine: CombatEngineAPI, projectile: DamagingProjectileAPI)
}

/**
 * 弹体特效场景树**蓝图**：DSL 产出的纯数据（各层 spec 列表），不含任何渲染组件。
 * 由渲染实现侧（astd-render 的 `ProjectileVfxTreeAssembler`）组装为 RenderEntity 场景树。
 */
class ProjectileVfxTreeSpec(
    val id: String,
    /** Static Trail 拖尾主体层（名称 → spec），按声明顺序叠层；由 BoxUtil Static Trail 系统托管渲染。 */
    val staticTrails: List<Pair<String, StaticTrailSpec>>,
    /**
     * Box 螺栓弹头层（取代原版 ProjectileRenderer 螺栓渲染）；null = 不渲染（`bolt { off() }`，
     * 如导弹等由原版弹体贴图承担的弹体）。
     */
    val bolt: BoltSpec?,
    /** BoxUtil 光斑层（名称 → spec）。 */
    val boxFlares: List<Pair<String, BoxFlareSpec>>,
    /** 锚点电弧层（名称 → spec）。 */
    val anchorArcs: List<Pair<String, AnchorArcSpec>>,
)

/**
 * 弹体特效的**唯一作者面**：手写 DSL 直接产出场景树蓝图 + 驱动策略。
 * 一个 [projectileVfx] 块内：`bolt` 定制 Box 螺栓弹头（默认开启，取代原版螺栓渲染），
 * `staticTrail` 声明 Static Trail 拖尾主体层（可多条叠层，BoxUtil 托管），
 * `boxFlare`/`anchorArc`/`onFire` 声明附加层，`lifecycle`/`fade` 声明驱动策略。
 *
 * 本 DSL 只负责把作者旋钮折成渲染器所需的层 spec（[ProjectileVfxTreeSpec] 纯数据蓝图）；
 * 场景树组装在渲染实现侧（astd-render 的 `ProjectileVfxTreeAssembler`）。
 * 每次生成弹体都重新调用构建函数（不缓存），以支持调试期字面量热交换（见设计 §7）。
 */
class ProjectileVfx(
    val tree: ProjectileVfxTreeSpec,
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
 * DSL 作用域：收集 Static Trail 拖尾层、附加层与策略，[build] 时组装成 [ProjectileVfx]。
 * 节点组装推迟到 [build]，故组件块的书写先后无关。
 */
@ProjectileVfxDslMarker
class ProjectileVfxScope(private val id: String) {

    private val staticTrails = ArrayList<Pair<String, StaticTrailSpec>>()
    private val boxFlares = ArrayList<Pair<String, BoxFlareSpec>>()
    private val anchorArcs = ArrayList<Pair<String, AnchorArcSpec>>()
    private var onFireHook: ProjectileVfxOnFireHook? = null

    /** Box 螺栓弹头：默认开启（取代原版螺栓渲染）；`bolt { off() }` 关闭（如导弹弹体）。 */
    private var bolt: BoltBuilder? = BoltBuilder()

    private val lifecycle = LifecycleBuilder()
    private val fade = FadeBuilder()

    /**
     * 叠加一条 Static Trail 拖尾主体层（BoxUtil 1.6.0 托管：GPU 实例化带体 + 环形 vRAM 池 +
     * 三段时长生命），可多次调用按 [StaticTrailBuilder.layer] 叠层。
     */
    fun staticTrail(name: String, texturePath: String, block: StaticTrailBuilder.() -> Unit) {
        staticTrails += name to StaticTrailBuilder(texturePath).apply(block).build()
    }

    /**
     * 定制 Box 螺栓弹头层（SpriteEntity 双趟 additive，取代原版螺栓渲染；默认即开启）。
     * 不调用本方法 = 默认近白螺栓；`bolt { off() }` 关闭（导弹等原版贴图弹体）。
     */
    fun bolt(block: BoltBuilder.() -> Unit) {
        val builder = bolt ?: BoltBuilder()
        builder.apply(block)
        bolt = if (builder.isOff) null else builder
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
    fun onFire(hook: ProjectileVfxOnFireHook) {
        onFireHook = hook
    }

    fun lifecycle(block: LifecycleBuilder.() -> Unit) {
        lifecycle.apply(block)
    }

    fun fade(block: FadeBuilder.() -> Unit) {
        fade.apply(block)
    }

    internal fun build(): ProjectileVfx {
        val boltSpec = bolt?.build()
        if (boltSpec == null && staticTrails.isEmpty() && boxFlares.isEmpty() && anchorArcs.isEmpty()) {
            throw IllegalStateException("projectileVfx '$id' 未声明任何特效层（bolt/staticTrail/boxFlare/anchorArc 至少一个）")
        }

        val headLead = lifecycle.headLeadWorld
        val treeSpec = ProjectileVfxTreeSpec(
            id = id,
            // headLead 统一盖印到每条拖尾层：tracker 锚点与树原点（光斑锚）保持一致
            staticTrails = staticTrails.map { (name, spec) -> name to spec.copy(headLeadWorld = headLead) },
            bolt = boltSpec,
            boxFlares = boxFlares.toList(),
            anchorArcs = anchorArcs.toList(),
        )

        val policy = ProjectileVfxDriverPolicy(
            hitFadeOutSeconds = fade.hitSeconds ?: fade.outSeconds,
            expireFadeOutSeconds = fade.expireSeconds ?: fade.outSeconds,
            removedFadeOutSeconds = fade.outSeconds,
            headLeadWorld = headLead,
        )
        return ProjectileVfx(treeSpec, policy, onFireHook)
    }
}

/**
 * Static Trail 拖尾层（BoxUtil 托管）：GPU 实例化带体 + 平铺滚动贴图图案。
 * 贴图约定同 astd_trails_*：X=带长向（REPEAT 平铺）、Y=横向，形在 alpha 通道、RGB 近白。
 */
@ProjectileVfxDslMarker
class StaticTrailBuilder(private val texturePath: String) {
    private var layer = 1
    private var width = 12f
    private var tailWidthRatio = 0.35f
    private var headColor = rgba(0xFFFFFFEBL)
    private var tailColor = rgba(0x0A1C380FL)
    private var bandLength = 180f
    private var tileLength = 180f
    private var scrollSpeed = 0f
    private var recede: Float? = null
    private var glowPower = 0f
    private var angularInRange: ClosedFloatingPointRange<Float>? = null
    private var angularOutRange: ClosedFloatingPointRange<Float>? = null
    private var velocityInRange: TrailDriftRange? = null
    private var velocityOutRange: TrailDriftRange? = null

    /** 叠层序号：同弹体多条拖尾的组织序（1 垫底、2 其上；additive 混合下不参与绘制排序）。 */
    fun layer(v: Int) {
        layer = v
    }

    /** 拖尾头部全宽（世界单位）；尾部宽度 = 本值 × [tailWidthRatio]，随生命线性收细。 */
    fun width(v: Float) {
        width = v
    }

    /** 尾宽比（0..1）。 */
    fun tailWidth(v: Float) {
        tailWidthRatio = v.coerceIn(0f, 1f)
    }

    /** 头尾颜色（0xRRGGBBAA）：头部亮端 → 尾部暗端，随节点生命两段渐变。 */
    fun colors(head: Long, tail: Long) {
        headColor = rgba(head); tailColor = rgba(tail)
    }

    /** 预期带长（世界单位）：节点总寿命 = 带长 / 弹体速度，三段时长（淡入/全亮/消散）按比例切分。 */
    fun length(v: Float) {
        bandLength = v
    }

    /** 图案平铺周期（世界单位）与滚动速度（世界单位/秒，0 不滚动）。 */
    fun tile(length: Float, scroll: Float) {
        tileLength = length; scrollSpeed = scroll
    }

    /** 带体整体向后退的距离（世界单位）：带体头部亮端退到螺栓弹头之后，让弹头尖在带体前露出。不调用 = 自动取弹体长度 ×0.75（运行期解析）。 */
    fun recede(v: Float) {
        recede = v
    }

    /** bloom 发光强度（0..1；进 BoxUtil emissive → bloom G-buffer）。不调用即不发光（原版螺栓无辉光）。 */
    fun glow(power: Float) {
        glowPower = power.coerceIn(0f, 1f)
    }

    /** 头部（最新节点）每节点随机自旋角速度范围（度/秒，绕节点锚点）。默认 ±15 的轻扭转。 */
    fun angularIn(min: Float = -15f, max: Float = 15f) {
        angularInRange = min..max
    }

    /** 尾部（最老节点）每节点随机自旋角速度范围（度/秒）：带尾随存活时间扭转出弧度，默认 ±45 可见卷曲。 */
    fun angularOut(min: Float = -45f, max: Float = 45f) {
        angularOutRange = min..max
    }

    /** 头部（最新节点）每节点随机漂移速度范围（世界单位/秒，基于带体朝向）。 */
    fun velocityIn(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        velocityInRange = TrailDriftRange(minX, minY, maxX, maxY)
    }

    /** 尾部（最老节点）每节点随机漂移速度范围（世界单位/秒）：带尾随存活时间漂离原航迹。 */
    fun velocityOut(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        velocityOutRange = TrailDriftRange(minX, minY, maxX, maxY)
    }

    internal fun build(): StaticTrailSpec = StaticTrailSpec(
        texturePath = texturePath,
        layer = layer,
        width = width,
        tailWidthRatio = tailWidthRatio,
        headColor = headColor,
        tailColor = tailColor,
        bandLength = bandLength,
        tileLength = tileLength,
        scrollSpeed = scrollSpeed,
        recede = recede,
        glowPower = glowPower,
        angularInRange = angularInRange,
        angularOutRange = angularOutRange,
        velocityInRange = velocityInRange,
        velocityOutRange = velocityOutRange,
    )
}

/** Box 螺栓弹头层（SpriteEntity 双趟 additive，烘焙版彗形贴图）：贴图/染色/关闭。 */
@ProjectileVfxDslMarker
class BoltBuilder {
    internal var isOff = false; private set
    private var texturePath = BoltSpec.DEFAULT_TEXTURE
    private var color = rgba(0xFFFFFFC8L)

    /** 关闭 Box 螺栓弹头（弹体视觉由其它路径承担，如原版导弹贴图）。 */
    fun off() {
        isOff = true
    }

    /** 弹头贴图路径（彗形白图，渐隐/收窄已烘焙进 alpha；X=飞行向，头在贴图左侧）。 */
    fun texture(path: String) {
        texturePath = path
    }

    /** 弹头染色（0xRRGGBBAA，原版 coreColor 语义，通常近白；alpha 参与亮度乘算）。 */
    fun color(color: Long) {
        this.color = rgba(color)
    }

    internal fun build(): BoltSpec = BoltSpec(
        texturePath = texturePath,
        color = color,
    )
}

/** 生命周期策略：拖尾锚点前移（其余运行期生命周期已由 Static Trail 系统接管）。 */
@ProjectileVfxDslMarker
class LifecycleBuilder {
    var headLeadWorld: Float? = null; private set

    /** 拖尾锚点前移量（世界单位）：不调用 = 0（弹体前端 location 即螺栓视觉头部，无需再前移）；正值继续向前探。 */
    fun headLead(v: Float) {
        headLeadWorld = v
    }
}

/** 淡出策略：默认淡出秒数 + 命中/过期各自秒数（省略则同默认）；作用于树内附加层（光斑等）。 */
@ProjectileVfxDslMarker
class FadeBuilder {
    var outSeconds = 0.15f; private set
    var hitSeconds: Float? = null; private set
    var expireSeconds: Float? = null; private set

    fun out(v: Float) {
        outSeconds = v
    }

    fun hit(v: Float) {
        hitSeconds = v
    }

    fun expire(v: Float) {
        expireSeconds = v
    }
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
    private var flick = false
    private var flickerRate = 1.2f
    private var noisePower = 0.1f
    private var style = BoxFlareStyle.SMOOTH_DISC
    private var facingOffsetDeg = 0f
    private var fixedFacingDeg: Float? = null
    private var offsetX = 0f

    /** 光斑全尺寸（世界单位）：w 沿朝向、h 横向；w >> h 即水平光条。 */
    fun size(w: Float, h: Float) {
        width = w; height = h
    }

    /** 核心/边缘色（0xRRGGBBAA）。 */
    fun colors(core: Long, fringe: Long) {
        coreColor = rgba(core); fringeColor = rgba(fringe)
    }

    /** bloom 强度（0..1+）与盘厚（越大越薄）。 */
    fun glow(power: Float, discRatio: Float = 4f) {
        glowPower = power; this.discRatio = discRatio
    }

    /** 开启闪烁（宽度脉动 + 明灭同步，默认速度倍率 1.2）；不调用即不闪烁。 */
    fun flicker() {
        flick = true
    }

    /** 开启闪烁并设速度倍率（1 = BoxUtil 默认；0/负值不合法，取 >0）。 */
    fun flicker(rate: Float) {
        flick = true; flickerRate = rate.coerceAtLeast(0.05f)
    }

    /** 边缘噪点强度（0 = 关闭）。 */
    fun noise(power: Float) {
        noisePower = power.coerceAtLeast(0f)
    }

    /** 光斑形态（如 [BoxFlareStyle.SHARP] 锐边 streak）与朝向偏移（度；90 = 垂直于飞行方向的横向亮条）。 */
    fun style(s: BoxFlareStyle, facingOffsetDeg: Float = 0f) {
        style = s; this.facingOffsetDeg = facingOffsetDeg
    }

    /** 固定世界朝向（度；设置后忽略宿主 facing 与朝向偏移，0 = 恒水平）。 */
    fun fixedFacing(deg: Float) {
        fixedFacingDeg = deg
    }

    /** 局部 x 偏移（负 = 向尾）：光斑相对树锚点（弹体前端提前 headLead 处）的偏移。 */
    fun offset(v: Float) {
        offsetX = v
    }

    internal fun build(): BoxFlareSpec = BoxFlareSpec(
        width = width,
        height = height,
        coreColor = coreColor,
        fringeColor = fringeColor,
        glowPower = glowPower,
        discRatio = discRatio,
        flick = flick,
        flickerRate = flickerRate,
        noisePower = noisePower,
        style = style,
        facingOffsetDeg = facingOffsetDeg,
        fixedFacingDeg = fixedFacingDeg,
        offsetX = offsetX,
    )
}

/** 锚点电弧（原版 EMP 一次性电弧：发射点固定锚 → 弹体中心实时拉伸，只生成一次）：粗细/边缘色/核心色。 */
@ProjectileVfxDslMarker
class AnchorArcBuilder {
    private var thickness = 10f
    private var fringeColor = rgba(0x78BEFFC0L)
    private var coreColor = rgba(0xF0F8FFF0L)

    /** 电弧粗细（世界单位，spawnEmpArcVisual thickness）。 */
    fun thickness(v: Float) {
        thickness = v.coerceAtLeast(0.1f)
    }

    /** 边缘色 / 核心色（0xRRGGBBAA）。 */
    fun colors(fringe: Long, core: Long) {
        fringeColor = rgba(fringe); coreColor = rgba(core)
    }

    internal fun build(): AnchorArcSpec = AnchorArcSpec(
        thickness = thickness,
        fringeColor = fringeColor,
        coreColor = coreColor,
    )
}
