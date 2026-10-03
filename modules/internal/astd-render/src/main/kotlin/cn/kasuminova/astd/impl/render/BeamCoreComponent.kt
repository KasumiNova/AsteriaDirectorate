package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.BeamHost
import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLease
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/** 束体调色档：核心片用 core 色作 diffuse、glow 色作 emissive；辉光片两者都用 glow 色（仅逐帧色 lerp 时生效）。 */
enum class BeamPalette { CORE, GLOW }

/**
 * 4 件套之一（core / coreMirroredU / glow / glowMirroredU）的着色档位。
 *
 * 说明：每帧束体沿束**均匀或线性 taper**着色（[BeamCoreComponent] 逐帧刷新 start/end 宽度与 alpha），
 * 只保留每帧真正生效的 body alpha / emissive alpha，以及创建期定死、之后不再改的 mixPower / 纹理流速 /
 * 材质 diffuse+fringe 色（固定色模式下即最终色；逐帧 lerp 模式下仅作首帧）。
 */
data class BeamCorePieceSpec(
    /** 是否 UV 镜像片（node 顺序反转 + 宽度端反转，做 UV 对称叠加）。 */
    val reversedU: Boolean,
    /** 用辉光宽（glowW）还是核心宽（coreW）。 */
    val useGlowWidth: Boolean,
    /** 调色档（逐帧色 lerp 模式下决定 diffuse/emissive 取核心色还是辉光色）。 */
    val palette: BeamPalette,
    /** 每帧 body alpha。 */
    val baseAlpha: Float,
    /** 每帧 emissive alpha。 */
    val emissiveAlpha: Float,
    /** mixPower（创建期定死）。 */
    val mixPower: Float,
    /** 纹理流速（负值向前流动，创建期定死）。 */
    val texSpeed: Float,
    /** 创建期 diffuse 色（材质色）。固定色模式即最终色。 */
    val createDiffuse: Color,
    /** 创建期 fringe/emissive 色（材质发光色）。固定色模式即最终发光色。 */
    val createFringe: Color,
)

/**
 * 光束核心束体规格：两个光束共用的 4 件套（core+coreU+glow+glowU）着色/宽度/颜色/淡出参数。
 * 全部默认值取自 PSI-Ω——`BeamCoreSpec()` 即 1:1 复现 Psi 束体；GravityCollapse 覆盖颜色/宽度/
 * taper/淡出方式即可。这是「束体 4 件套参数表格化」的落点（迁移计划 §5-A）。
 *
 * 宽度管线（每帧）：coreW = baseWidth×(coreWidthBase+coreWidthRamp×strength)，glowW = coreW×(glowWidthMul+
 * glowWidthRamp×strength)；bodyW = 选中宽 ×(bodyWidthBase+bodyWidthRamp×strength)，若 [fadeMulScalesWidth]
 * 再 ×fadeMul；tipW = bodyW×[tipWidthMul]。Psi：bodyWidthBase=0.78/Ramp=0.22、tipWidthMul=1（均匀）、不吃 fadeMul。
 */
