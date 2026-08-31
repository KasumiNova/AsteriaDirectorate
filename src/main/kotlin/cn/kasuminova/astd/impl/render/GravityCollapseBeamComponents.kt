package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.TaperedBeamTrailsVfx
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamSpriteRingRenderer
import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 引力坍缩炮（Gravity Collapse）光束的节点族——把旧 `GravityCollapseBeamVfx` 那个 1170 行、7 类效果混杂、
 * 40+ 空 catch 的巨类拆成一效果一节点（迁移计划 §5）。束体 4 件套走公共 [BeamCoreComponent]；本文件是其余 5 类
 * 独立效果 + 起手函数。几何统一读 [RenderContext.frame]（intensity=level 控宽/密度，fadeMul 控淡出包络）。
 */

/** 引力坍缩炮共用调色与亮度（旧 `GravityCollapseBeamVfx.companion` 的颜色族）。 */
internal object GcBeam {
    const val BRIGHTNESS_MUL = 0.65f
    const val WHITE_CORE_MUL = 1.09375f

    val CORE_COLOR = Color(255, 45, 45, (235f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))
    val GLOW_COLOR = Color(255, 25, 25, (190f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))
    val HOT_COLOR = Color(255, 70, 70, (220f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))
    val BEAM_CORE_WHITE = Color(255, 255, 255, (225f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))
    val BEAM_FRINGE_PINK = Color(255, 130, 130, (200f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))
    val AMBIENT_NEBULA_BASE_COLOR = Color(255, 55, 55)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun lerpF(a: Float, b: Float, t: Float): Float = lerp(a, b, t)

    /** GravityCollapse 束体给公共 [BeamCoreComponent] 的规格：白芯+粉边核心、暗红辉光、tip 0.80 taper、
     *  淡出由 fadeMul 整体收束（变细+变淡）、固定色（不逐帧 lerp）、不施加填充淡化。 */
    fun coreSpec(): BeamCoreSpec {
        // coreW = baseWidth×(9 + 8×level)（baseWidth 由宿主传 s×wMul）；glowW = coreW×2.15。
        val baseAlphaCore = 0.62f * BRIGHTNESS_MUL
        val baseEmCore = 2.85f * BRIGHTNESS_MUL * WHITE_CORE_MUL
        val baseAlphaGlow = 0.18f * BRIGHTNESS_MUL
        val baseEmGlow = 1.95f * BRIGHTNESS_MUL
        return BeamCoreSpec(
            coreWidthBase = 9f, coreWidthRamp = 8f, coreWidthMin = 0f,
            glowWidthMul = 2.15f, glowWidthRamp = 0f, glowWidthMin = 0f,
            bodyWidthBase = 1f, bodyWidthRamp = 0f, tipWidthMul = 0.80f,
            fadeMulScalesWidth = true, fadeMulScalesAlpha = true,
            lerpColorPerFrame = false, applyEndFade = false,
            pieces = listOf(
                BeamCorePieceSpec(false, false, BeamPalette.CORE, baseAlphaCore, baseEmCore, 3.25f, 0f, BEAM_CORE_WHITE, BEAM_FRINGE_PINK),
                BeamCorePieceSpec(true, false, BeamPalette.CORE, baseAlphaCore * 0.62f, baseEmCore * 0.62f, 3.25f, 0f, BEAM_CORE_WHITE, BEAM_FRINGE_PINK),
                BeamCorePieceSpec(false, true, BeamPalette.GLOW, baseAlphaGlow, baseEmGlow, 3.60f, 0f, GLOW_COLOR, HOT_COLOR),
                BeamCorePieceSpec(true, true, BeamPalette.GLOW, baseAlphaGlow * 0.62f, baseEmGlow * 0.62f, 3.60f, 0f, GLOW_COLOR, HOT_COLOR),
            ),
        )
    }
}

/** 由本帧几何（origin/facing/length）重建一条 [BeamLineUtil.BeamLine]，供各 GC 节点取 dirUnit/perpUnit。 */
internal fun FrameState.beamLine(): BeamLineUtil.BeamLine? {
    val rad = Math.toRadians(facing.toDouble())
    val dir = Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
    val to = Vector2f(origin.x + dir.x * length, origin.y + dir.y * length)
    return BeamLineUtil.fromPoints(origin, to)
}

private fun beamCoreSprites(): Pair<SpriteAPI, SpriteAPI>? = BeamSprites.load()

// ============================ 沿束光圈环（main + sub + muzzle） ============================

