package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.BeamHost
import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.TrailEntity
import org.boxutil.util.CurveUtil
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 恒星喷射（Stellar Jet）光束的节点族——把旧 `StellarJetBeamVfx`（1340 行、束体/丝带/沿束散射/炮口/命中五类效果
 * 混在一个 update + 一堆 40 个空 catch 里）拆成一效果一节点（迁移计划 §7.3）。束体 4 件套走公共 [BeamCoreComponent]；
 * 本文件是其余四类**纯视觉**节点。EMP 弧的 applyDamage/选点（gameplay）不在此——留在
 * `StellarJetEmitterEveryFrameEffect`。几何统一读 [RenderContext.frame]（intensity=strength 控宽/密度/频次，
 * active=firing 控是否散射，endpoint/hitTarget=命中端）。
 */

/** 恒星喷射共用调色与束体规格（旧 `StellarJetEmitterEveryFrameEffect` 的 CORE/GLOW 色 + 旧 `StellarJetBeamVfx` 的束体参数）。 */
internal object SjBeam {

    val CORE_COLOR = Color(255, 250, 235, 220)
    val GLOW_COLOR = Color(120, 200, 255, 160)

    /** 沿束烟雾基色：更偏蓝（独立于 core/glow 以便稳定调参，旧 AMBIENT_SMOKE_BASE_COLOR）。 */
    val AMBIENT_SMOKE_BASE_COLOR = Color(70, 160, 255)

    // 束体宽度法：核心 coreW = baseWidth×(1+strength)；辉光 glowW = baseWidth×(1.6667+3×strength)。
    // 二者由旧宿主两条独立 lerp（16.8→33.6 / 28→78.4，baseWidth=BEAM_MIN_CORE_W=16.8）等价折算而来（端点精确吻合）。
    const val CORE_WIDTH_BASE = 1f
    const val CORE_WIDTH_RAMP = 1f
    const val GLOW_WIDTH_MUL = 1.6666667f
    const val GLOW_WIDTH_RAMP = 3.0f

    private const val CORE_TEX_SPEED = -520f
    private const val GLOW_TEX_SPEED = -320f

    /** 核心束宽（wisp/ambient 等共用同一法）。 */
    fun coreWidth(baseWidth: Float, ramp: Float): Float = baseWidth * (CORE_WIDTH_BASE + CORE_WIDTH_RAMP * ramp.coerceIn(0f, 1f))

    /** 辉光束宽（沿束散射侧向铺开范围据此算）。 */
    fun glowWidth(baseWidth: Float, ramp: Float): Float = baseWidth * (GLOW_WIDTH_MUL + GLOW_WIDTH_RAMP * ramp.coerceIn(0f, 1f))

    /**
     * StellarJet 束体给公共 [BeamCoreComponent] 的规格：核心（白暖芯+蓝辉光边）+ 辉光两片各带 UV 镜像片；
     * 宽度随 strength 线性放大（核心/辉光各自独立法，故 glowWidthRelativeToCore=false）；alpha/emissive 随 strength
     * ramp（旧 updateStraight 的 (0.55+0.75s)/(0.6+1.0s)）；固定色（不逐帧 lerp）；施加两端填充淡化；停火 0.14s 淡出。
     */
    fun coreSpec(): BeamCoreSpec = BeamCoreSpec(
        coreWidthBase = CORE_WIDTH_BASE, coreWidthRamp = CORE_WIDTH_RAMP, coreWidthMin = 0f,
        glowWidthMul = GLOW_WIDTH_MUL, glowWidthRamp = GLOW_WIDTH_RAMP, glowWidthMin = 0f,
        glowWidthRelativeToCore = false,
        bodyWidthBase = 0.72f, bodyWidthRamp = 0.28f, tipWidthMul = 0.75f,
        alphaRampBase = 0.55f, alphaRampMul = 0.75f,
        emissiveRampBase = 0.6f, emissiveRampMul = 1.0f,
        fadeMulScalesWidth = false, fadeMulScalesAlpha = false,
        lerpColorPerFrame = false, applyEndFade = true,
        fadeOut = 0.14f,
        pieces = listOf(
            BeamCorePieceSpec(false, false, BeamPalette.CORE, 0.55f, 3.2f, 3.6f, CORE_TEX_SPEED, CORE_COLOR, GLOW_COLOR),
            BeamCorePieceSpec(true, false, BeamPalette.CORE, 0.22f, 1.35f, 3.2f, CORE_TEX_SPEED * -0.92f, CORE_COLOR, GLOW_COLOR),
            BeamCorePieceSpec(false, true, BeamPalette.GLOW, 0.18f, 1.75f, 3.0f, GLOW_TEX_SPEED, GLOW_COLOR, GLOW_COLOR),
            BeamCorePieceSpec(true, true, BeamPalette.GLOW, 0.10f, 0.95f, 2.8f, GLOW_TEX_SPEED * -0.92f, GLOW_COLOR, GLOW_COLOR),
        ),
    )
}

