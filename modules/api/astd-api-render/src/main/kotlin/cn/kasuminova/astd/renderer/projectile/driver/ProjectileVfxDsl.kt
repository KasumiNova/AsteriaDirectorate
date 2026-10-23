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

/** 弹体登记成功后执行一次的发射动作。仅在战斗主线程调用。 */
fun interface ProjectileVfxOnFireHook {
    fun onFire(engine: CombatEngineAPI, projectile: DamagingProjectileAPI)
}

/**
 * 弹体特效树的数据蓝图。渲染实现根据这些参数创建实体树。
 */
class ProjectileVfxTreeSpec(
    val id: String,
    /** Static Trail 拖尾层，保留声明顺序。 */
    val staticTrails: List<Pair<String, StaticTrailSpec>>,
    /** 能量螺栓弹头层；null 表示不渲染。 */
    val bolt: BoltSpec?,
    /** 弹体本体贴图层；null 表示不渲染。 */
    val spriteBody: SpriteBodySpec?,
    /** BoxUtil 光斑层。 */
    val boxFlares: List<Pair<String, BoxFlareSpec>>,
    /** 锚点电弧层。 */
    val anchorArcs: List<Pair<String, AnchorArcSpec>>,
    /** 三角碎片发射器层。 */
    val shardWakes: List<Pair<String, ShardWakeSpec>> = emptyList(),
    /** 马赫环发射器层。 */
    val machRings: List<Pair<String, MachRingSpec>> = emptyList(),
)

/**
 * 弹体特效蓝图与驱动策略，由 [projectileVfx] 构建。
 *
 * 构建过程只收集参数，不创建渲染实体。
 */
class ProjectileVfx(
    val tree: ProjectileVfxTreeSpec,
    val policy: ProjectileVfxDriverPolicy,
    /** 发射动作，按登记顺序执行。 */
    val onFire: List<ProjectileVfxOnFireHook> = emptyList(),
)

internal fun rgba(hex: Long): ASTDColor = ASTDColor(
    ((hex shr 24) and 0xFF) / 255f,
    ((hex shr 16) and 0xFF) / 255f,
    ((hex shr 8) and 0xFF) / 255f,
    (hex and 0xFF) / 255f,
)

@DslMarker
annotation class ProjectileVfxDslMarker

/**
 * 构建弹体特效蓝图。至少声明一个特效层，否则抛出 [IllegalStateException]。
 * 构建过程不创建渲染实体。
 */
@ProjectileVfxDslMarker
fun projectileVfx(id: String, block: ProjectileVfxScope.() -> Unit): ProjectileVfx =
    ProjectileVfxScope(id).apply(block).build()

/** 收集弹体特效层与驱动策略。 */
@ProjectileVfxDslMarker
class ProjectileVfxScope(private val id: String) {

    private val staticTrails = ArrayList<Pair<String, StaticTrailSpec>>()
    private val boxFlares = ArrayList<Pair<String, BoxFlareSpec>>()
    private val anchorArcs = ArrayList<Pair<String, AnchorArcSpec>>()
    private val shardWakes = ArrayList<Pair<String, ShardWakeSpec>>()
    private val machRings = ArrayList<Pair<String, MachRingSpec>>()
    private val onFireHooks = ArrayList<ProjectileVfxOnFireHook>()

    private var bolt: BoltBuilder? = BoltBuilder()
    private var spriteBody: SpriteBodyBuilder? = null

    private val lifecycle = LifecycleBuilder()
    private val fade = FadeBuilder()

    /** 添加 Static Trail 拖尾层，可多次调用。 */
    fun staticTrail(name: String, texturePath: String, block: StaticTrailBuilder.() -> Unit) {
        staticTrails += name to StaticTrailBuilder(texturePath).apply(block).build()
    }

    /** 配置能量螺栓弹头；弹头默认启用，可在 [block] 中调用 [BoltBuilder.off] 关闭。 */
    fun bolt(block: BoltBuilder.() -> Unit) {
        val builder = bolt ?: BoltBuilder()
        builder.apply(block)
        bolt = if (builder.isOff) null else builder
    }

    /** 添加跟随弹体头部的 BoxUtil 光斑。 */
    fun boxFlare(name: String, block: BoxFlareBuilder.() -> Unit) {
        boxFlares += name to BoxFlareBuilder().apply(block).build()
    }

    /**
     * 使用 BoxUtil SpriteEntity 渲染弹体本体。调用方必须屏蔽原版贴图并预加载 [texturePath]。
     *
     * @param width 沿飞行方向的全长，单位为 su
     * @param height 横向全宽，单位为 su
     */
    fun spriteBody(texturePath: String, width: Float, height: Float, block: SpriteBodyBuilder.() -> Unit = {}) {
        spriteBody = SpriteBodyBuilder(texturePath, width, height).apply(block)
    }

    /** 添加从发射位置延伸到弹体的 EMP 电弧。 */
    fun anchorArc(name: String, block: AnchorArcBuilder.() -> Unit) {
        anchorArcs += name to AnchorArcBuilder().apply(block).build()
    }