/**
 * 沿束前进的光圈环 + 炮口脉冲环（旧 advance:372-486 的三处 upsert）。后端复用 [AttachedBeamSpriteRingRenderer]
 * （按 key 持久管理实例），本节点只负责每帧 upsert 与 detach 时 remove。fadeMul≤0.001 时不 upsert（对齐旧 ringFade 门控）。
 */
class BeamRingComponent(
    id: String,
    private val scale: Float = 1f,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER) {

    private val log = Global.getLogger(BeamRingComponent::class.java)
    private val keyBase = "astd_stasis_collapse_nozzle_rings:" + System.identityHashCode(this)
    private var warnedUpsert = false

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val fade = ctx.frame.fadeMul.coerceIn(0f, 1f)
        val ringFade = (fade * fade).coerceIn(0f, 1f)
        if (ringFade <= 0.001f) return
        val line = ctx.frame.beamLine() ?: return
        val s = scale.coerceIn(0.35f, 2.25f)

        val ringEndFade = max(72f * s, min(line.length * 0.18f, 180f * s))
        val ringSpawnFade = 14f * s
        val muzzleOffset = min(70f * s, max(18f * s, line.length * 0.12f))
        val ringMaxInstances = if (line.length > 2200f) 128 else 112

        try {
            AttachedBeamSpriteRingRenderer.upsert(
                engine = engine, key = "$keyBase:main", line = line,
                spec = AttachedBeamSpriteRingRenderer.Spec(
                    mode = AttachedBeamSpriteRingRenderer.Mode.PERMANENT,
                    spacing = RING_SPACING * s, travelSpeed = RING_TRAVEL_SPEED,
                    aSideHalf = 44f * RING_BASE_SIZE_MUL * RING_VISIBILITY_SIZE_MUL * s,
                    bAlongHalf = 20f * RING_BASE_SIZE_MUL * RING_VISIBILITY_SIZE_MUL * s,
                    color = Color(255, 45, 45, (110f * GcBeam.BRIGHTNESS_MUL * ringFade).toInt().coerceIn(0, 255)),
                    headScale = RING_START_SCALE, tailScale = 1.0f,
                    endFadeDistance = ringEndFade, spawnFadeInDistance = ringSpawnFade,
                    muzzleExtraScaleMin = 1.0f, muzzleExtraScaleMax = 1.0f,
                    glowPower = 1.35f, layer = CombatEngineLayers.ABOVE_PARTICLES, maxInstances = ringMaxInstances,
                ),
            )

            val subA = 44f * RING_BASE_SIZE_MUL * SUB_RING_SCALE * RING_VISIBILITY_SIZE_MUL * s
            val subB = 20f * RING_BASE_SIZE_MUL * SUB_RING_SCALE * RING_VISIBILITY_SIZE_MUL * s
            AttachedBeamSpriteRingRenderer.upsert(
                engine = engine, key = "$keyBase:sub130", line = line,
                spec = AttachedBeamSpriteRingRenderer.Spec(
                    mode = AttachedBeamSpriteRingRenderer.Mode.PERMANENT,
                    spacing = RING_SPACING * s, travelSpeed = RING_TRAVEL_SPEED,
                    aSideHalf = subA, bAlongHalf = subB, distanceOffset = SUB_RING_DISTANCE_OFFSET * s,
                    color = Color(255, 45, 45, (88f * GcBeam.BRIGHTNESS_MUL * ringFade).toInt().coerceIn(0, 255)),
                    headScale = RING_START_SCALE, tailScale = 1.0f,
                    endFadeDistance = ringEndFade, spawnFadeInDistance = ringSpawnFade,
                    muzzleExtraScaleMin = 1.0f, muzzleExtraScaleMax = 1.0f,
                    glowPower = 1.25f, layer = CombatEngineLayers.ABOVE_PARTICLES, maxInstances = ringMaxInstances,
                ),
            )

            AttachedBeamSpriteRingRenderer.upsert(
                engine = engine, key = "$keyBase:muzzle", line = line,
                spec = AttachedBeamSpriteRingRenderer.Spec(
                    mode = AttachedBeamSpriteRingRenderer.Mode.MUZZLE_PULSE,
                    spacing = RING_SPACING * s, travelSpeed = 0f,
                    aSideHalf = 44f * RING_BASE_SIZE_MUL * RING_VISIBILITY_SIZE_MUL * s,
                    bAlongHalf = 20f * RING_BASE_SIZE_MUL * RING_VISIBILITY_SIZE_MUL * s,
                    distanceOffset = muzzleOffset,
                    color = Color(255, 45, 45, (110f * GcBeam.BRIGHTNESS_MUL * ringFade).toInt().coerceIn(0, 255)),
                    headScale = RING_START_SCALE, tailScale = RING_START_SCALE,
                    endFadeDistance = max(56f * s, line.length * 0.10f), spawnFadeInDistance = 10f * s,
                    muzzleExtraScaleMin = 0.95f, muzzleExtraScaleMax = 1.10f,
                    pulseLifetime = 0.50f, pulseStartScale = 0.28f, pulseEndScale = 2.55f, pulseScaleExponent = 0.33f,
                    muzzleSpreadDistance = 0f, glowPower = 1.70f,
                    layer = CombatEngineLayers.ABOVE_PARTICLES, maxInstances = 1,
                ),
            )
        } catch (t: Throwable) {
            if (!warnedUpsert) {
                warnedUpsert = true
                log.warn("AttachedBeamSpriteRingRenderer.upsert 失败，光圈环将不可见。", t)
            }
        }
    }

    override fun onDetachSelf() {
        val engine = Global.getCombatEngine() ?: return
        AttachedBeamSpriteRingRenderer.remove(engine, "$keyBase:main")
        AttachedBeamSpriteRingRenderer.remove(engine, "$keyBase:sub130")
        AttachedBeamSpriteRingRenderer.remove(engine, "$keyBase:muzzle")
    }

    companion object {
        const val RENDER_ORDER = 40
        private const val RING_SPACING = 150f
        private const val RING_TRAVEL_SPEED = 260f
        private const val RING_BASE_SIZE_MUL = 0.65f
        private const val RING_VISIBILITY_SIZE_MUL = 1.75f
        private const val RING_START_SCALE = 2.0f
        private const val SUB_RING_SCALE = 0.70f
        private const val SUB_RING_DISTANCE_OFFSET = -20f
    }
}