data class BeamCoreSpec(
    val coreColor0: Color = Color(30, 0, 70, 240),
    val coreColor1: Color = Color(55, 5, 110, 240),
    val glowColor0: Color = Color(65, 15, 130, 220),
    val glowColor1: Color = Color(110, 35, 180, 220),
    val coreWidthBase: Float = 0.92f,
    val coreWidthRamp: Float = 0.55f,
    val coreWidthMin: Float = 10f,
    val glowWidthMul: Float = 1.55f,
    val glowWidthRamp: Float = 0.30f,
    val glowWidthMin: Float = 16f,
    /** body 宽 = 选中宽 ×(bodyWidthBase + bodyWidthRamp×strength)。 */
    val bodyWidthBase: Float = 0.78f,
    val bodyWidthRamp: Float = 0.22f,
    /** 每帧 body alpha = piece.baseAlpha ×(alphaRampBase + alphaRampMul×strength)。默认(1,0)=不随 strength 变（Psi/GC）。 */
    val alphaRampBase: Float = 1f,
    val alphaRampMul: Float = 0f,
    /** 每帧 emissive alpha = piece.emissiveAlpha ×(emissiveRampBase + emissiveRampMul×strength)。默认(1,0)=不随 strength 变。 */
    val emissiveRampBase: Float = 1f,
    val emissiveRampMul: Float = 0f,
    /** tip 宽 = body 宽 × 此值（1=均匀，<1=向末端收窄）。 */
    val tipWidthMul: Float = 1f,
    /** true 时 body/tip 宽再 ×fadeMul（束体随淡出整体变细）。 */
    val fadeMulScalesWidth: Boolean = false,
    /** true 时每帧 body/emissive alpha 再 ×fadeMul（束体随淡出整体变淡）。 */
    val fadeMulScalesAlpha: Boolean = false,
    /** true 时每帧按 strength lerp 材质色（Psi）；false 时用创建期固定色（GravityCollapse）。 */
    val lerpColorPerFrame: Boolean = true,
    /** true 时每帧施加填充端淡化（fillStart/End，Psi）；false 时不施加（GravityCollapse）。 */
    val applyEndFade: Boolean = true,
    /**
     * 看门狗心跳：firing 每帧触活保活；停火不触活 → heartbeat 秒后快照当前 alpha 经 fadeOut 秒
     * 线性淡出自动泊车归还（池化口径，实体不 delete）。注意与旧实现的行为差异：旧版「组件挂载
     * 但 active=false」期间仍每帧重钉定时器导致束体满亮常显（属旧实现缺陷）；本口径停火期淡出
     * 泊车，复火时由 [BeamCoreComponent.ensurePieces] 自愈重建（租约失效/实体被 purge 均重新检出）。
     */
    val heartbeat: Float = 0.35f,
    val fadeOut: Float = 0.16f,
    val texturePixels: Float = 512f,
    val startFadeAlpha: Float = 0.22f,
    val startFadeFactor: Float = 0.018f,
    val endFadeAlpha: Float = 0.18f,
    val endFadeFactor: Float = 0.024f,
    val jitterPower: Float = 0.03f,
    val corePath: String = BeamSprites.CORE_PATH,
    val fringePath: String = BeamSprites.FRINGE_PATH,
    val pieces: List<BeamCorePieceSpec> = defaultPsiPieces(),
) {
    companion object {
        /** PSI-Ω 的 4 件套档位（核心亮度 ×0.50、辉光原亮度；负纹理速、镜像片速度取反 ×0.92）。 */
        fun defaultPsiPieces(): List<BeamCorePieceSpec> {
            val core = Color(30, 0, 70, 240)
            val glow = Color(65, 15, 130, 220)
            return listOf(
                BeamCorePieceSpec(
                    reversedU = false,
                    useGlowWidth = false,
                    palette = BeamPalette.CORE,
                    baseAlpha = 0.55f * 0.50f,
                    emissiveAlpha = 0.45f * 0.50f,
                    mixPower = 3.6f,
                    texSpeed = -540f,
                    createDiffuse = core,
                    createFringe = glow
                ),
                BeamCorePieceSpec(
                    reversedU = true,
                    useGlowWidth = false,
                    palette = BeamPalette.CORE,
                    baseAlpha = 0.20f * 0.50f,
                    emissiveAlpha = 0.22f * 0.50f,
                    mixPower = 3.2f,
                    texSpeed = -540f * -0.92f,
                    createDiffuse = core,
                    createFringe = glow
                ),
                BeamCorePieceSpec(
                    reversedU = false,
                    useGlowWidth = true,
                    palette = BeamPalette.GLOW,
                    baseAlpha = 0.14f,
                    emissiveAlpha = 0.50f,
                    mixPower = 3.0f,
                    texSpeed = -340f,
                    createDiffuse = glow,
                    createFringe = glow
                ),
                BeamCorePieceSpec(
                    reversedU = true,
                    useGlowWidth = true,
                    palette = BeamPalette.GLOW,
                    baseAlpha = 0.08f,
                    emissiveAlpha = 0.28f,
                    mixPower = 2.8f,
                    texSpeed = -340f * -0.92f,
                    createDiffuse = glow,
                    createFringe = glow
                ),
            )
        }
    }
}

/**
 * 公共束体节点：两个光束共用的常驻直束 4 件套（迁移计划 §3.3，本次收益最大的共享资产）。
 *
 * 生命周期：读 [RenderContext.frame] 的 origin/facing/length 作束几何、intensity 作 strength、fadeMul 作淡出包络、
 * active 作 firing；束体走 PooledCombatVfx 池化租约（防 renderEntityMap 滞留泄漏）的心跳看门狗模式——
 * firing 时每帧 touch 保活（对齐旧逐帧重钉 globalTimer 语义），停火即不触活，看门狗按 heartbeat 秒
 * 全亮 + fadeOut 秒线性淡出后自动泊车归还（复火时 touch 打断淡出复活，或重新检出）。基宽走 [BeamHost.baseWidth]。
 *
 * 与旧实现的行为差异（已裁定接受）：旧版「组件挂载但 active=false」期间仍每帧重钉定时器，束体满亮
 * 常显（属旧实现缺陷）；本口径停火期看门狗淡出泊车，复火时由 [ensurePieces] 自愈重建——租约失效、
 * 实体被外部 purge（hasDelete）均触发重新检出。
 */