// ============================ 卷曲能量丝带（双螺旋 wisp） ============================

/**
 * 绕主束对绕缠绕的两条“扰动边缘”丝带（旧 createMultiNodeTrail + updateWisp）。多节点 [TrailEntity]，用沿束推进
 * 的相位 + 正弦偏移在 2D 里模拟绕轴螺旋（两端收敛）。生命周期同束体：active 心跳常驻、停火淡出自删、复火重建。
 */
class BeamHelixTrailComponent(
    id: String,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    private val log = Global.getLogger(BeamHelixTrailComponent::class.java)
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var wisps: List<TrailEntity> = emptyList()
    private var time = 0f

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        ensureWisps(engine, ctx)
        return wisps.size == WISP_COUNT
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val host = ctx.host as BeamHost
        val frame = ctx.frame
        if (frame.active) {
            ensureWisps(engine, ctx)
            time += amount
            val length = frame.length.coerceAtLeast(16f)
            val coreW = SjBeam.coreWidth(host.baseWidth, frame.intensity)
            wisps.forEachIndexed { index, w -> updateWisp(w, frame.origin, frame.facing, length, coreW, frame.intensity.coerceIn(0f, 1f), index) }
        } else {
            wisps.forEach { if (!it.hasDelete()) it.setGlobalTimer(0f, 0f, FADE_OUT) }
        }
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        wisps.forEach { if (!it.hasDelete()) it.setGlobalTimer(0f, 0f, FADE_OUT) }
    }

    override fun onDetachSelf() {
        wisps.forEach { it.delete() }
        wisps = emptyList()
    }

    private fun ensureWisps(engine: CombatEngineAPI, ctx: RenderContext) {
        if (wisps.size == WISP_COUNT && wisps.none { it.hasDelete() }) return
        wisps.forEach { it.delete() }
        wisps = emptyList()

        BoxUtilCombatVfx.ensureReady(engine)
        val sprite = sprites ?: BeamSprites.load()?.also { sprites = it } ?: return
        val host = ctx.host as BeamHost
        val coreW = SjBeam.coreWidth(host.baseWidth, ctx.frame.intensity)
        val width = (coreW * WISP_WIDTH_BASE_MUL * WISP_WIDTH_MUL).coerceAtLeast(5f)

        val built = ArrayList<TrailEntity>(WISP_COUNT)
        repeat(WISP_COUNT) {
            val e = createRibbon(engine, sprite.first, sprite.second, width)
            if (e == null) {
                built.forEach { it.delete() }
                log.warn("StellarJet 螺旋丝带建实体失败：id=$id（BoxUtil addEntity 返回非 0）")
                return
            }
            built += e
        }
        wisps = built
    }

    private fun createRibbon(engine: CombatEngineAPI, coreSprite: SpriteAPI, fringeSprite: SpriteAPI, width: Float): TrailEntity? {
        val e = TrailEntity()
        repeat(WISP_NODES.coerceAtLeast(2)) { e.addNode(Vector2f(0f, 0f)) }
        e.setNodeRefreshAllFromCurrentIndex()
        e.submitNodes()

        e.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        e.setAdditiveBlend()
        e.setGlobalTimer(0f, HEARTBEAT, FADE_OUT)

        e.materialData.setDiffuse(coreSprite)
        e.materialData.setEmissive(fringeSprite)
        e.materialData.setColor(SjBeam.GLOW_COLOR)
        e.materialData.setEmissiveColor(SjBeam.GLOW_COLOR)

        e.setTexturePixels(WISP_TEX_PIXELS)
        e.setTextureSpeed(WISP_TEX_SPEED)
        e.setUVOffset((Math.random().toFloat() * 2f) - 1f)
        e.setStartWidth(width)
        e.setEndWidth(width)
        e.setMixFactor(3.4f)

        e.setFillStartAlpha(WISP_END_ALPHA_START)
        e.setFillStartFactor(WISP_END_FADE_START)
        e.setFillEndAlpha(WISP_END_ALPHA_END)
        e.setFillEndFactor(WISP_END_FADE_END)

        e.setFlick(false)
        e.setSyncFlick(false)
        e.setJitterPower(0.05f)

        val state = BoxUtilCombatVfx.addEntity(engine, org.boxutil.define.BoxEnum.ENTITY_TRAIL, e)
        if (state != 0) {
            e.delete()
            return null
        }
        return e
    }

    private fun updateWisp(e: TrailEntity, start: Vector2f, facing: Float, length: Float, coreWidth: Float, s: Float, index: Int) {
        if (e.hasDelete()) return
        val nodes = e.nodes ?: return
        if (nodes.size < 2) return

        val w = (coreWidth * WISP_WIDTH_BASE_MUL * WISP_WIDTH_MUL * (0.75f + 0.25f * s)).coerceAtLeast(4.5f)
        e.setStartWidth(w)
        e.setEndWidth(w)

        val phaseBase = index * PI.toFloat() // 两条丝带相位相反，形成“对绕”
        val turns = (length / WISP_PITCH_SU).coerceAtLeast(0.75f)
        val travel = time * WISP_TRAVEL_SPEED * WISP_ROT_SPEED_MUL
        val spin = time * WISP_SPIN_SPEED * WISP_ROT_SPEED_MUL
        val radius = WISP_RADIUS_SU
        val fineJitter = radius * WISP_FINE_JITTER_MUL

        val startNode = NodeData().apply {
            setLocation(0f, 0f); setTangentRight(length * 0.33f, 0f); setTangentLeft(0f, 0f)
        }
        val endNode = NodeData().apply {
            setLocation(length, 0f); setTangentLeft(-length * 0.33f, 0f); setTangentRight(0f, 0f)
        }

        val n = nodes.size
        for (i in 0 until n) {
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1).toFloat()
            val p = CurveUtil.getPointOnCurve(startNode, endNode, t)
            var x = p?.x ?: (length * t)
            val baseY = p?.y ?: 0f
            val env = sin(PI.toFloat() * t).coerceAtLeast(0f).pow(1.25f)
            val a = 2f * PI.toFloat() * (turns * t + travel) + spin + phaseBase
            val y = baseY + sin(a) * radius * env + sin(a * 2.1f + t * 9.0f + time * 3.2f * WISP_ROT_SPEED_MUL) * fineJitter * env
            x += cos(a) * (radius * 0.08f) * env
            nodes[i].set(x, y)
        }

        e.setNodeRefreshIndex(0)
        e.setNodeRefreshAllFromCurrentIndex()
        e.submitNodes()

        e.setTexturePixels(WISP_TEX_PIXELS)
        e.setTextureSpeed(WISP_TEX_SPEED * (0.80f + 0.35f * s))

        val a0 = (0.24f + 0.22f * s).coerceIn(0f, 0.9f)
        val a1 = (0.08f + 0.10f * s).coerceIn(0f, 0.70f).coerceAtLeast(a0 * 0.75f)
        val ea0 = (1.6f + 1.4f * s).coerceIn(0f, 6f)
        val ea1 = (1.05f + 0.95f * s).coerceIn(0f, 6f).coerceAtLeast(ea0 * 0.75f)
        e.setStartColor(1f, 1f, 1f, a0)
        e.setEndColor(1f, 1f, 1f, a1)
        e.setStartEmissive(1f, 1f, 1f, ea0)
        e.setEndEmissive(1f, 1f, 1f, ea1)

        e.setStateVanilla(start, facing)
        e.setGlobalTimer(0f, HEARTBEAT, FADE_OUT)
    }

    companion object {
        const val RENDER_ORDER = 200
        private const val WISP_COUNT = 2
        private const val WISP_NODES = 100
        private const val WISP_TEX_PIXELS = 256f
        private const val WISP_TEX_SPEED = -220f
        private const val WISP_ROT_SPEED_MUL = 0.25f
        private const val WISP_WIDTH_BASE_MUL = 0.45f
        private const val WISP_WIDTH_MUL = 1.75f
        private const val WISP_PITCH_SU = 792f
        private const val WISP_RADIUS_SU = 42f
        private const val WISP_FINE_JITTER_MUL = 0.035f
        private const val WISP_TRAVEL_SPEED = 0.95f
        private const val WISP_SPIN_SPEED = 2.10f
        private const val WISP_END_ALPHA_START = 0.14f
        private const val WISP_END_ALPHA_END = 0.11f
        private const val WISP_END_FADE_START = 0.26f
        private const val WISP_END_FADE_END = 0.34f
        private const val HEARTBEAT = 0.35f
        private const val FADE_OUT = 0.14f
    }
}

