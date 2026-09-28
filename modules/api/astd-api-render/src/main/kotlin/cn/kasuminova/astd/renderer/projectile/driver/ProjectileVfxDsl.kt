package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.AnchorArcSpec
import cn.kasuminova.astd.impl.render.BoltSpec
import cn.kasuminova.astd.impl.render.BoxFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.impl.render.MachRingSpec
import cn.kasuminova.astd.impl.render.ShardWakeSpec
import cn.kasuminova.astd.impl.render.SpriteBodySpec
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
    /**
     * 弹体本体贴图层（BoxUtil SpriteEntity 逐帧跟随，normal alpha，取代原版弹体贴图渲染）；
     * null = 不渲染（默认）。用于有实体贴图的弹体（如冰晶碎片），.proj 侧须以 `BUtil_NONE.png` 屏蔽原版贴图。
     */
    val spriteBody: SpriteBodySpec?,
    /** BoxUtil 光斑层（名称 → spec）。 */
    val boxFlares: List<Pair<String, BoxFlareSpec>>,
    /** 锚点电弧层（名称 → spec）。 */
    val anchorArcs: List<Pair<String, AnchorArcSpec>>,
    /** 三角碎片航迹发射器层（名称 → spec）：持续型，弹体 Active 期间按节拍喷碎片。 */
    val shardWakes: List<Pair<String, ShardWakeSpec>> = emptyList(),
    /** 马赫环航迹发射器层（名称 → spec）：持续型，弹体 Active 期间按节拍留环。 */
    val machRings: List<Pair<String, MachRingSpec>> = emptyList(),
)

/**
 * 弹体特效的**唯一作者面**：手写 DSL 直接产出场景树蓝图 + 驱动策略。
 * 一个 [projectileVfx] 块内：`bolt` 定制 Box 螺栓弹头（默认开启，取代原版螺栓渲染），
 * `staticTrail` 声明 Static Trail 拖尾主体层（可多条叠层，BoxUtil 托管），
 * `boxFlare`/`anchorArc`/`onFire` 声明附加层，`shardWake`/`machRing` 声明持续发射器层
 * （三角碎片航迹/马赫环，统一粒子池渲染），`lifecycle`/`fade` 声明驱动策略。
 *
 * 本 DSL 只负责把作者旋钮折成渲染器所需的层 spec（[ProjectileVfxTreeSpec] 纯数据蓝图）；
 * 场景树组装在渲染实现侧（astd-render 的 `ProjectileVfxTreeAssembler`）。
 * 每次生成弹体都重新调用构建函数（不缓存），以支持调试期字面量热交换（见设计 §7）。
 */