// ============================ 炮口光锥（起手 burst + 持续 spray + 几何渐长） ============================

/**
 * 炮口光锥（旧 spawnMuzzleConeBurst / spawnMuzzleConeSpray / advanceGrowingCones，三段散落合为一节点）：
 * - 起手一次性 burst（可选，[startupBurst]=true 时含爆炸/火花/立即锥；weapon 宿主用，system 宿主自绘故置 false）；
 * - firing 期间周期性 spray 出「几何渐长」的短锥（内部保留列表逐帧插值长/宽/alpha）。
 */
class BeamMuzzleComponent(
    id: String,
    private val scale: Float = 1f,
    private val startupBurst: Boolean = true,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    private data class GrowingCone(
        val entity: TrailEntity, val facing: Float, val createdAt: Float, val growDuration: Float,
        val targetLength: Float, val targetBaseWidth: Float, val targetTipWidth: Float,
        val baseAlpha: Float, val tipAlpha: Float, val baseEmissive: Float, val tipEmissive: Float,
    )

    private val growingCones = ArrayList<GrowingCone>()
    private val muzzleConeInterval = IntervalUtil(0.333f, 0.333f)
    private var startedAt: Float? = null
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        startedAt = engine.getTotalElapsedTime(false)
        if (startupBurst) {
            val line = ctx.frame.beamLine()
            val facing = line?.facing ?: ctx.frame.facing
            spawnStartupBurst(engine, ctx.frame.origin, facing, ctx.frame.intensity.coerceIn(0f, 1f))
        }
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        val line = frame.beamLine() ?: return
        val now = engine.getTotalElapsedTime(false)

        advanceGrowingCones(now, line.from)

        val level = frame.intensity.coerceIn(0f, 1f)
        val fade = frame.fadeMul.coerceIn(0f, 1f)
        if (fade > 0.01f && level > 0.01f) {
            val sa = startedAt
            if (sa == null || now - sa >= 0.20f) {
                muzzleConeInterval.advance(amount)
                if (muzzleConeInterval.intervalElapsed()) {
                    spawnMuzzleConeSpray(engine, now, line.from, line.facing, level, fade)
                }
            }
        }
    }

    override fun onDetachSelf() {
        growingCones.forEach { it.entity.delete() }
        growingCones.clear()
    }

    private fun sprites(engine: CombatEngineAPI): Pair<SpriteAPI, SpriteAPI>? {
        sprites?.let { return it }
        BoxUtilCombatVfx.ensureReady(engine)
        return beamCoreSprites()?.also { sprites = it }
    }

    private fun spawnStartupBurst(engine: CombatEngineAPI, from: Vector2f, facing: Float, level: Float) {
        val t = level.coerceIn(0f, 1f)
        val s = scale.coerceIn(0.35f, 2.25f)
        val bm = GcBeam.BRIGHTNESS_MUL
        engine.spawnExplosion(from, Vector2f(0f, 0f), GcBeam.CORE_COLOR, GcBeam.lerpF(140f, 240f, t) * bm * s, 0.22f)
        engine.addSmoothParticle(from, Vector2f(0f, 0f), GcBeam.lerpF(220f, 380f, t) * bm * s, 1.25f * bm, 0.28f, GcBeam.GLOW_COLOR)

        repeat(14) {
            val ang = facing + MathUtils.getRandomNumberInRange(-10f, 10f)
            val speed = MathUtils.getRandomNumberInRange(220f, 760f) * (0.65f + 0.65f * t)
            val vel = MathUtils.getPointOnCircumference(Vector2f(0f, 0f), speed, ang)
            val size = MathUtils.getRandomNumberInRange(18f, 44f) * s
            val dur = MathUtils.getRandomNumberInRange(0.10f, 0.22f)
            val c = if (Math.random() < 0.35) GcBeam.HOT_COLOR else GcBeam.CORE_COLOR
            engine.addSmoothParticle(from, vel, size * bm, 1.15f * bm, dur, c)
        }

        spawnMuzzleConeBurst(engine, from, facing, t)
    }

    private fun spawnMuzzleConeBurst(engine: CombatEngineAPI, center: Vector2f, facing: Float, level: Float) {
        val t = level.coerceIn(0f, 1f)
        val s = scale.coerceIn(0.35f, 2.25f)
        val sprite = sprites(engine) ?: return

        val count = GcBeam.lerpF(MUZZLE_CONE_COUNT_MIN.toFloat(), MUZZLE_CONE_COUNT_MAX.toFloat(), t).toInt()
            .coerceIn(MUZZLE_CONE_COUNT_MIN, MUZZLE_CONE_COUNT_MAX)
        val halfArc = MUZZLE_CONE_ARC_DEG * 0.5f
        val signs = twoSidedSigns(count)

        repeat(count) {
            val ang = facing + signs[it] * MathUtils.getRandomNumberInRange(0f, halfArc) + MathUtils.getRandomNumberInRange(-4.0f, 4.0f)
            val len = GcBeam.lerpF(MUZZLE_CONE_LEN_MIN, MUZZLE_CONE_LEN_MAX, Math.random().toFloat()) * (0.85f + 0.35f * t) * s * MUZZLE_CONE_GEOM_MUL
            val baseW = GcBeam.lerpF(70f, 125f, t) * 0.90f * 0.60f * s * MUZZLE_CONE_GEOM_MUL
            val tipW = (baseW * 0.08f).coerceAtLeast(1.2f * s * MUZZLE_CONE_GEOM_MUL)
            val e = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine, location = center, facing = ang, length = len, baseWidth = baseW, tipWidth = tipW,
                coreColor = GcBeam.HOT_COLOR, fringeColor = GcBeam.CORE_COLOR, coreSprite = sprite.first, fringeSprite = sprite.second,
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, full = MUZZLE_CONE_FULL,
                baseAlphaMul = 0.22f * GcBeam.BRIGHTNESS_MUL, tipAlphaMul = 0.04f * GcBeam.BRIGHTNESS_MUL,
                baseEmissiveAlphaMul = 2.45f * GcBeam.BRIGHTNESS_MUL, tipEmissiveAlphaMul = 0.55f * GcBeam.BRIGHTNESS_MUL, mixPower = 3.25f,
            )
            e?.setGlobalTimer(0f, MUZZLE_CONE_FULL, MUZZLE_CONE_FADE_OUT)
        }
    }

    private fun spawnMuzzleConeSpray(engine: CombatEngineAPI, now: Float, center: Vector2f, facing: Float, level: Float, fade: Float) {
        val t = (level * fade).coerceIn(0f, 1f)
        if (t <= 0.001f) return
        val s = scale.coerceIn(0.35f, 2.25f)
        val sprite = sprites(engine) ?: return

        val count = 2
        val halfArc = MUZZLE_CONE_ARC_DEG * 0.5f
        val signs = twoSidedSigns(count)

        repeat(count) {
            val ang = facing + signs[it] * MathUtils.getRandomNumberInRange(0f, halfArc) + MathUtils.getRandomNumberInRange(-4.0f, 4.0f)
            val sizeMul = 0.75f
            val len = GcBeam.lerpF(120f, 210f, MathUtils.getRandomNumberInRange(0f, 1f)) * (0.85f + 0.35f * t) * s * sizeMul * MUZZLE_CONE_GEOM_MUL
            val baseW = GcBeam.lerpF(55f, 95f, t) * 0.60f * s * sizeMul * MUZZLE_CONE_GEOM_MUL
            val tipW = (baseW * 0.10f).coerceAtLeast(1.2f * s * MUZZLE_CONE_GEOM_MUL)
            val e = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine, location = center, facing = ang, length = 1f, baseWidth = 1f, tipWidth = 1f,
                coreColor = GcBeam.HOT_COLOR, fringeColor = GcBeam.CORE_COLOR, coreSprite = sprite.first, fringeSprite = sprite.second,
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, full = 9999f,
                baseAlphaMul = 0.12f * GcBeam.BRIGHTNESS_MUL, tipAlphaMul = 0.03f * GcBeam.BRIGHTNESS_MUL,
                baseEmissiveAlphaMul = 1.75f * GcBeam.BRIGHTNESS_MUL, tipEmissiveAlphaMul = 0.45f * GcBeam.BRIGHTNESS_MUL, mixPower = 3.10f,
            ) ?: return@repeat
            e.setGlobalTimer(0f, 0.22f, 0.33f)
            growingCones.add(
                GrowingCone(
                    entity = e, facing = ang, createdAt = now, growDuration = 0.22f,
                    targetLength = len, targetBaseWidth = baseW, targetTipWidth = tipW,
                    baseAlpha = 0.12f * GcBeam.BRIGHTNESS_MUL, tipAlpha = 0.03f * GcBeam.BRIGHTNESS_MUL,
                    baseEmissive = 1.75f * GcBeam.BRIGHTNESS_MUL, tipEmissive = 0.45f * GcBeam.BRIGHTNESS_MUL,
                ),
            )
        }
    }

    private fun advanceGrowingCones(now: Float, muzzle: Vector2f) {
        if (growingCones.isEmpty()) return
        val it = growingCones.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val e = c.entity
            if (e.hasDelete()) { it.remove(); continue }

            val t0 = ((now - c.createdAt) / c.growDuration).coerceIn(0f, 1f)
            val t = (t0 * t0 * (3f - 2f * t0)).coerceIn(0f, 1f)
            val curLen = (1f + (c.targetLength - 1f) * t).coerceAtLeast(1f)
            val curBaseW = (1f + (c.targetBaseWidth - 1f) * t).coerceAtLeast(1f)
            val curTipW = (1f + (c.targetTipWidth - 1f) * t).coerceAtLeast(1f)
            val aBase = (c.baseAlpha * t).coerceIn(0f, 1f)
            val aTip = (c.tipAlpha * t).coerceIn(0f, 1f)
            val eBase = (c.baseEmissive * t).coerceIn(0f, 10f)
            val eTip = (c.tipEmissive * t).coerceIn(0f, 10f)

            val nodes = e.nodes
            if (nodes == null || nodes.size < 2) {
                e.resetNodes()
                e.addNode(Vector2f(0f, 0f))
                e.addNode(Vector2f(curLen, 0f))
                e.submitNodes()
            } else {
                nodes[0].set(0f, 0f)
                nodes[1].set(curLen, 0f)
                e.setNodeRefreshIndex(0)
                e.setNodeRefreshAllFromCurrentIndex()
                e.submitNodes()
            }
            e.setStartWidth(curBaseW)
            e.setEndWidth(curTipW)
            e.setStartColorAlpha(aBase)
            e.setEndColorAlpha(aTip)
            e.setStartEmissiveAlpha(eBase)
            e.setEndEmissiveAlpha(eTip)
            e.setStateVanilla(muzzle, BoxUtilCombatVfx.normalizeFacingDeg(c.facing))
        }
    }

    private fun twoSidedSigns(count: Int): List<Float> {
        val signs = ArrayList<Float>(count)
        if (count >= 2) { signs.add(1f); signs.add(-1f) }
        while (signs.size < count) signs.add(if (Math.random() < 0.5) 1f else -1f)
        signs.shuffle()
        return signs
    }

    companion object {
        const val RENDER_ORDER = 120
        private const val MUZZLE_CONE_ARC_DEG = 92f
        private const val MUZZLE_CONE_LEN_MIN = 160f
        private const val MUZZLE_CONE_LEN_MAX = 280f
        private const val MUZZLE_CONE_COUNT_MIN = 5
        private const val MUZZLE_CONE_COUNT_MAX = 10
        private const val MUZZLE_CONE_FULL = 0.08f
        private const val MUZZLE_CONE_FADE_OUT = 0.42f
        private const val MUZZLE_CONE_GEOM_MUL = 0.60f
    }
}