class BeamCoreComponent(
    id: String,
    private val spec: BeamCoreSpec = BeamCoreSpec(),
    layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    renderOrder: Int = RENDER_ORDER,
) : RenderEntityImpl(id, layer, renderOrder) {

    private val log = Global.getLogger(BeamCoreComponent::class.java)
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var pieces: List<TrailLease> = emptyList()

    /** 检出失败 WARN 节流计数（首次 + 每 64 次，与池绑定层口径一致）。 */
    private var checkoutFailCount = 0

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        ensurePieces(engine, ctx)
        return pieces.size == spec.pieces.size
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val host = ctx.host as BeamHost
        if (ctx.frame.active) {
            ensurePieces(engine, ctx)
            pieces.forEachIndexed { index, lease ->
                updatePiece(lease, ctx, host, spec.pieces[index])
                // 心跳触活：看门狗计时归零（淡出途中触活即复活回满亮，对齐重钉 timer 打断淡出）
                lease.touch()
            }
        }
        // 停火分支无需动作：看门狗在 heartbeat 秒未触活后快照当前 alpha 线性淡出并自动泊车
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        // 立即进入快照淡出（对齐旧 setGlobalTimer(0,0,fadeOut)：跳过 full 段直接线性淡出）
        pieces.forEach { it.release(spec.fadeOut) }
    }

    override fun onDetachSelf() {
        pieces.forEach { it.release() }
        pieces = emptyList()
    }

    private fun ensurePieces(engine: CombatEngineAPI, ctx: RenderContext) {
        if (pieces.size == spec.pieces.size && pieces.all { it.active && !it.entity.hasDelete() }) return
        pieces.forEach { it.release() }
        pieces = emptyList()

        BoxUtilCombatVfx.ensureReady(engine)
        // 组件级贴图门保留：失败 WARN 语义与预载职责不变（池侧加载命中同一缓存）
        sprites ?: BeamSprites.load(spec.corePath, spec.fringePath)?.also { sprites = it } ?: return
        val host = ctx.host as BeamHost
        val ramp = ctx.frame.intensity.coerceIn(0f, 1f)
        val coreW = coreWidth(host, ramp)
        val glowW = glowWidth(coreW, ramp)

        val built = ArrayList<TrailLease>(spec.pieces.size)
        for (piece in spec.pieces) {
            val width = if (piece.useGlowWidth) glowW else coreW
            val lease = PooledCombatVfx.checkoutTrail(
                engine, poolKey(piece),
                TrailLeaseSpec(
                    location = Vector2f(0f, 0f),
                    facingDeg = 0f,
                    // 节点序对齐 FromCenter（node[0]=基部）：镜像片反转节点做 UV 对称叠加
                    nodes = if (piece.reversedU) {
                        listOf(Vector2f(ctx.frame.length, 0f), Vector2f(0f, 0f))
                    } else {
                        listOf(Vector2f(0f, 0f), Vector2f(ctx.frame.length, 0f))
                    },
                    startWidth = if (piece.reversedU) (width * spec.tipWidthMul).coerceAtLeast(1f) else width,
                    endWidth = if (piece.reversedU) width else (width * spec.tipWidthMul).coerceAtLeast(1f),
                    coreColor = piece.createDiffuse,
                    fringeColor = piece.createFringe,
                    startAlpha = piece.baseAlpha,
                    endAlpha = piece.baseAlpha,
                    startEmissiveAlpha = piece.emissiveAlpha,
                    endEmissiveAlpha = piece.emissiveAlpha,
                    fillStartAlpha = if (spec.applyEndFade) spec.startFadeAlpha else 1f,
                    fillStartFactor = if (spec.applyEndFade) spec.startFadeFactor else 1f,
                    fillEndAlpha = if (spec.applyEndFade) spec.endFadeAlpha else 1f,
                    fillEndFactor = if (spec.applyEndFade) spec.endFadeFactor else 1f,
                    texturePixels = spec.texturePixels,
                    textureSpeed = piece.texSpeed,
                    uvOffset = (Math.random().toFloat() * 2f) - 1f,
                    jitterPower = spec.jitterPower,
                    // 心跳看门狗：对齐旧逐帧重钉 globalTimer(0, heartbeat, fadeOut) 的保活/淡出/自收口径
                    watchdogHeartbeat = spec.heartbeat,
                    watchdogFadeOut = spec.fadeOut,
                ),
            )
            if (lease == null) {
                built.forEach { it.release() }
                // 节流 WARN：active 期间每帧重试，池满/池不可用期间首次 + 每 64 次记一条（与池绑定层口径一致）
                checkoutFailCount++
                if (checkoutFailCount == 1 || checkoutFailCount % CHECKOUT_WARN_STRIDE == 0) {
                    log.warn("光束核心束体 4 件套池化检出失败：id=$id piece=$piece（池不可用或池满，累计 $checkoutFailCount 次）")
                }
                return
            }
            built += lease
        }
        pieces = built
    }

    private fun updatePiece(lease: TrailLease, ctx: RenderContext, host: BeamHost, piece: BeamCorePieceSpec) {
        val entity = lease.entity
        if (entity.hasDelete()) return
        val frame = ctx.frame
        val ramp = frame.intensity.coerceIn(0f, 1f)
        val fade = frame.fadeMul.coerceIn(0f, 1f)
        val coreW = coreWidth(host, ramp)
        val glowW = glowWidth(coreW, ramp)
        val width = if (piece.useGlowWidth) glowW else coreW

        val nodes = entity.nodes
        if (nodes != null && nodes.size >= 2) {
            if (!piece.reversedU) {
                nodes[0].set(0f, 0f); nodes[1].set(frame.length, 0f)
            } else {
                nodes[0].set(frame.length, 0f); nodes[1].set(0f, 0f)
            }
            entity.setNodeRefreshIndex(0)
            entity.setNodeRefreshAllFromCurrentIndex()
            entity.submitNodes()
        }

        var bodyW = (width * (spec.bodyWidthBase + spec.bodyWidthRamp * ramp))
        if (spec.fadeMulScalesWidth) bodyW *= fade
        bodyW = bodyW.coerceAtLeast(2f)
        val tipW = (bodyW * spec.tipWidthMul).coerceAtLeast(1f)
        // node[0]=base（factor 0），node[1]=tip；镜像片 node 反转，宽度端亦反转。
        if (!piece.reversedU) {
            entity.startWidth = bodyW; entity.endWidth = tipW
        } else {
            entity.startWidth = tipW; entity.endWidth = bodyW
        }

        var a = piece.baseAlpha * (spec.alphaRampBase + spec.alphaRampMul * ramp)
        var ea = piece.emissiveAlpha * (spec.emissiveRampBase + spec.emissiveRampMul * ramp)
        if (spec.fadeMulScalesAlpha) {
            a *= fade; ea *= fade
        }
        a = a.coerceIn(0f, 1.2f)
        ea = ea.coerceIn(0f, 10f)
        entity.setStartColor(1f, 1f, 1f, a)
        entity.setEndColor(1f, 1f, 1f, a)
        entity.setStartEmissive(1f, 1f, 1f, ea)
        entity.setEndEmissive(1f, 1f, 1f, ea)

        if (spec.lerpColorPerFrame) {
            val coreColor = BeamMath.colorLerp(spec.coreColor0, spec.coreColor1, ramp)
            val glowColor = BeamMath.colorLerp(spec.glowColor0, spec.glowColor1, ramp)
            when (piece.palette) {
                BeamPalette.CORE -> {
                    entity.materialData.setColor(coreColor); entity.materialData.setEmissiveColor(glowColor)
                }

                BeamPalette.GLOW -> {
                    entity.materialData.setColor(glowColor); entity.materialData.setEmissiveColor(glowColor)
                }
            }
        }

        entity.setStateVanilla(frame.origin, frame.facing)
    }

    private fun coreWidth(host: BeamHost, ramp: Float): Float =
        (host.baseWidth * (spec.coreWidthBase + spec.coreWidthRamp * ramp)).coerceAtLeast(spec.coreWidthMin)

    private fun glowWidth(coreW: Float, ramp: Float): Float {
        return (coreW * (spec.glowWidthMul + spec.glowWidthRamp * ramp)).coerceAtLeast(spec.glowWidthMin)
    }

    /**
     * 束体片池化租约键：按渲染层/贴图对/片 mixPower 键控（GC 束体 4 件套塌缩为 2 池）。
     * 容量 8 = 同 mixPower 片的峰值并发束数（GC/PSI 均为单装重型束，余量充足；池满拒发 + 节流 WARN）。
     */
    private fun poolKey(piece: BeamCorePieceSpec) = TrailLeaseKey(
        layer = layer,
        coreSpritePath = spec.corePath,
        fringeSpritePath = spec.fringePath,
        mixPower = piece.mixPower,
        capacity = PIECE_POOL_CAPACITY,
    )

    companion object {
        /** 束体在树内的次级绘制序：置于 detail（螺旋/环）之下。 */
        const val RENDER_ORDER = 100

        /** 单 mixPower 片的池容量（同规格束体的峰值并发数）。 */
        private const val PIECE_POOL_CAPACITY = 8

        /** 检出失败 WARN 节流步长（首次必记，与池绑定层 OVERFLOW_WARN_STRIDE 口径一致）。 */
        private const val CHECKOUT_WARN_STRIDE = 64
    }
}