    /** 添加在弹体活动期间生成三角碎片的发射器。 */
    fun shardWake(name: String, block: ShardWakeBuilder.() -> Unit) {
        shardWakes += name to ShardWakeBuilder().apply(block).build()
    }

    /** 添加在弹体活动期间留下椭圆环的发射器。 */
    fun machRing(name: String, block: MachRingBuilder.() -> Unit) {
        machRings += name to MachRingBuilder().apply(block).build()
    }

    /** 登记弹体注册成功后执行一次的动作。 */
    fun onFire(hook: ProjectileVfxOnFireHook) {
        onFireHooks += hook
    }

    /** 配置锚点位置。 */
    fun lifecycle(block: LifecycleBuilder.() -> Unit) {
        lifecycle.apply(block)
    }

    /** 配置不同终止原因的淡出时长。 */
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
 * 配置 Static Trail 拖尾层。贴图 X 轴为带长方向并使用 REPEAT 平铺，alpha 描述形状，RGB 使用近白色。
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

    /** 设置同一弹体多条拖尾的组织序号。 */
    fun layer(v: Int) {
        layer = v
    }

    /** 设置拖尾头部全宽，尾部宽度为该值乘以尾宽比。 */
    fun width(v: Float) {
        width = v
    }

    /** 设置尾宽比，输入会限制在 0 至 1。 */
    fun tailWidth(v: Float) {
        tailWidthRatio = v.coerceIn(0f, 1f)
    }

    /** 设置头部与尾部颜色，格式为 0xRRGGBBAA。 */
    fun colors(head: Long, tail: Long) {
        headColor = rgba(head); tailColor = rgba(tail)
    }

    /** 设置预期带长，节点总寿命由带长除以弹体速度得到。 */
    fun length(v: Float) {
        bandLength = v
    }

    /** 设置图案平铺周期和滚动速度，单位分别为 su 和 su/s。 */
    fun tile(length: Float, scroll: Float) {
        tileLength = length; scrollSpeed = scroll
    }

    /** 设置带体向后偏移的距离；不调用时由渲染实现根据弹体长度计算。 */
    fun recede(v: Float) {
        recede = v
    }

    /** 设置 bloom 强度，输入会限制在 0 至 1。 */
    fun glow(power: Float) {
        glowPower = power.coerceIn(0f, 1f)
    }

    /** 设置最新节点的随机自旋角速度范围；必须同时调用 [velocityIn] 才会生效。 */
    fun angularIn(min: Float = -15f, max: Float = 15f) {
        angularInRange = min..max
    }

    /** 设置最旧节点的随机自旋角速度范围；必须同时调用 [velocityOut] 才会生效。 */
    fun angularOut(min: Float = -45f, max: Float = 45f) {
        angularOutRange = min..max
    }

    /** 设置最新节点的随机漂移速度范围，单位为 su/s。 */
    fun velocityIn(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        velocityInRange = TrailDriftRange(minX, minY, maxX, maxY)
    }

    /** 设置最旧节点的随机漂移速度范围，单位为 su/s。 */
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

/** 配置 BoxUtil 能量螺栓弹头。 */
@ProjectileVfxDslMarker
class BoltBuilder {
    internal var isOff = false; private set
    private var texturePath = BoltSpec.DEFAULT_TEXTURE
    private var color = rgba(0xFFFFFFC8L)
    private var allowMissile = false
    private var lengthOverride: Float? = null
    private var widthOverride: Float? = null

    /** 关闭能量螺栓弹头。 */
    fun off() {
        isOff = true
    }

    /** 设置弹头贴图，头端必须位于贴图右侧。 */
    fun texture(path: String) {
        texturePath = path
    }

    /** 设置弹头颜色，格式为 0xRRGGBBAA。 */
    fun color(color: Long) {
        this.color = rgba(color)
    }

    /**
     * 为导弹启用能量螺栓弹头。调用方必须屏蔽原版导弹贴图并通过 [size] 提供尺寸。
     */
    fun onMissile() {
        allowMissile = true
    }

    /** 设置螺栓全长与全宽；不调用时读取弹体规格。 */
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

/** 配置弹体特效锚点。 */
@ProjectileVfxDslMarker
class LifecycleBuilder {
    var headLeadWorld: Float? = null; private set

    /** 设置锚点沿弹体朝向前移的距离；不调用表示不偏移。 */
    fun headLead(v: Float) {
        headLeadWorld = v
    }
}

/** 配置附加特效层的淡出时长，单位为秒。 */
@ProjectileVfxDslMarker
class FadeBuilder {
    var outSeconds = 0.15f; private set
    var hitSeconds: Float? = null; private set
    var expireSeconds: Float? = null; private set

    /** 设置默认淡出时长。 */
    fun out(v: Float) {
        outSeconds = v
    }

    /** 设置命中后的淡出时长；不调用时使用默认值。 */
    fun hit(v: Float) {
        hitSeconds = v
    }