// ============================ 沿束 ambient nebula ============================

/**
 * 沿束散发的红色 nebula 尘埃（旧 emitAmbientBeamNebula）。纯每帧抛引擎粒子，无常驻句柄；用累积器控频，
 * 密度随束长线性增长、随 fadeMul 收敛。fadeMul≤0.001 或束长<80 时不抛（对齐旧门控）。
 */
class BeamAmbientComponent(
    id: String,
    private val scale: Float = 1f,
    private val beamWidthMul: Float = 1f,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER) {

    private var acc = 0f
    private var index = 0

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        val f = frame.fadeMul.coerceIn(0f, 1f)
        if (f <= 0.001f) return
        val line = frame.beamLine() ?: return
        val length = line.length.coerceAtLeast(0f)
        if (length < 80f) return

        val s = frame.intensity.coerceIn(0f, 1f)
        val wMul = beamWidthMul.coerceIn(0.35f, 1.25f)
        val sc = scale.coerceIn(0.35f, 2.25f)
        val coreWidth = (9f + 8f * s) * sc * wMul
        val glowWidth = coreWidth * 2.15f

        val particleRatePerSec = (length / 50f) * PARTICLES_PER_50SU_PER_SEC * (0.65f + 0.85f * s)
        val ratePerSec = (particleRatePerSec * NEBULA_RATE_MUL * NEBULA_COUNT_MUL * (0.35f + 0.65f * f)).coerceAtLeast(0f)
        acc += ratePerSec * amount
        val count = acc.toInt().coerceAtMost(NEBULA_MAX_PER_FRAME)
        if (count > 0) acc -= count
        if (count <= 0) return

        val dir = line.dirUnit
        val perp = line.perpUnit
        val baseSpread = ((glowWidth * 0.5f * NEBULA_LATERAL_MUL) + (coreWidth * 0.25f)).coerceAtLeast(10f)

        for (i in 0 until count) {
            val t = if (Math.random() < 0.65) MathUtils.getRandomNumberInRange(0f, 1f).pow(0.55f) else MathUtils.getRandomNumberInRange(0f, 1f)
            val along = length * t
            val lateral = (MathUtils.getRandomNumberInRange(0f, 1f) - 0.5f) * 2f * baseSpread * (0.75f + 0.45f * s)
            val at = Vector2f(line.from.x + dir.x * along + perp.x * lateral, line.from.y + dir.y * along + perp.y * lateral)

            val sign = if ((index++ and 1) == 0) 1f else -1f
            val speed = GcBeam.lerpF(NEBULA_SPEED_MIN, NEBULA_SPEED_MAX, MathUtils.getRandomNumberInRange(0f, 1f)) * (0.65f + 0.45f * s)
            val vel = Vector2f(perp.x * speed * NEBULA_SIDE_SPEED_MUL * sign, perp.y * speed * NEBULA_SIDE_SPEED_MUL * sign)

            val size = GcBeam.lerpF(NEBULA_SIZE_MIN, NEBULA_SIZE_MAX, MathUtils.getRandomNumberInRange(0f, 1f)) * (0.85f + 0.35f * s)
            val dur = GcBeam.lerpF(NEBULA_DUR_MIN, NEBULA_DUR_MAX, MathUtils.getRandomNumberInRange(0f, 1f))
            val opacity = GcBeam.lerpF(NEBULA_OPACITY_MIN, NEBULA_OPACITY_MAX, MathUtils.getRandomNumberInRange(0f, 1f)) * (0.85f + 0.25f * s) * f
            val endSizeMult = GcBeam.lerpF(NEBULA_END_SIZE_MUL_MIN, NEBULA_END_SIZE_MUL_MAX, MathUtils.getRandomNumberInRange(0f, 1f))
            val inDur = (dur * NEBULA_IN_FRAC).coerceAtLeast(0.01f)
            val fullDur = (dur * NEBULA_FULL_FRAC).coerceAtLeast(0.01f)
            val outDur = (dur * NEBULA_OUT_FRAC).coerceAtLeast(0.01f)
            val alpha = (NEBULA_COLOR_ALPHA.toFloat() * opacity).toInt().coerceIn(0, 255)
            val c = Color(GcBeam.AMBIENT_NEBULA_BASE_COLOR.red, GcBeam.AMBIENT_NEBULA_BASE_COLOR.green, GcBeam.AMBIENT_NEBULA_BASE_COLOR.blue, alpha)
            engine.addNebulaSmokeParticle(at, vel, size, endSizeMult, inDur, fullDur, outDur, c)
        }
    }

    companion object {
        const val RENDER_ORDER = 30
        private const val PARTICLES_PER_50SU_PER_SEC = 2.0f
        private const val NEBULA_RATE_MUL = (1f / 3f)
        private const val NEBULA_COUNT_MUL = 2.0f
        private const val NEBULA_MAX_PER_FRAME = 18
        private const val NEBULA_LATERAL_MUL = 1.30f
        private const val NEBULA_SIDE_SPEED_MUL = 0.55f
        private const val NEBULA_COLOR_ALPHA = 143
        private const val NEBULA_END_SIZE_MUL_MIN = 1.35f
        private const val NEBULA_END_SIZE_MUL_MAX = 2.10f
        private const val NEBULA_IN_FRAC = 0.12f
        private const val NEBULA_FULL_FRAC = 0.22f
        private const val NEBULA_OUT_FRAC = 0.66f
        private const val NEBULA_SPEED_MIN = 6f
        private const val NEBULA_SPEED_MAX = 42f
        private const val NEBULA_SIZE_MIN = 12f
        private const val NEBULA_SIZE_MAX = 35f
        private const val NEBULA_DUR_MIN = 0.55f
        private const val NEBULA_DUR_MAX = 1.35f
        private const val NEBULA_OPACITY_MIN = 0.25f
        private const val NEBULA_OPACITY_MAX = 0.55f
    }
}