// ============================ 沿束散射（前向粒子 + 侧向 nebula 烟雾） ============================

/**
 * 光束发射期间沿束持续散发的粒子 + 烟雾（旧 emitAmbientBeamParticles + emitAmbientBeamSmoke）。纯每帧抛引擎粒子，
 * 无常驻句柄；两个累积器控频，密度随束长/strength 变化。仅 firing 时散射。
 */
class StellarJetAmbientComponent(
    id: String,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER) {

    private var particleAcc = 0f
    private var smokeAcc = 0f
    private var smokeIndex = (Math.random() * 997).toInt()

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        if (!frame.active) return
        val host = ctx.host as BeamHost
        val length = frame.length.coerceAtLeast(16f)
        val s = frame.intensity.coerceIn(0f, 1f)
        val coreWidth = SjBeam.coreWidth(host.baseWidth, s)
        val glowWidth = SjBeam.glowWidth(host.baseWidth, s)

        emitParticles(engine, amount, frame.origin, frame.facing, length, s, coreWidth, glowWidth)
        emitSmoke(engine, amount, frame.origin, frame.facing, length, s, coreWidth, glowWidth)
    }

    private fun emitParticles(
        engine: CombatEngineAPI, amount: Float, start: Vector2f, facing: Float, length: Float,
        s: Float, coreWidth: Float, glowWidth: Float,
    ) {
        val ratePerSec = (length / 50f) * PARTICLES_PER_50SU_PER_SEC * (0.65f + 0.85f * s)
        particleAcc += ratePerSec * amount
        val count = particleAcc.toInt().coerceAtMost(PARTICLES_MAX_PER_FRAME)
        if (count > 0) particleAcc -= count
        if (count <= 0) return

        val dir = Vector2f(BeamMath.facingUnitX(facing), BeamMath.facingUnitY(facing))
        val perp = Vector2f(-dir.y, dir.x)
        val baseSpread = ((glowWidth * 0.5f * PARTICLE_LATERAL_MUL) + (coreWidth * 0.25f)).coerceAtLeast(6f)

        for (i in 0 until count) {
            val along = length * BeamMath.rand01()
            val lateral = BeamMath.randSigned01() * baseSpread * (0.65f + 0.55f * s)
            val at = Vector2f(start.x + dir.x * along + perp.x * lateral, start.y + dir.y * along + perp.y * lateral)
            val speed = BeamMath.lerp(PARTICLE_SPEED_MIN, PARTICLE_SPEED_MAX, BeamMath.rand01()) * (0.7f + 0.6f * s)
            val side = BeamMath.randSigned01()
            val vel = Vector2f(dir.x * speed + perp.x * speed * 0.18f * side, dir.y * speed + perp.y * speed * 0.18f * side)
            val size = BeamMath.lerp(PARTICLE_SIZE_MIN, PARTICLE_SIZE_MAX, BeamMath.rand01()) * (0.75f + 0.45f * s)
            val dur = BeamMath.lerp(PARTICLE_DUR_MIN, PARTICLE_DUR_MAX, BeamMath.rand01())
            val bright = BeamMath.lerp(0.35f, 0.85f, BeamMath.rand01()) * (0.75f + 0.55f * s)
            val c = if (BeamMath.rand01() < 0.35f) SjBeam.CORE_COLOR else SjBeam.GLOW_COLOR
            engine.addSmoothParticle(at, vel, size, bright, dur, c)
        }
    }

    private fun emitSmoke(
        engine: CombatEngineAPI, amount: Float, start: Vector2f, facing: Float, length: Float,
        s: Float, coreWidth: Float, glowWidth: Float,
    ) {
        val particleRatePerSec = (length / 50f) * PARTICLES_PER_50SU_PER_SEC * (0.65f + 0.85f * s)
        val ratePerSec = (particleRatePerSec * SMOKE_RATE_MUL).coerceAtLeast(0f)
        smokeAcc += ratePerSec * amount
        val count = smokeAcc.toInt().coerceAtMost(SMOKE_MAX_PER_FRAME)
        if (count > 0) smokeAcc -= count
        if (count <= 0) return

        val dir = Vector2f(BeamMath.facingUnitX(facing), BeamMath.facingUnitY(facing))
        val perp = Vector2f(-dir.y, dir.x)
        val baseSpread = ((glowWidth * 0.5f * SMOKE_LATERAL_MUL) + (coreWidth * 0.25f)).coerceAtLeast(10f)

        for (i in 0 until count) {
            val t = if (BeamMath.rand01() < 0.65f) BeamMath.rand01().pow(0.55f) else BeamMath.rand01()
            val along = length * t
            val lateral = BeamMath.randSigned01() * baseSpread * (0.75f + 0.45f * s)
            val at = Vector2f(start.x + dir.x * along + perp.x * lateral, start.y + dir.y * along + perp.y * lateral)

            val sign = if ((smokeIndex++ and 1) == 0) 1f else -1f
            val speed = BeamMath.lerp(SMOKE_SPEED_MIN, SMOKE_SPEED_MAX, BeamMath.rand01()) * (0.65f + 0.45f * s)
            val vel = Vector2f(perp.x * speed * SMOKE_SIDE_SPEED_MUL * sign, perp.y * speed * SMOKE_SIDE_SPEED_MUL * sign)

            val dur = BeamMath.lerp(SMOKE_DUR_MIN, SMOKE_DUR_MAX, BeamMath.rand01())
            val size = BeamMath.lerp(SMOKE_SIZE_MIN, SMOKE_SIZE_MAX, BeamMath.rand01()) * (0.85f + 0.35f * s)
            val opacity = BeamMath.lerp(SMOKE_OPACITY_MIN, SMOKE_OPACITY_MAX, BeamMath.rand01()) * (0.85f + 0.25f * s)
            val endSizeMult = BeamMath.lerp(SMOKE_END_SIZE_MUL_MIN, SMOKE_END_SIZE_MUL_MAX, BeamMath.rand01())
            val inDur = (dur * SMOKE_IN_FRAC).coerceAtLeast(0.01f)
            val fullDur = (dur * SMOKE_FULL_FRAC).coerceAtLeast(0.01f)
            val outDur = (dur * SMOKE_OUT_FRAC).coerceAtLeast(0.01f)
            val alpha = (SMOKE_COLOR_ALPHA.toFloat() * opacity).toInt().coerceIn(0, 255)
            val base = SjBeam.AMBIENT_SMOKE_BASE_COLOR
            val c = Color(base.red, base.green, base.blue, alpha)
            engine.addNebulaSmokeParticle(at, vel, size, endSizeMult, inDur, fullDur, outDur, c)
        }
    }

    companion object {
        const val RENDER_ORDER = 30
        private const val PARTICLES_PER_50SU_PER_SEC = 2.0f
        private const val PARTICLES_MAX_PER_FRAME = 16
        private const val PARTICLE_LATERAL_MUL = 0.95f
        private const val PARTICLE_SPEED_MIN = 16f
        private const val PARTICLE_SPEED_MAX = 84f
        private const val PARTICLE_SIZE_MIN = 8f
        private const val PARTICLE_SIZE_MAX = 18f
        private const val PARTICLE_DUR_MIN = 0.22f
        private const val PARTICLE_DUR_MAX = 0.55f

        private const val SMOKE_RATE_MUL = (1f / 3f)
        private const val SMOKE_MAX_PER_FRAME = 9
        private const val SMOKE_LATERAL_MUL = 1.30f
        private const val SMOKE_SIDE_SPEED_MUL = 0.55f
        private const val SMOKE_COLOR_ALPHA = 95
        private const val SMOKE_END_SIZE_MUL_MIN = 1.35f
        private const val SMOKE_END_SIZE_MUL_MAX = 2.10f
        private const val SMOKE_IN_FRAC = 0.12f
        private const val SMOKE_FULL_FRAC = 0.22f
        private const val SMOKE_OUT_FRAC = 0.66f
        private const val SMOKE_SPEED_MIN = 6f
        private const val SMOKE_SPEED_MAX = 42f
        private const val SMOKE_SIZE_MIN = 18f
        private const val SMOKE_SIZE_MAX = 52f
        private const val SMOKE_DUR_MIN = 0.55f
        private const val SMOKE_DUR_MAX = 1.35f
        private const val SMOKE_OPACITY_MIN = 0.25f
        private const val SMOKE_OPACITY_MAX = 0.55f
    }
}

