package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.CurveEntity
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PSI-Ω 的双螺旋扰动丝带（迁移计划 §3.4，detail 后端之一：CurveEntity 双螺旋）。
 *
 * 每条螺旋拆两层：BODY（normal blend 光带主体）+ GLOW（additive 内发光高光），共 4 条 [CurveEntity]。
 * 螺旋节点位置/切线/颜色的数学从旧 `PsiOmegaBeamVfx.updateWisp` 原样移植，几何源改读 [RenderContext.frame]。
 * 淡出与束体同法（心跳定时器：active 刷新常驻，停火不刷新自淡）。
 */
class PsiHelixComponent(
    id: String,
    layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    renderOrder: Int = RENDER_ORDER,
) : RenderEntityImpl(id, layer, renderOrder) {

    private val log = Global.getLogger(PsiHelixComponent::class.java)
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var wispBodies: List<CurveEntity> = emptyList()
    private var wispGlows: List<CurveEntity> = emptyList()

    private var time = 0f
    private var wispLenSmooth = -1f
    private val wispScratchX = FloatArray(WISP_NODES_MAX)
    private val wispScratchY = FloatArray(WISP_NODES_MAX)

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        ensureWisps(engine, ctx.frame.length.coerceAtLeast(16f))
        return wispBodies.size == 2 && wispGlows.size == 2
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        if (!frame.active) {
            (wispBodies + wispGlows).forEach { if (!it.hasDelete()) it.setGlobalTimer(0f, 0f, FADE_OUT) }
            return
        }

        val usableLen = frame.length.coerceAtLeast(16f)
        ensureWisps(engine, usableLen)
        if (wispBodies.size != 2 || wispGlows.size != 2) return

        time += amount
        if (wispLenSmooth < 0f) wispLenSmooth = usableLen
        val lenSmoothT = (amount * 10f).coerceIn(0f, 1f)
        wispLenSmooth += (usableLen - wispLenSmooth) * lenSmoothT

        val ramp = frame.intensity.coerceIn(0f, 1f)
        val coreW = (baseWidthOf(ctx) * (0.92f + 0.55f * ramp)).coerceAtLeast(10f)
        val glowColor = BeamMath.colorLerp(GLOW_COLOR_0, GLOW_COLOR_1, ramp)

        for (idx in 0..1) {
            val bodyWidth = (coreW * 0.92f).coerceAtLeast(8f)
            val glowWidth = (coreW * 0.62f).coerceAtLeast(6f)
            updateWisp(wispBodies[idx], frame.origin.x, frame.origin.y, frame.facing, wispLenSmooth, time, ramp, idx, WispStyle.BODY, bodyWidth, glowColor)
            updateWisp(wispGlows[idx], frame.origin.x, frame.origin.y, frame.facing, wispLenSmooth, time, ramp, idx, WispStyle.GLOW, glowWidth, glowColor)
        }
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        (wispBodies + wispGlows).forEach { if (!it.hasDelete()) it.setGlobalTimer(0f, 0f, FADE_OUT) }
    }

    override fun onDetachSelf() {
        (wispBodies + wispGlows).forEach { it.delete() }
        wispBodies = emptyList()
        wispGlows = emptyList()
    }

    private fun baseWidthOf(ctx: RenderContext): Float = (ctx.host as cn.kasuminova.astd.api.render.BeamHost).baseWidth

    private fun ensureWisps(engine: CombatEngineAPI, length: Float) {
        val bodiesOk = wispBodies.size == 2 && wispBodies.none { it.hasDelete() }
        val glowsOk = wispGlows.size == 2 && wispGlows.none { it.hasDelete() }
        if (bodiesOk && glowsOk) return
        (wispBodies + wispGlows).forEach { it.delete() }
        wispBodies = emptyList()
        wispGlows = emptyList()

        BoxUtilCombatVfx.ensureReady(engine)
        val sprite = sprites ?: BeamSprites.load()?.also { sprites = it } ?: return

        val bodies = ArrayList<CurveEntity>(2)
        val glows = ArrayList<CurveEntity>(2)
        repeat(2) {
            val body = createWispTrail(engine, diffuseSprite = sprite.second, emissiveSprite = sprite.first, additive = false, mixFactor = 2.2f, texSpeed = WISP_TEX_SPEED * 0.70f, interpolation = WISP_INTERP_BODY, glowPower = 1.05f)
            val glow = createWispTrail(engine, diffuseSprite = sprite.first, emissiveSprite = sprite.second, additive = true, mixFactor = 3.6f, texSpeed = WISP_TEX_SPEED, interpolation = WISP_INTERP_GLOW, glowPower = 1.60f)
            if (body == null || glow == null) {
                bodies.forEach { it.delete() }
                glows.forEach { it.delete() }
                body?.delete(); glow?.delete()
                log.warn("PSI-Ω 双螺旋建实体失败：id=$id（BoxUtil addEntity 返回非 0）")
                return
            }
            bodies += body
            glows += glow
        }
        wispBodies = bodies
        wispGlows = glows
    }

    private fun createWispTrail(
        engine: CombatEngineAPI,
        diffuseSprite: SpriteAPI,
        emissiveSprite: SpriteAPI,
        additive: Boolean,
        mixFactor: Float,
        texSpeed: Float,
        interpolation: Short,
        glowPower: Float,
    ): CurveEntity? {
        val e = CurveEntity()
        e.setInterpolation(interpolation)
        e.setGlobalUV(true)

        val initCount = WISP_NODES_INIT.coerceAtLeast(2)
        val nodes = ArrayList<NodeData>(initCount)
        repeat(initCount) {
            val n = NodeData()
            n.setWidth(8f)
            n.setMixFactor(mixFactor)
            n.setColor(DEFAULT_GLOW_COLOR)
            n.setEmissiveColor(DEFAULT_GLOW_COLOR)
            nodes.add(n)
        }
        e.nodes = nodes
        e.submitNodes()

        e.setLayer(layer)
        if (additive) e.setAdditiveBlend() else e.setNormalBlend()
        e.setGlobalTimer(0f, HEARTBEAT, FADE_OUT)

        e.materialData.setDiffuse(diffuseSprite)
        e.materialData.setEmissive(emissiveSprite)
        val mat = e.materialData
        mat.setAlphaToEmissive(0f)
        mat.setColorToEmissive(0f)
        mat.setGlowPower(glowPower)
        mat.setColor(DEFAULT_GLOW_COLOR)
        mat.setEmissiveColor(DEFAULT_GLOW_COLOR)

        e.setTexturePixels(WISP_TEX_PIXELS)
        e.setTextureSpeed(texSpeed)
        e.setUVOffset((BeamMath.rand01() * 2f) - 1f)

        e.setFillStartAlpha(1f)
        e.setFillStartFactor(0f)
        e.setFillEndAlpha(1f)
        e.setFillEndFactor(0f)

        val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_CURVE, e)
        if (state != 0) {
            e.delete()
            return null
        }
        return e
    }

    private enum class WispStyle { BODY, GLOW }

    private fun updateWisp(
        e: CurveEntity,
        startX: Float,
        startY: Float,
        facing: Float,
        length: Float,
        time: Float,
        ramp: Float,
        index: Int,
        style: WispStyle,
        baseWidthVal: Float,
        baseColor: Color,
    ) {
        if (e.hasDelete()) return
        val nodes = e.nodes ?: return
        if (nodes.isEmpty()) return

        val s = ramp.coerceIn(0f, 1f)
        val phaseBase = index * PI.toFloat()
        val travel = time * WISP_TRAVEL_SPEED * WISP_ROT_SPEED_MUL
        val spin = time * WISP_SPIN_SPEED * WISP_ROT_SPEED_MUL
        val radius = WISP_LOCAL_RADIUS_SU * WISP_RADIUS_MUL

        val aMul = if (style == WispStyle.BODY) 1.00f else 0.72f
        val eaMul = if (style == WispStyle.BODY) 0.55f else 1.35f
        val styleBrightMul = if (style == WispStyle.BODY) WISP_BODY_BRIGHTNESS_MUL else WISP_GLOW_BRIGHTNESS_MUL
        val baseAlpha = (0.40f * aMul * styleBrightMul).coerceIn(0f, 0.95f)
        val baseEmissiveAlpha = (0.60f * eaMul * styleBrightMul).coerceIn(0f, 1.0f)
        val baseW = if (style == WispStyle.BODY) (baseWidthVal * (0.8f + 0.2f * s)) else (baseWidthVal * (0.85f + 0.15f * s))

        val wantCount = ((length / WISP_NODE_STEP_SU).toInt() + 1).coerceIn(2, WISP_NODES_MAX)
        if (nodes.size < wantCount) {
            repeat(wantCount - nodes.size) { nodes.add(NodeData()) }
            e.setNodeRefreshIndex(0)
            e.setNodeRefreshAllFromCurrentIndex()
        }

        val totalNodes = nodes.size
        val maxLen = (totalNodes - 1).toFloat() * WISP_NODE_STEP_SU
        val lenUse = length.coerceAtMost(maxLen).coerceAtLeast(0f)
        val nodeCount = ((lenUse / WISP_NODE_STEP_SU).toInt() + 1).coerceIn(2, totalNodes)
        val lastIdx = nodeCount - 1

        val br = baseColor.red / 255f
        val bg = baseColor.green / 255f
        val bb = baseColor.blue / 255f
        val edge = 0.12f
        val xs = wispScratchX
        val ys = wispScratchY

        for (i in 0 until nodeCount) {
            val t = if (lastIdx <= 0) 0f else i.toFloat() / lastIdx.toFloat()
            val localX = if (i == lastIdx) lenUse else i.toFloat() * WISP_NODE_STEP_SU
            val phase = 2f * PI.toFloat() * (localX / WISP_PITCH_SU + travel) + spin + phaseBase
            val localY = sin(phase) * radius
            xs[i] = localX
            ys[i] = localY

            val z = cos(phase)
            val front = (z * 0.5f + 0.5f).coerceIn(0f, 1f)
            val alphaMod = BeamMath.lerp(0.50f, 1.55f, front)
            val rgbMod = BeamMath.lerp(0.55f, 1.00f, front)
            val emissiveAMod = BeamMath.lerp(0.20f, 1.00f, front)
            val head = BeamMath.smoothstep01((t / edge).coerceIn(0f, 1f))
            val tail = BeamMath.smoothstep01(((1f - t) / edge).coerceIn(0f, 1f))
            val endEnv = head * tail
            val zWidthMod = 1.0f + z * 0.18f

            val node = nodes[i]
            node.setLocation(localX, localY)
            node.setWidth((baseW * zWidthMod).coerceAtLeast(1f))
            node.setMixFactor(if (style == WispStyle.GLOW) 0.92f else 0.85f)

            val nodeAlpha = (baseAlpha * alphaMod * endEnv).coerceIn(0f, 1f)
            val nodeEmA = (baseEmissiveAlpha * emissiveAMod * endEnv).coerceIn(0f, 1f)
            node.setColor((br * rgbMod).coerceIn(0f, 1f), (bg * rgbMod).coerceIn(0f, 1f), (bb * rgbMod).coerceIn(0f, 1f), nodeAlpha)

            val toWhite = if (style == WispStyle.GLOW) 0.35f else 0.12f
            val w = (front * toWhite).coerceIn(0f, 1f)
            val er = BeamMath.lerp(br * 0.75f, 1f, w).coerceIn(0f, 1f)
            val eg = BeamMath.lerp(bg * 0.75f, 1f, w).coerceIn(0f, 1f)
            val eb = BeamMath.lerp(bb * 0.75f, 1f, w).coerceIn(0f, 1f)
            node.setEmissiveColor(er, eg, eb, nodeEmA)
        }

        val handleLen = WISP_NODE_STEP_SU * WISP_TANGENT_HANDLE_MUL
        for (i in 0 until nodeCount) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i1 = (i + 1).coerceAtMost(lastIdx)
            val dx = xs[i1] - xs[i0]
            val dy = ys[i1] - ys[i0]
            val invLen = 1f / sqrt((dx * dx + dy * dy).coerceAtLeast(0.0001f))
            val tx = dx * invLen * handleLen
            val ty = dy * invLen * handleLen
            val node = nodes[i]
            node.setTangentLeft(-tx, -ty)
            node.setTangentRight(tx, ty)
        }

        e.setNodeRenderingCount(nodeCount)
        e.setNodeRefreshIndex(0)
        e.setNodeRefreshSize(nodeCount)
        e.submitNodes()

        e.setTexturePixels(WISP_TEX_PIXELS)
        val speedMul = if (style == WispStyle.BODY) 0.85f else 1.00f
        e.setTextureSpeed(WISP_TEX_SPEED * speedMul * (0.80f + 0.35f * s))
        e.materialData.setColor(Color(255, 255, 255, 255))
        e.materialData.setEmissiveColor(baseColor)
        e.setStateVanilla(org.lwjgl.util.vector.Vector2f(startX, startY), facing)
        e.setGlobalTimer(0f, HEARTBEAT, FADE_OUT)
    }

    companion object {
        /** 螺旋在树内的次级绘制序：置于束体（100）之上。 */
        const val RENDER_ORDER = 200

        private const val HEARTBEAT = 0.35f
        private const val FADE_OUT = 0.16f
        private const val WISP_TEX_PIXELS = 256f
        private const val WISP_TEX_SPEED = -240f
        private const val WISP_NODES_INIT = 256
        private const val WISP_NODES_MAX = 1024
        private const val WISP_PITCH_SU = 100f
        private const val WISP_LOCAL_RADIUS_SU = 16f
        private const val WISP_RADIUS_MUL = 0.70f
        private const val WISP_NODE_STEP_SU = 10f
        private const val WISP_INTERP_BODY: Short = 16
        private const val WISP_INTERP_GLOW: Short = 24
        private const val WISP_TANGENT_HANDLE_MUL = 0.35f
        private const val WISP_ROT_SPEED_MUL = 0.28f
        private const val WISP_TRAVEL_SPEED = 0.95f
        private const val WISP_SPIN_SPEED = 2.10f
        private const val WISP_BODY_BRIGHTNESS_MUL = 0.50f
        private const val WISP_GLOW_BRIGHTNESS_MUL = 1.40f

        private val DEFAULT_GLOW_COLOR = Color(65, 15, 130, 220)
        private val GLOW_COLOR_0 = Color(65, 15, 130, 220)
        private val GLOW_COLOR_1 = Color(110, 35, 180, 220)
    }
}