// ============================ 装饰微束 ============================

/**
 * 绕主束的装饰微束（旧 spawnDecorativeMicroBeams）。用 [TaperedBeamTrailsVfx.spawn] 抛短寿命细束（自管生命周期）。
 * 仅在 fadeMul>0.999（满态、非淡出）时按 trailInterval 刷新（对齐旧门控）。
 */
class BeamMicroBeamComponent(
    id: String,
    private val scale: Float = 1f,
    private val beamWidthMul: Float = 1f,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    private val trailInterval = IntervalUtil(TRAIL_REFRESH, TRAIL_REFRESH)

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        trailInterval.advance(amount)
        if (!trailInterval.intervalElapsed()) return
        if (frame.fadeMul.coerceIn(0f, 1f) <= 0.999f) return
        val line = frame.beamLine() ?: return
        spawnMicroBeams(engine, engine.getTotalElapsedTime(false), line, frame.intensity.coerceIn(0f, 1f))
    }

    private fun spawnMicroBeams(engine: CombatEngineAPI, now: Float, line: BeamLineUtil.BeamLine, level: Float) {
        val len = line.length
        if (len < 80f) return
        val s = scale.coerceIn(0.35f, 2.25f)
        val wMul = beamWidthMul.coerceIn(0.35f, 1.25f)

        val segLen = (len * GcBeam.lerpF(0.18f, 0.30f, level)).coerceIn(55f, 220f)
        val amp = GcBeam.lerpF(4f, 10f, level) * s
        val thinCoreW = GcBeam.lerpF(2.2f, 3.6f, level) * s * wMul
        val thinGlowW = thinCoreW * 1.9f

        for (i in 0 until 3) {
            val u0 = ((now * 0.78f) + i * 0.33f) % 1f
            val startDist = (u0 * (len - segLen)).coerceIn(0f, (len - segLen).coerceAtLeast(0f))
            val baseFrom = Vector2f(line.from.x + line.dirUnit.x * startDist, line.from.y + line.dirUnit.y * startDist)
            val baseTo = Vector2f(baseFrom.x + line.dirUnit.x * segLen, baseFrom.y + line.dirUnit.y * segLen)

            val phase = (now * 6.0f) + i * 1.7f
            val off = (sin(phase.toDouble()).toFloat() * amp).coerceIn(-amp, amp)
            for (sign in floatArrayOf(-1f, 1f)) {
                val dx = line.perpUnit.x * off * sign
                val dy = line.perpUnit.y * off * sign
                val f = Vector2f(baseFrom.x + dx, baseFrom.y + dy)
                val t = Vector2f(baseTo.x + dx, baseTo.y + dy)
                TaperedBeamTrailsVfx.spawn(
                    engine = engine, from = f, to = t,
                    coreBaseWidth = thinCoreW, coreTipWidth = thinCoreW * TIP_WIDTH_MUL,
                    glowBaseWidth = thinGlowW, glowTipWidth = thinGlowW * TIP_WIDTH_MUL,
                    params = TaperedBeamTrailsVfx.BeamParams(
                        fadeIn = 0.01f, full = 0.03f, fadeOut = 0.06f, layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                        core = TaperedBeamTrailsVfx.LayerParams(
                            coreColor = GcBeam.HOT_COLOR, fringeColor = GcBeam.CORE_COLOR,
                            baseAlphaMul = 0.08f * GcBeam.BRIGHTNESS_MUL, tipAlphaMul = 0.08f * GcBeam.BRIGHTNESS_MUL,
                            baseEmissiveAlphaMul = 1.35f * GcBeam.BRIGHTNESS_MUL, tipEmissiveAlphaMul = 1.35f * GcBeam.BRIGHTNESS_MUL,
                            mixPower = 3.1f, mirroredUMul = 0f,
                        ),
                        glow = TaperedBeamTrailsVfx.LayerParams(
                            coreColor = GcBeam.CORE_COLOR, fringeColor = GcBeam.GLOW_COLOR,
                            baseAlphaMul = 0.03f * GcBeam.BRIGHTNESS_MUL, tipAlphaMul = 0.03f * GcBeam.BRIGHTNESS_MUL,
                            baseEmissiveAlphaMul = 0.85f * GcBeam.BRIGHTNESS_MUL, tipEmissiveAlphaMul = 0.85f * GcBeam.BRIGHTNESS_MUL,
                            mixPower = 3.4f, mirroredUMul = 0f,
                        ),
                    ),
                )
            }
        }
    }

    companion object {
        const val RENDER_ORDER = 110
        private const val TRAIL_REFRESH = 0.030f
        private const val TIP_WIDTH_MUL = 0.80f
    }
}