// ============================ 炮口散射（粒子 + 短束光锥） ============================

/**
 * 炮口“散射”粒子 + 短束光锥（旧 emitMuzzleFx）。粒子直接抛引擎；短束光锥用 tapered [TrailEntity]，其“淡出缓慢缩小”
 * 由旧代码每束一个 `BaseEveryFrameCombatPlugin` 承担——这里改为**节点内保留列表**逐帧收缩（与 GravityCollapse 的
 * GrowingCone 同法，去掉一次性 plugin）。仅 firing 时散射；保留列表在收缩完/被回收后剔除。
 */
class StellarJetMuzzleComponent(
    id: String,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    private data class ShrinkingCone(
        val entity: TrailEntity, val start: Vector2f, val facing: Float, val createdAt: Float,
        val length: Float, val baseWidth: Float, val tipWidth: Float,
    )

    private var particleAcc = 0f
    private var sprayAcc = 0f
    private var sprayIndex = (Math.random() * 997).toInt()
    private val shrinkingCones = ArrayList<ShrinkingCone>()
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        val now = engine.getTotalElapsedTime(false)
        advanceShrinkingCones(now)
        if (!frame.active) return

        val s = frame.intensity.coerceIn(0f, 1f)
        val coreWidth = SjBeam.coreWidth(ctx.host.let { it as BeamHost }.baseWidth, s)
        emitParticles(engine, amount, frame.origin, frame.facing, s)
        emitSprayCones(engine, amount, now, frame.origin, frame.facing, s, coreWidth)
    }

    override fun onDetachSelf() {
        shrinkingCones.forEach { it.entity.delete() }
        shrinkingCones.clear()
    }

    private fun sprites(engine: CombatEngineAPI): Pair<SpriteAPI, SpriteAPI>? {
        sprites?.let { return it }
        BoxUtilCombatVfx.ensureReady(engine)
        return BeamSprites.load()?.also { sprites = it }
    }

    private fun emitParticles(engine: CombatEngineAPI, amount: Float, start: Vector2f, facing: Float, s: Float) {
        val rate = (PARTICLES_PER_SEC * (0.35f + 0.65f * s)).coerceAtLeast(0f)
        particleAcc += rate * amount
        val count = particleAcc.toInt().coerceAtMost(PARTICLES_MAX_PER_FRAME)
        if (count > 0) particleAcc -= count

        for (i in 0 until count) {
            val ang = facing + BeamMath.randSigned01() * 0.5f * (CONE_ARC_DEG * (0.65f + 0.35f * (1f - s)))
            val rad = Math.toRadians(ang.toDouble())
            val speed = BeamMath.lerp(SPEED_MIN, SPEED_MAX, BeamMath.rand01()) * (0.8f + 0.6f * s)
            val vel = Vector2f(cos(rad).toFloat() * speed, sin(rad).toFloat() * speed)
            val size = BeamMath.lerp(SIZE_MIN, SIZE_MAX, BeamMath.rand01()) * (0.85f + 0.35f * s)
            val dur = BeamMath.lerp(DUR_MIN, DUR_MAX, BeamMath.rand01())
            val bright = BeamMath.lerp(0.75f, 1.45f, BeamMath.rand01())
            engine.addSmoothParticle(start, vel, size, bright, dur, SjBeam.GLOW_COLOR)
        }
    }

    private fun emitSprayCones(engine: CombatEngineAPI, amount: Float, now: Float, start: Vector2f, facing: Float, s: Float, coreWidth: Float) {
        val rate = (SPRAY_BEAMS_PER_SEC * (0.25f + 0.75f * s)).coerceAtLeast(0f)
        sprayAcc += rate * amount
        val count = sprayAcc.toInt().coerceAtMost(SPRAY_MAX_PER_FRAME)
        if (count > 0) sprayAcc -= count
        if (count <= 0) return
        val sprite = sprites(engine) ?: return

        for (i in 0 until count) {
            val idx = sprayIndex++
            val sign = if ((idx and 1) == 0) 1f else -1f
            val halfArc = SPRAY_ARC_DEG * 0.5f
            val slots = SPRAY_ANGLE_SLOTS
            val slot = (idx / 2) % slots
            val slotT = (slot.toFloat() + 0.5f) / slots.toFloat()
            val mag = halfArc * slotT
            val jitter = BeamMath.randSigned01() * (halfArc / slots.toFloat()) * SPRAY_ANGLE_JITTER_MUL
            val ang = facing + sign * mag + jitter
            val len = BeamMath.lerp(SPRAY_LEN_MIN, SPRAY_LEN_MAX, BeamMath.rand01())
            val baseW = (coreWidth * (0.55f + 0.25f * s)).coerceAtLeast(3.5f) * 2.0f
            val tipW = (baseW * 0.10f).coerceAtLeast(1.2f)

            val e = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine, location = start, facing = ang, length = len, baseWidth = baseW, tipWidth = tipW,
                coreColor = SjBeam.CORE_COLOR, fringeColor = SjBeam.GLOW_COLOR, coreSprite = sprite.first, fringeSprite = sprite.second,
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, full = 0.05f,
                baseAlphaMul = 0.28f, tipAlphaMul = 0.04f, baseEmissiveAlphaMul = 2.10f, tipEmissiveAlphaMul = 0.55f, mixPower = 3.2f,
            ) ?: continue

            e.setGlobalTimer(0f, SPRAY_FULL, SPRAY_FADE_OUT)
            initFlowParams(e, -520f * (0.6f + 0.5f * s))
            shrinkingCones.add(ShrinkingCone(e, Vector2f(start), ang, now, len, baseW, tipW))
        }
    }

    /** 短束光锥：过 full 期后逐帧“缓慢变小”（前期慢、末尾快），位置/朝向定格为炮口残影。 */
    private fun advanceShrinkingCones(now: Float) {
        if (shrinkingCones.isEmpty()) return
        val total = (SPRAY_FULL + SPRAY_FADE_OUT).coerceAtLeast(0.01f)
        val it = shrinkingCones.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val e = c.entity
            val elapsed = now - c.createdAt
            if (e.hasDelete() || e.isGlobalTimerOver || elapsed >= total + 0.10f) {
                if (e.hasDelete()) it.remove()
                continue
            }
            if (elapsed <= SPRAY_FULL) continue

            val fadeT = ((elapsed - SPRAY_FULL) / SPRAY_FADE_OUT).coerceIn(0f, 1f)
            val shrink = (1f - fadeT).pow(0.70f).coerceIn(0f, 1f)
            val sNow = (SPRAY_SHRINK_MIN + (1f - SPRAY_SHRINK_MIN) * shrink).coerceIn(0f, 1f)

            val nodes = e.nodes
            if (nodes != null && nodes.size >= 2) {
                nodes[0].set(0f, 0f)
                nodes[1].set(c.length * sNow, 0f)
                e.setNodeRefreshIndex(0)
                e.setNodeRefreshAllFromCurrentIndex()
                e.submitNodes()
            }
            e.setStartWidth(c.baseWidth * sNow)
            e.setEndWidth(c.tipWidth * sNow)
            e.setStateVanilla(c.start, c.facing)
        }
    }

    private fun initFlowParams(e: TrailEntity, textureSpeed: Float) {
        e.setTexturePixels(512f)
        e.setTextureSpeed(textureSpeed)
        e.setFlowWhenPaused(false)
        e.setUVOffset((Math.random().toFloat() * 2f) - 1f)
        e.setJitterPower(0.03f)
        e.setFlick(false)
        e.setSyncFlick(false)
        e.setFillStartAlpha(0.22f)
        e.setFillStartFactor(0.018f)
        e.setFillEndAlpha(0.18f)
        e.setFillEndFactor(0.024f)
    }

    companion object {
        const val RENDER_ORDER = 120
        private const val PARTICLES_PER_SEC = 150f
        private const val PARTICLES_MAX_PER_FRAME = 16
        private const val CONE_ARC_DEG = 110f
        private const val SPEED_MIN = 110f
        private const val SPEED_MAX = 360f
        private const val SIZE_MIN = 36f
        private const val SIZE_MAX = 88f
        private const val DUR_MIN = 0.10f
        private const val DUR_MAX = 0.26f

        private const val SPRAY_BEAMS_PER_SEC = 28f
        private const val SPRAY_MAX_PER_FRAME = 5
        private const val SPRAY_ARC_DEG = 92f
        private const val SPRAY_LEN_MIN = 140f
        private const val SPRAY_LEN_MAX = 360f
        private const val SPRAY_ANGLE_SLOTS = 6
        private const val SPRAY_ANGLE_JITTER_MUL = 0.35f
        private const val SPRAY_FULL = 0.08f
        private const val SPRAY_FADE_OUT = 0.28f
        private const val SPRAY_SHRINK_MIN = 0.12f
    }
}