class ProjectileVfx(
    val tree: ProjectileVfxTreeSpec,
    val policy: ProjectileVfxDriverPolicy,
    /** 发射瞬间附加动作列表（如炮口锥面冲击、发射点扭曲特效），按登记序执行；空则无事发生。 */
    val onFire: List<ProjectileVfxOnFireHook> = emptyList(),
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
    private val shardWakes = ArrayList<Pair<String, ShardWakeSpec>>()
    private val machRings = ArrayList<Pair<String, MachRingSpec>>()
    private val onFireHooks = ArrayList<ProjectileVfxOnFireHook>()

    /** Box 螺栓弹头：默认开启（取代原版螺栓渲染）；`bolt { off() }` 关闭（如导弹弹体）。 */
    private var bolt: BoltBuilder? = BoltBuilder()

    /** 弹体本体贴图层（默认不渲染；`spriteBody(...)` 开启，取代原版弹体贴图渲染）。 */
    private var spriteBody: SpriteBodyBuilder? = null

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

    /**
     * 弹体本体贴图层（BoxUtil SpriteEntity 逐帧跟随弹体 location/facing，normal alpha，
     * 对齐原版 Missile.render 弹体贴图语义含熄火淡出）：用于有实体贴图的弹体（如冰晶碎片），
     * .proj 侧须以 `sprite=BUtil_NONE.png` 屏蔽原版贴图渲染。贴图约定：文件右（+u）= 飞行正向。
     *
     * @param width 世界全宽（su，沿飞行向长度，对齐 .proj size[0]）。
     * @param height 世界全高（su，横向宽度，对齐 .proj size[1]）。
     */
    fun spriteBody(texturePath: String, width: Float, height: Float, block: SpriteBodyBuilder.() -> Unit = {}) {
        spriteBody = SpriteBodyBuilder(texturePath, width, height).apply(block)
    }

    /** 拉一条原版 EMP 锚点电弧：发射点（attach 时捕获的固定位置）→ 弹体头部（每帧跟随），随弹体生命周期存续。 */
    fun anchorArc(name: String, block: AnchorArcBuilder.() -> Unit) {
        anchorArcs += name to AnchorArcBuilder().apply(block).build()
    }

    /** 三角碎片航迹发射器：弹体飞行中按节拍喷一撮同色三角碎片（统一粒子池渲染），弹体消亡即停喷。 */
    fun shardWake(name: String, block: ShardWakeBuilder.() -> Unit) {
        shardWakes += name to ShardWakeBuilder().apply(block).build()
    }

    /** 马赫环航迹发射器：弹体飞行中按节拍在弹体当前位置留一枚拍扁椭圆环（统一粒子池渲染），环不跟弹、自然寿终。 */
    fun machRing(name: String, block: MachRingBuilder.() -> Unit) {
        machRings += name to MachRingBuilder().apply(block).build()
    }

    /** 发射瞬间附加动作（如炮口锥面冲击、发射点扭曲特效）：登记弹体成功后由分发器各调用一次，可登记多个。 */
    fun onFire(hook: ProjectileVfxOnFireHook) {
        onFireHooks += hook
    }

    fun lifecycle(block: LifecycleBuilder.() -> Unit) {
        lifecycle.apply(block)
    }

    fun fade(block: FadeBuilder.() -> Unit) {
        fade.apply(block)
    }

    internal fun build(): ProjectileVfx {
        val boltSpec = bolt?.build()
        val spriteBodySpec = spriteBody?.build()
        if (boltSpec == null && spriteBodySpec == null && staticTrails.isEmpty() && boxFlares.isEmpty() && anchorArcs.isEmpty()
            && shardWakes.isEmpty() && machRings.isEmpty()
        ) {
            throw IllegalStateException("projectileVfx '$id' 未声明任何特效层（bolt/spriteBody/staticTrail/boxFlare/anchorArc/shardWake/machRing 至少一个）")
        }

        val headLead = lifecycle.headLeadWorld
        val treeSpec = ProjectileVfxTreeSpec(
            id = id,
            // headLead 统一盖印到每条拖尾层：tracker 锚点与树原点（光斑锚）保持一致
            staticTrails = staticTrails.map { (name, spec) -> name to spec.copy(headLeadWorld = headLead) },
            bolt = boltSpec,
            spriteBody = spriteBodySpec,
            boxFlares = boxFlares.toList(),
            anchorArcs = anchorArcs.toList(),
            shardWakes = shardWakes.toList(),
            machRings = machRings.toList(),
        )

        val policy = ProjectileVfxDriverPolicy(
            hitFadeOutSeconds = fade.hitSeconds ?: fade.outSeconds,
            expireFadeOutSeconds = fade.expireSeconds ?: fade.outSeconds,
            removedFadeOutSeconds = fade.outSeconds,
            headLeadWorld = headLead,
        )
        return ProjectileVfx(treeSpec, policy, onFireHooks.toList())
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
    private var allowMissile = false
    private var lengthOverride: Float? = null
    private var widthOverride: Float? = null

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

    /**
     * 接管导弹弹体（默认关闭：MissileAPI 保留原版贴图渲染，组件 attach 时自禁用）。
     * 开启时原版贴图须另行屏蔽，且必须用 [size] 显式给尺寸（导弹 spec 无 length/width 键）；
     * 导弹无 TrailExtender 尾迹真值，螺栓按全长渲染、无出生伸入。
     */
    fun onMissile() {
        allowMissile = true
    }

    /** 螺栓尺寸显式覆盖（su）：length=全长（飞行向），width=全宽（横向）；省略 = 读弹体 spec 的 length/width。 */
    fun size(length: Float, width: Float) {
        lengthOverride = length
        widthOverride = width
    }

    internal fun build(): BoltSpec = BoltSpec(
        texturePath = texturePath,
        color = color,
        allowMissile = allowMissile,
        lengthOverride = lengthOverride,
        widthOverride = widthOverride,
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

/**
 * 弹体本体贴图层构建器（DSL `spriteBody(...)`）：贴图/尺寸为构造参数，这里只有 bloom 发光一个旋钮。
 */
@ProjectileVfxDslMarker
class SpriteBodyBuilder(
    private val texturePath: String,
    private val width: Float,
    private val height: Float,
) {
    private var glowPower = 0f

    /** bloom 发光强度（0 = 不发光；>0 时 emissive 复用本体 diffuse 贴图原色发光，进 bloom G-buffer）。 */
    fun glow(power: Float) {
        glowPower = power.coerceAtLeast(0f)
    }

    internal fun build(): SpriteBodySpec = SpriteBodySpec(
        texturePath = texturePath,
        width = width,
        height = height,
        glowPower = glowPower,
    )
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


/** 三角碎片航迹发射器构建器（DSL `shardWake(name){}`）：节拍/散布/速度域/张角 + 碎片外观旋钮。 */
@ProjectileVfxDslMarker
class ShardWakeBuilder {
    private var interval = 0.05f
    private var perTick = 5
    private var scatterRadius = 8f
    private var speedMin = 100f
    private var speedMax = 150f
    private var spreadDeg = 8f
    private var shardLength = 34f
    private var coreColor = rgba(0xF0F8FFFFL)
    private var fringeColor = rgba(0x78BEFFFFL)
    private var sizeMul = 0.2f
    private var sizeMin = 4f
    private var sizeMax = 9f
    private var spinMin = 90f
    private var spinMax = 360f
    private var alphaLo = 120
    private var alphaHi = 180
    private var timerFullLo = 0.15f
    private var timerFullHi = 0.3f
    private var timerFadeOut = 0.3f

    /** 发射节拍（秒）与每节拍颗数。 */
    fun cadence(intervalSeconds: Float, count: Int) {
        interval = intervalSeconds; perTick = count
    }

    /** 发射行为：散布半径 / 初速域 / 飞行方向张角（±度）。 */
    fun emission(scatterRadius: Float, speedMin: Float, speedMax: Float, spreadDeg: Float) {
        this.scatterRadius = scatterRadius
        this.speedMin = speedMin
        this.speedMax = speedMax
        this.spreadDeg = spreadDeg
    }

    /** 尺寸基准长度（世界单位）：碎片边长 = clamp(本值×sizeMul, sizeMin, sizeMax) × 抖动。 */
    fun shardLength(v: Float) {
        shardLength = v
    }

    /** 提亮色 / 底色（0xRRGGBBAA）。 */
    fun colors(core: Long, fringe: Long) {
        coreColor = rgba(core); fringeColor = rgba(fringe)
    }

    /** 碎片外观微调（尺寸域/自旋域/alpha 域/寿命域），默认值即坠星残响航迹观感。 */
    fun shard(
        sizeMul: Float = this.sizeMul, sizeMin: Float = this.sizeMin, sizeMax: Float = this.sizeMax,
        spinMin: Float = this.spinMin, spinMax: Float = this.spinMax,
        alphaLo: Int = this.alphaLo, alphaHi: Int = this.alphaHi,
        timerFullLo: Float = this.timerFullLo, timerFullHi: Float = this.timerFullHi,
        timerFadeOut: Float = this.timerFadeOut,
    ) {
        this.sizeMul = sizeMul; this.sizeMin = sizeMin; this.sizeMax = sizeMax
        this.spinMin = spinMin; this.spinMax = spinMax
        this.alphaLo = alphaLo; this.alphaHi = alphaHi
        this.timerFullLo = timerFullLo; this.timerFullHi = timerFullHi; this.timerFadeOut = timerFadeOut
    }

    internal fun build(): ShardWakeSpec = ShardWakeSpec(
        interval = interval,
        perTick = perTick,
        scatterRadius = scatterRadius,
        speedMin = speedMin,
        speedMax = speedMax,
        spreadDeg = spreadDeg,
        shardLength = shardLength,
        coreColor = coreColor,
        fringeColor = fringeColor,
        sizeMul = sizeMul,
        sizeMin = sizeMin,
        sizeMax = sizeMax,
        spinMin = spinMin,
        spinMax = spinMax,
        alphaLo = alphaLo,
        alphaHi = alphaHi,
        timerFullLo = timerFullLo,
        timerFullHi = timerFullHi,
        timerFadeOut = timerFadeOut,
    )
}

/** 马赫环航迹发射器构建器（DSL `machRing(name){}`）：节拍/半径/染色 + 形态包络旋钮。 */
@ProjectileVfxDslMarker
class MachRingBuilder {
    private var interval = 0.5f
    private var halfSize = 35f
    private var color = rgba(0x78BEFFFFL)
    private var alpha = 0.6f
    private var flatten = 0.45f
    private var growthStart = 0.7f
    private var growthEnd = 1.6f
    private var fadeIn = 0.06f
    private var full = 0.5f
    private var fadeOut = 0.44f
    private var texturePath = "graphics/fx/astd_generated_ring.png"
    private var speedMin = 0f
    private var speedMax = 0f

    /** 发射节拍（秒）与环基准半径（世界半尺寸）。 */
    fun cadence(intervalSeconds: Float, halfSize: Float) {
        interval = intervalSeconds; this.halfSize = halfSize
    }

    /** 环染色（0xRRGGBBAA）与基准透明度 0..1。 */
    fun color(hex: Long, alpha: Float = this.alpha) {
        color = rgba(hex); this.alpha = alpha.coerceIn(0f, 1f)
    }

    /** 环贴图路径（256×256 旋转对称圆环，形在 alpha）。 */
    fun texture(path: String) {
        texturePath = path
    }

    /** 形态与寿命包络：横向拍扁比 / 出生→寿终尺寸倍率 / 三段时长（秒）。 */
    fun shape(
        flatten: Float = this.flatten,
        growthStart: Float = this.growthStart, growthEnd: Float = this.growthEnd,
        fadeIn: Float = this.fadeIn, full: Float = this.full, fadeOut: Float = this.fadeOut,
    ) {
        this.flatten = flatten
        this.growthStart = growthStart; this.growthEnd = growthEnd
        this.fadeIn = fadeIn; this.full = full; this.fadeOut = fadeOut
    }

    /** 前飞动量（世界单位/秒，方向 = 发射瞬间弹体 facing，逐枚在 [speedMin, speedMax] 随机）；不传 = 锚在原地。 */
    fun momentum(speedMin: Float, speedMax: Float = speedMin) {
        this.speedMin = speedMin; this.speedMax = speedMax
    }

    internal fun build(): MachRingSpec = MachRingSpec(
        interval = interval,
        halfSize = halfSize,
        color = color,
        alpha = alpha,
        flatten = flatten,
        growthStart = growthStart,
        growthEnd = growthEnd,
        fadeIn = fadeIn,
        full = full,
        fadeOut = fadeOut,
        texturePath = texturePath,
        speedMin = speedMin,
        speedMax = speedMax,
    )
}