// ============================ 螺旋粒子 ============================

/**
 * 绕束旋进的红色螺旋粒子（旧 advance:508-548 内联 40 行提成节点）。纯每帧抛引擎粒子，用累积器控频。
 * 仅在 fadeMul>0.999（满态）时抛（对齐旧门控）。
 */
class BeamHelixParticleComponent(
    id: String,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER) {

    private var helixAcc = 0f

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        val level = frame.intensity.coerceIn(0f, 1f)
        val line = frame.beamLine() ?: return
        val now = engine.getTotalElapsedTime(false)

        if (frame.fadeMul.coerceIn(0f, 1f) > 0.999f) {
            helixAcc += (HELIX_RATE * (0.55f + 0.75f * level)) * amount
        }
        val helixCount = helixAcc.toInt().coerceIn(0, HELIX_MAX_PER_FRAME)
        if (helixCount <= 0) return
        helixAcc -= helixCount

        val amp = GcBeam.lerpF(12f, 28f, level)
        repeat(helixCount) {
            val u = Math.random().toFloat().coerceIn(0f, 1f)
            val phase = (now * 10.5f) + u * (2f * PI).toFloat() * 3.0f
            val wobble = sin(phase.toDouble()).toFloat() * amp
            val base = Vector2f(line.from.x + line.dirUnit.x * line.length * u, line.from.y + line.dirUnit.y * line.length * u)
            val loc = Vector2f(base.x + line.perpUnit.x * wobble, base.y + line.perpUnit.y * wobble)
            val along = MathUtils.getRandomNumberInRange(140f, 360f) * (0.75f + 0.55f * level)
            val side = cos(phase.toDouble()).toFloat() * MathUtils.getRandomNumberInRange(45f, 110f)
            val vel = Vector2f(line.dirUnit.x * along + line.perpUnit.x * side, line.dirUnit.y * along + line.perpUnit.y * side)
            val size = GcBeam.lerpF(10f, 18f, level) * MathUtils.getRandomNumberInRange(0.75f, 1.25f)
            val dur = MathUtils.getRandomNumberInRange(0.18f, 0.40f)
            val bright = MathUtils.getRandomNumberInRange(0.85f, 1.35f) * GcBeam.BRIGHTNESS_MUL
            val c = if (Math.random() < 0.45) GcBeam.CORE_COLOR else GcBeam.GLOW_COLOR
            engine.addSmoothParticle(loc, vel, size, bright, dur, c)
        }
    }

    companion object {
        const val RENDER_ORDER = 20
        private const val HELIX_RATE = 85f
        private const val HELIX_MAX_PER_FRAME = 10
    }
}