// ============================ 命中端散射（火花 + 侧向弧线，纯视觉） ============================

/**
 * 命中点“爆闪/火花 + 多条侧向弧线”（旧 emitImpactFx）。**纯视觉**，读 [RenderContext.frame] 的 endpoint/hitTarget/
 * isShieldHit；周期性 EMP 伤害弧（applyDamage/选点）是 gameplay，不在此，留在宿主插件。仅 firing 且命中在场时散射。
 */
class StellarJetImpactComponent(
    id: String,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER) {

    private var particleAcc = 0f
    private var arcAcc = 0f

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        if (!frame.active) return
        val hitTarget = frame.hitTarget ?: return
        if (!engine.isEntityInPlay(hitTarget)) return
        val point = frame.endpoint ?: return
        val s = frame.intensity.coerceIn(0f, 1f)

        emitSparks(engine, amount, point, s)
        emitArcs(engine, amount, point, frame.facing, s, frame.isShieldHit, hitTarget)
    }

    private fun emitSparks(engine: CombatEngineAPI, amount: Float, point: Vector2f, s: Float) {
        val rate = (PARTICLES_PER_SEC * (0.35f + 0.65f * s)).coerceAtLeast(0f)
        particleAcc += rate * amount
        val count = particleAcc.toInt().coerceAtMost(PARTICLES_MAX_PER_FRAME)
        if (count > 0) particleAcc -= count

        val spread = PARTICLE_SPREAD * (0.75f + 0.55f * s)
        for (i in 0 until count) {
            val ang = BeamMath.rand01() * 360f
            val rad = Math.toRadians(ang.toDouble())
            val speed = BeamMath.lerp(SPEED_MIN, SPEED_MAX, BeamMath.rand01()) * (0.8f + 0.6f * s)
            val vel = Vector2f(cos(rad).toFloat() * speed, sin(rad).toFloat() * speed)
            val size = BeamMath.lerp(SIZE_MIN, SIZE_MAX, BeamMath.rand01()) * (0.85f + 0.45f * s)
            val dur = BeamMath.lerp(DUR_MIN, DUR_MAX, BeamMath.rand01())
            val bright = BeamMath.lerp(0.65f, 1.25f, BeamMath.rand01())
            val at = Vector2f(point.x + BeamMath.randSigned01() * 0.5f * spread, point.y + BeamMath.randSigned01() * 0.5f * spread)
            engine.addHitParticle(at, vel, size * 0.65f, bright, dur, SjBeam.CORE_COLOR)
            engine.addSmoothParticle(at, vel, size, bright * 0.95f, dur * 1.1f, SjBeam.GLOW_COLOR)
        }
    }

    private fun emitArcs(
        engine: CombatEngineAPI, amount: Float, point: Vector2f, facing: Float, s: Float,
        isShieldHit: Boolean, hitTarget: com.fs.starfarer.api.combat.CombatEntityAPI,
    ) {
        val rate = (ARCS_PER_SEC * (0.25f + 0.75f * s)).coerceAtLeast(0f)
        arcAcc += rate * amount
        val count = arcAcc.toInt().coerceAtMost(ARCS_MAX_PER_FRAME)
        if (count > 0) arcAcc -= count

        for (i in 0 until count) {
            val ang = facing + 90f + BeamMath.randSigned01() * 0.5f * 220f
            val rad = Math.toRadians(ang.toDouble())
            val r = BeamMath.lerp(ARC_RADIUS_MIN, ARC_RADIUS_MAX, BeamMath.rand01()) * (0.75f + 0.45f * s)
            val end = Vector2f(point.x + cos(rad).toFloat() * r, point.y + sin(rad).toFloat() * r)
            val width = BeamMath.lerp(ARC_WIDTH_MIN, ARC_WIDTH_MAX, BeamMath.rand01()) * (0.8f + 0.4f * s)
            engine.spawnEmpArcVisual(point, hitTarget, end, hitTarget, width, SjBeam.GLOW_COLOR, if (isShieldHit) SjBeam.GLOW_COLOR else SjBeam.CORE_COLOR)
        }
    }

    companion object {
        const val RENDER_ORDER = 40
        private const val PARTICLES_PER_SEC = 95f
        private const val PARTICLES_MAX_PER_FRAME = 12
        private const val PARTICLE_SPREAD = 14f
        private const val SPEED_MIN = 60f
        private const val SPEED_MAX = 320f
        private const val SIZE_MIN = 10f
        private const val SIZE_MAX = 28f
        private const val DUR_MIN = 0.08f
        private const val DUR_MAX = 0.22f

        private const val ARCS_PER_SEC = 22f
        private const val ARCS_MAX_PER_FRAME = 3
        private const val ARC_RADIUS_MIN = 35f
        private const val ARC_RADIUS_MAX = 120f
        private const val ARC_WIDTH_MIN = 8f
        private const val ARC_WIDTH_MAX = 16f
    }
}
