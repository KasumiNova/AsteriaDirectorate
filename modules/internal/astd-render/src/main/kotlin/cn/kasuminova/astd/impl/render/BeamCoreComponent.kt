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

enum class BeamPalette { CORE, GLOW }

data class BeamCorePieceSpec(
    val reversedU: Boolean,
    val useGlowWidth: Boolean,
    val palette: BeamPalette,
    val baseAlpha: Float,
    val emissiveAlpha: Float,
    val mixPower: Float,
    val texSpeed: Float,
    val createDiffuse: Color,
    val createFringe: Color,
)

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
    val bodyWidthBase: Float = 0.78f,
    val bodyWidthRamp: Float = 0.22f,
    val alphaRampBase: Float = 1f,
    val alphaRampMul: Float = 0f,
    val emissiveRampBase: Float = 1f,
    val emissiveRampMul: Float = 0f,
    val tipWidthMul: Float = 1f,
    val fadeMulScalesWidth: Boolean = false,
    val fadeMulScalesAlpha: Boolean = false,
    val lerpColorPerFrame: Boolean = true,
    val applyEndFade: Boolean = true,
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

class BeamCoreComponent(
    id: String,
    private val spec: BeamCoreSpec = BeamCoreSpec(),
    layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    renderOrder: Int = RENDER_ORDER,
) : RenderEntityImpl(id, layer, renderOrder) {

    private val log = Global.getLogger(BeamCoreComponent::class.java)
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var pieces: List<TrailLease> = emptyList()

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
                lease.touch()
            }
        }
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
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
                    watchdogHeartbeat = spec.heartbeat,
                    watchdogFadeOut = spec.fadeOut,
                ),
            )
            if (lease == null) {
                built.forEach { it.release() }
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

    private fun poolKey(piece: BeamCorePieceSpec) = TrailLeaseKey(
        layer = layer,
        coreSpritePath = spec.corePath,
        fringeSpritePath = spec.fringePath,
        mixPower = piece.mixPower,
        capacity = PIECE_POOL_CAPACITY,
    )

    companion object {
        const val RENDER_ORDER = 100

        private const val PIECE_POOL_CAPACITY = 8

        private const val CHECKOUT_WARN_STRIDE = 64
    }
}