    /** 设置自然过期后的淡出时长；不调用时使用默认值。 */
    fun expire(v: Float) {
        expireSeconds = v
    }
}

/** 配置弹体本体贴图层。 */
@ProjectileVfxDslMarker
class SpriteBodyBuilder(
    private val texturePath: String,
    private val width: Float,
    private val height: Float,
) {
    private var glowPower = 0f

    /** 设置 bloom 强度，负数按 0 处理。 */
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

/** 配置 BoxUtil 光斑。 */
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

    /** 设置光斑全长与全宽，单位为 su。 */
    fun size(w: Float, h: Float) {
        width = w; height = h
    }

    /** 设置核心色与边缘色，格式为 0xRRGGBBAA。 */
    fun colors(core: Long, fringe: Long) {
        coreColor = rgba(core); fringeColor = rgba(fringe)
    }

    /** 设置 bloom 强度与盘厚比例，盘厚比例越大则光斑越薄。 */
    fun glow(power: Float, discRatio: Float = 4f) {
        glowPower = power; this.discRatio = discRatio
    }

    /** 开启闪烁。 */
    fun flicker() {
        flick = true
    }

    /** 开启闪烁并设置速度倍率，最小值为 0.05。 */
    fun flicker(rate: Float) {
        flick = true; flickerRate = rate.coerceAtLeast(0.05f)
    }

    /** 设置边缘噪点强度，0 表示关闭，负数按 0 处理。 */
    fun noise(power: Float) {
        noisePower = power.coerceAtLeast(0f)
    }

    /** 设置光斑形态与相对宿主朝向的角度偏移。 */
    fun style(s: BoxFlareStyle, facingOffsetDeg: Float = 0f) {
        style = s; this.facingOffsetDeg = facingOffsetDeg
    }

    /** 设置固定世界朝向，设置后忽略宿主朝向与角度偏移。 */
    fun fixedFacing(deg: Float) {
        fixedFacingDeg = deg
    }

    /** 设置光斑相对树锚点的纵向偏移，负值向弹尾移动。 */
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

/** 配置从固定发射位置延伸到弹体的 EMP 电弧。 */
@ProjectileVfxDslMarker
class AnchorArcBuilder {
    private var thickness = 10f
    private var fringeColor = rgba(0x78BEFFC0L)
    private var coreColor = rgba(0xF0F8FFF0L)

    /** 设置电弧粗细，最小值为 0.1 su。 */
    fun thickness(v: Float) {
        thickness = v.coerceAtLeast(0.1f)
    }

    /** 设置边缘色与核心色，格式为 0xRRGGBBAA。 */
    fun colors(fringe: Long, core: Long) {
        fringeColor = rgba(fringe); coreColor = rgba(core)
    }

    internal fun build(): AnchorArcSpec = AnchorArcSpec(
        thickness = thickness,
        fringeColor = fringeColor,
        coreColor = coreColor,
    )
}

/** 配置三角碎片发射器。 */
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

    /** 设置发射间隔与每次发射数量。 */
    fun cadence(intervalSeconds: Float, count: Int) {
        interval = intervalSeconds; perTick = count
    }

    /** 设置散布半径、初速范围与方向随机张角。 */
    fun emission(scatterRadius: Float, speedMin: Float, speedMax: Float, spreadDeg: Float) {
        this.scatterRadius = scatterRadius
        this.speedMin = speedMin
        this.speedMax = speedMax
        this.spreadDeg = spreadDeg
    }

    /** 设置用于计算碎片边长的基准长度。 */
    fun shardLength(v: Float) {
        shardLength = v
    }

    /** 设置提亮色与底色，格式为 0xRRGGBBAA。 */
    fun colors(core: Long, fringe: Long) {
        coreColor = rgba(core); fringeColor = rgba(fringe)
    }

    /** 设置碎片尺寸、自旋、透明度与寿命范围。 */
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

/** 配置马赫环发射器。 */
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

    /** 设置发射间隔与环的基准半径。 */
    fun cadence(intervalSeconds: Float, halfSize: Float) {
        interval = intervalSeconds; this.halfSize = halfSize
    }

    /** 设置颜色与基准透明度，透明度会限制在 0 至 1。 */
    fun color(hex: Long, alpha: Float = this.alpha) {
        color = rgba(hex); this.alpha = alpha.coerceIn(0f, 1f)
    }

    /** 设置环贴图，贴图必须使用 alpha 描述旋转对称的环形。 */
    fun texture(path: String) {
        texturePath = path
    }

    /** 设置横向压扁比例、起止尺寸倍率与三段寿命时长。 */
    fun shape(
        flatten: Float = this.flatten,
        growthStart: Float = this.growthStart, growthEnd: Float = this.growthEnd,
        fadeIn: Float = this.fadeIn, full: Float = this.full, fadeOut: Float = this.fadeOut,
    ) {
        this.flatten = flatten
        this.growthStart = growthStart; this.growthEnd = growthEnd
        this.fadeIn = fadeIn; this.full = full; this.fadeOut = fadeOut
    }

    /** 设置环沿发射方向的速度范围，不调用时环保持在生成位置。 */
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
