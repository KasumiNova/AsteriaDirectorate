package cn.kasuminova.astd.combat.effect.psi

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.CurveEntity
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PSI-Ω 的 BoxUtil 光束渲染：
 * - 完全独立于原版 beam 渲染（原版 beam 可在 .wpn 中设为透明）。
 * - 风格：厚实核心 + 发光辉光 + 双螺旋扰动丝带。
 *
 * 说明：伤害/命中仍由原版 beam 机制提供；这里仅负责渲染叠加。
 */
internal class PsiOmegaBeamVfx(
    private val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
) {

    companion object {
        private const val HEARTBEAT = 0.35f
        private const val FADE_OUT = 0.16f

        private const val MAIN_TEX_PIXELS = 512f
        private const val WISP_TEX_PIXELS = 256f

        // 负值：向前流动（BoxUtil 约定）
        private const val CORE_TEX_SPEED = -540f
        private const val GLOW_TEX_SPEED = -340f
        private const val WISP_TEX_SPEED = -240f

        private const val START_FADE_FACTOR = 0.018f
        private const val END_FADE_FACTOR = 0.024f

        // 螺旋节点：初始分配较小，随着光束最大射程自动扩容（只增不减），以适配不同射程。
        // 注意：更新时只渲染/刷新前 nodeCount 个节点，避免不必要的开销。
        // 默认射程 1000，常见加成后约 2000：用 256 节点（step=10）可覆盖约 2550su，
        // 让绝大多数情况下不触发运行时扩容。
        private const val WISP_NODES_INIT = 256
        private const val WISP_NODES_MAX = 1024
        private const val WISP_PITCH_SU = 100f
        private const val WISP_LOCAL_RADIUS_SU = 16f
        private const val WISP_RADIUS_MUL = 0.70f // 两条光带间距 -30%（螺旋半径缩放），节距不变

        // 为避免 beam 长度频繁变化导致“节点重采样闪烁”，使用固定节点间距。
        // 节点越密越平滑，但更耗 CPU/带宽。这里取 10su，配合插值后足够圆润。
        private const val WISP_NODE_STEP_SU = 10f

        // 曲线平滑：通过 CurveEntity 插值 + 合理切线，消除“折线感”。
        private const val WISP_INTERP_BODY: Short = 16
        private const val WISP_INTERP_GLOW: Short = 24
        private const val WISP_TANGENT_HANDLE_MUL = 0.35f
        private const val WISP_ROT_SPEED_MUL = 0.28f
        private const val WISP_TRAVEL_SPEED = 0.95f
        private const val WISP_SPIN_SPEED = 2.10f

        // 螺旋：头/尾各 10% 的明显淡化（但不再像之前那样淡掉一大片）
        private const val WISP_END_FADE_FACTOR = 0.10f
        private const val WISP_END_FADE_ALPHA = 0.10f

        // Trail 创建首帧的默认着色：避免某些情况下（例如某条 wisp 创建失败导致 update 早退）出现“整束变白”的观感。
        private val DEFAULT_CORE_COLOR = Color(30, 0, 70, 240)
        private val DEFAULT_GLOW_COLOR = Color(65, 15, 130, 220)

        private const val CORE_TIP_WIDTH_MUL = 1.0f
        private const val GLOW_TIP_WIDTH_MUL = 1.0f

        // 亮度调节（按需求：-50%）
        private const val CORE_BRIGHTNESS_MUL = 0.50f
        private const val WISP_BODY_BRIGHTNESS_MUL = 0.50f
        private const val WISP_GLOW_BRIGHTNESS_MUL = 1.40f

        // 击中点特效（返回粒子）
        private const val HIT_RETURN_SPAWN_PER_SEC_MIN = 2.0f
        private const val HIT_RETURN_SPAWN_PER_SEC_MAX = 4.0f
        private const val HIT_RETURN_MAX = 96
        private const val HIT_RETURN_SPEED = 430f // -50%
        private const val HIT_RETURN_SWIRL = 0.52f // 越大越环绕光束
        private const val HIT_RETURN_TURN_DEG = 30f
        private const val HIT_RETURN_TURN_INTERVAL_MIN = 0.08f
        private const val HIT_RETURN_TURN_INTERVAL_MAX = 0.18f
        private const val HIT_RETURN_LIFE_EXTRA = 0.35f
        private const val HIT_RETURN_LIFE_MUL = 1.35f
        private const val HIT_RETURN_LIFE_CAP = 30f

        // 击中点特效（烟雾涟漪）
        // 频次：1s ~ 2s 一次（随 fx 越强越接近 1s，但不会更快）
        private const val HIT_SMOKE_INTERVAL_SLOW = 2.0f
        private const val HIT_SMOKE_INTERVAL_FAST = 1.0f
        private const val HIT_SMOKE_PUFFS_PER_RING = 22
        private const val HIT_SMOKE_RINGS = 3
        private const val HIT_SMOKE_SPEED_MIN = 18f
        private const val HIT_SMOKE_SPEED_MAX = 65f
        private const val HIT_SMOKE_SIZE_MIN = 22f
        private const val HIT_SMOKE_SIZE_MAX = 42f
        private const val HIT_SMOKE_END_SIZE_MUL = 2.15f

        private const val HIT_SMOKE_RAMP_UP = 0.25f
        private const val HIT_SMOKE_FULL = 1.10f
        private const val HIT_SMOKE_FADE = 1.15f

        private fun rand01(): Float = Math.random().toFloat()
        private fun randSigned01(): Float = rand01() * 2f - 1f
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun smoothstep01(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun facingUnitX(facing: Float): Float {
            val rad = Math.toRadians(facing.toDouble())
            return cos(rad).toFloat()
        }

        private fun facingUnitY(facing: Float): Float {
            val rad = Math.toRadians(facing.toDouble())
            return sin(rad).toFloat()
        }

        private fun rotate2D(x: Float, y: Float, rad: Float): Pair<Float, Float> {
            val c = cos(rad)
            val s = sin(rad)
            return Pair(x * c - y * s, x * s + y * c)
        }

        private fun normalize(x: Float, y: Float): Pair<Float, Float> {
            val len = sqrt((x * x + y * y).coerceAtLeast(0.000001f))
            return Pair(x / len, y / len)
        }


    private data class ReturnParticle(
        val pos: Vector2f,
        val vel: Vector2f,
        val prev: Vector2f,
        var life: Float,
        val lifeMax: Float,
        var steerT: Float,
        var steerInterval: Float,
        var steerRad: Float,
        val swirlSign: Float,
        var trailT: Float,
    )
        private fun loadSprites(): Pair<SpriteAPI, SpriteAPI>? {
            return try {
                val core = Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
                val fringe = Global.getSettings().getSprite("graphics/fx/beamfringeb.png")
                Pair(core, fringe)
            } catch (_: Throwable) {
                try {
                    val s = Global.getSettings().getSprite("textures", "BUtil_ONE")
                    Pair(s, s)
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    private data class State(
        val core: TrailEntity,
        val coreMirroredU: TrailEntity,
        val glow: TrailEntity,
        val glowMirroredU: TrailEntity,
        val wispBodies: List<CurveEntity>,
        val wispGlows: List<CurveEntity>,
        var time: Float,
        var wispLenSmooth: Float,
        val returnParticles: MutableList<ReturnParticle>,
        var returnSpawnAcc: Float,
        var smokeT: Float,
        var smokeIntervalCur: Float,
        var fadingOut: Boolean,
    )

    private var state: State? = null
    private var cachedSprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var boxVfxFailed = false

    // updateWisp() 的 scratch（避免每帧分配 FloatArray）
    private val wispScratchX = FloatArray(WISP_NODES_MAX)
    private val wispScratchY = FloatArray(WISP_NODES_MAX)

    private val v0 = Vector2f(0f, 0f)

    fun fadeOut() {
        val s = state ?: return
        if (s.fadingOut) {
            val allDeleted =
                s.core.hasDelete() && s.coreMirroredU.hasDelete() && s.glow.hasDelete() && s.glowMirroredU.hasDelete() &&
                    s.wispBodies.all { it.hasDelete() } && s.wispGlows.all { it.hasDelete() }
            if (allDeleted) state = null
            return
        }

        s.fadingOut = true
        try { s.core.setGlobalTimer(0f, 0f, FADE_OUT) } catch (_: Throwable) {}
        try { s.coreMirroredU.setGlobalTimer(0f, 0f, FADE_OUT) } catch (_: Throwable) {}
        try { s.glow.setGlobalTimer(0f, 0f, FADE_OUT) } catch (_: Throwable) {}
        try { s.glowMirroredU.setGlobalTimer(0f, 0f, FADE_OUT) } catch (_: Throwable) {}
        for (w in s.wispBodies) {
            try { w.setGlobalTimer(0f, 0f, FADE_OUT) } catch (_: Throwable) {}
        }
        for (w in s.wispGlows) {
            try { w.setGlobalTimer(0f, 0f, FADE_OUT) } catch (_: Throwable) {}
        }
    }

    fun update(
        engine: CombatEngineAPI,
        amount: Float,
        start: Vector2f,
        facing: Float,
        length: Float,
        ramp: Float,
        baseWidth: Float,
    ) {
        if (boxVfxFailed) return

        BoxUtilCombatVfx.ensureReady(engine)

        val sprites = cachedSprites ?: loadSprites().also { cachedSprites = it }
        if (sprites == null) {
            boxVfxFailed = true
            return
        }

        val usableLen = length.coerceAtLeast(16f)
        val coreW = (baseWidth * (0.92f + 0.55f * ramp)).coerceAtLeast(10f)
        val glowW = (coreW * (1.55f + 0.30f * ramp)).coerceAtLeast(16f)

        val s = ensureState(engine, sprites, usableLen, coreW, glowW) ?: return

        // 平滑长度：避免瞬时长度变化造成螺旋末端节点数量/位置抖动。
        val lenSmoothT = (amount * 10f).coerceIn(0f, 1f)
        s.wispLenSmooth += (usableLen - s.wispLenSmooth) * lenSmoothT

        if (s.fadingOut) {
            s.fadingOut = false
            try { s.core.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) } catch (_: Throwable) {}
            try { s.coreMirroredU.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) } catch (_: Throwable) {}
            try { s.glow.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) } catch (_: Throwable) {}
            try { s.glowMirroredU.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) } catch (_: Throwable) {}
            for (w in s.wispBodies) {
                try { w.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) } catch (_: Throwable) {}
            }
            for (w in s.wispGlows) {
                try { w.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) } catch (_: Throwable) {}
            }
        }

        s.time += amount

        // 暗紫核心：保持深色，不向亮粉偏移
        // alpha 固定：避免 ramp/强度影响亮度（只允许轻微色相变化）。
        val core0 = Color(30, 0, 70, 240)
        val core1 = Color(55, 5, 110, 240)
        val glow0 = Color(65, 15, 130, 220)
        val glow1 = Color(110, 35, 180, 220)

        val coreColor = colorLerp(core0, core1, ramp)
        val glowColor = colorLerp(glow0, glow1, ramp)

        // 核心亮度 -50%
        updateStraight(s.core, start, facing, usableLen, coreW, ramp, baseAlpha = 0.55f * CORE_BRIGHTNESS_MUL, emissiveAlpha = 0.45f * CORE_BRIGHTNESS_MUL, reversedU = false)
        updateStraight(s.coreMirroredU, start, facing, usableLen, coreW, ramp, baseAlpha = 0.20f * CORE_BRIGHTNESS_MUL, emissiveAlpha = 0.22f * CORE_BRIGHTNESS_MUL, reversedU = true)

        updateStraight(s.glow, start, facing, usableLen, glowW, ramp, baseAlpha = 0.14f, emissiveAlpha = 0.50f, reversedU = false)
        updateStraight(s.glowMirroredU, start, facing, usableLen, glowW, ramp, baseAlpha = 0.08f, emissiveAlpha = 0.28f, reversedU = true)

        for (idx in 0..1) {
            val body = s.wispBodies.getOrNull(idx)
            val glow = s.wispGlows.getOrNull(idx)

            // Pass explicit width for calculation since CurveEntity doesn't store "base width" for us
            val bodyWidth = (coreW * 0.92f).coerceAtLeast(8f)
            val glowWidth = (coreW * 0.62f).coerceAtLeast(6f)

            if (body != null) {
                updateWisp(body, start, facing, s.wispLenSmooth, s.time, ramp, idx, WispStyle.BODY, bodyWidth, glowColor)
            }
            if (glow != null) {
                updateWisp(glow, start, facing, s.wispLenSmooth, s.time, ramp, idx, WispStyle.GLOW, glowWidth, glowColor)
            }
        }

        // 击中点特效（返回粒子 + 烟雾涟漪）
        updateHitFx(engine, amount, start, facing, usableLen, ramp)

        // core/glow 的颜色：保持在材质层
        try {
            s.core.materialData.setColor(coreColor)
            s.core.materialData.setEmissiveColor(glowColor)
        } catch (_: Throwable) {}
        try {
            s.coreMirroredU.materialData.setColor(coreColor)
            s.coreMirroredU.materialData.setEmissiveColor(glowColor)
        } catch (_: Throwable) {}
        try {
            s.glow.materialData.setColor(glowColor)
            s.glow.materialData.setEmissiveColor(glowColor)
        } catch (_: Throwable) {}
        try {
            s.glowMirroredU.materialData.setColor(glowColor)
            s.glowMirroredU.materialData.setEmissiveColor(glowColor)
        } catch (_: Throwable) {}
    }

    private fun ensureState(
        engine: CombatEngineAPI,
        sprites: Pair<SpriteAPI, SpriteAPI>,
        length: Float,
        coreW: Float,
        glowW: Float,
    ): State? {
        val existing = state
        if (
            existing != null &&
            !existing.core.hasDelete() && !existing.coreMirroredU.hasDelete() &&
            !existing.glow.hasDelete() && !existing.glowMirroredU.hasDelete() &&
            existing.wispBodies.none { it.hasDelete() } && existing.wispGlows.none { it.hasDelete() }
        ) {
            return existing
        }

        val core = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = Vector2f(0f, 0f),
            facing = 0f,
            length = length,
            baseWidth = coreW,
            tipWidth = (coreW * CORE_TIP_WIDTH_MUL).coerceAtLeast(2.2f),
            coreColor = DEFAULT_CORE_COLOR,
            fringeColor = DEFAULT_GLOW_COLOR,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            layer = layer,
            full = HEARTBEAT,
            baseAlphaMul = 0.55f,
            tipAlphaMul = 0.35f,
            baseEmissiveAlphaMul = 0.45f,
            tipEmissiveAlphaMul = 0.35f,
            mixPower = 3.6f,
        )
        val coreMirroredU = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
            engine = engine,
            location = Vector2f(0f, 0f),
            facing = 0f,
            length = length,
            baseWidth = coreW,
            tipWidth = (coreW * CORE_TIP_WIDTH_MUL).coerceAtLeast(2.2f),
            coreColor = DEFAULT_CORE_COLOR,
            fringeColor = DEFAULT_GLOW_COLOR,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            layer = layer,
            full = HEARTBEAT,
            baseAlphaMul = 0.20f,
            tipAlphaMul = 0.12f,
            baseEmissiveAlphaMul = 0.22f,
            tipEmissiveAlphaMul = 0.15f,
            mixPower = 3.2f,
        )
        val glow = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = Vector2f(0f, 0f),
            facing = 0f,
            length = length,
            baseWidth = glowW,
            tipWidth = (glowW * GLOW_TIP_WIDTH_MUL).coerceAtLeast(3.5f),
            coreColor = DEFAULT_GLOW_COLOR,
            fringeColor = DEFAULT_GLOW_COLOR,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            layer = layer,
            full = HEARTBEAT,
            baseAlphaMul = 0.14f,
            tipAlphaMul = 0.08f,
            baseEmissiveAlphaMul = 0.50f,
            tipEmissiveAlphaMul = 0.35f,
            mixPower = 3.0f,
        )
        val glowMirroredU = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
            engine = engine,
            location = Vector2f(0f, 0f),
            facing = 0f,
            length = length,
            baseWidth = glowW,
            tipWidth = (glowW * GLOW_TIP_WIDTH_MUL).coerceAtLeast(3.5f),
            coreColor = DEFAULT_GLOW_COLOR,
            fringeColor = DEFAULT_GLOW_COLOR,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            layer = layer,
            full = HEARTBEAT,
            baseAlphaMul = 0.08f,
            tipAlphaMul = 0.04f,
            baseEmissiveAlphaMul = 0.28f,
            tipEmissiveAlphaMul = 0.18f,
            mixPower = 2.8f,
        )

        if (core == null || coreMirroredU == null || glow == null || glowMirroredU == null) {
            core?.delete(); coreMirroredU?.delete(); glow?.delete(); glowMirroredU?.delete()
            boxVfxFailed = true
            return null
        }

        initFlowParams(core, CORE_TEX_SPEED)
        initFlowParams(coreMirroredU, CORE_TEX_SPEED * -0.92f)
        initFlowParams(glow, GLOW_TEX_SPEED)
        initFlowParams(glowMirroredU, GLOW_TEX_SPEED * -0.92f)

        initEndFade(core)
        initEndFade(coreMirroredU)
        initEndFade(glow)
        initEndFade(glowMirroredU)

        // 心跳定时器：若 advance() 不再调用，实体会在 HEARTBEAT+FADE_OUT 后自动消亡
        initHeartbeat(core)
        initHeartbeat(coreMirroredU)
        initHeartbeat(glow)
        initHeartbeat(glowMirroredU)

        // 每条螺旋拆成两层：
        // - BODY：normal blend 的“光带主体”（更像缠绕的丝带）
        // - GLOW：additive 的“内发光高光”（提供能量感）
        val wispBodies = ArrayList<CurveEntity>(2)
        val wispGlows = ArrayList<CurveEntity>(2)
        repeat(2) {
            val body = createWispTrail(
                engine = engine,
                diffuseSprite = sprites.second,
                emissiveSprite = sprites.first,
                width = (coreW * 0.92f).coerceAtLeast(8f),
                additive = false,
                mixFactor = 2.2f,
                texSpeed = WISP_TEX_SPEED * 0.70f,
                interpolation = WISP_INTERP_BODY,
                glowPower = 1.05f,
            )
            val glowWisp = createWispTrail(
                engine = engine,
                diffuseSprite = sprites.first,
                emissiveSprite = sprites.second,
                width = (coreW * 0.62f).coerceAtLeast(6f),
                additive = true,
                mixFactor = 3.6f,
                texSpeed = WISP_TEX_SPEED,
                interpolation = WISP_INTERP_GLOW,
                glowPower = 1.60f,
            )

            if (body == null || glowWisp == null) {
                for (x in wispBodies) x.delete()
                for (x in wispGlows) x.delete()
                body?.delete(); glowWisp?.delete()
                core.delete(); coreMirroredU.delete(); glow.delete(); glowMirroredU.delete()
                boxVfxFailed = true
                return null
            }

            wispBodies.add(body)
            wispGlows.add(glowWisp)
        }

        return State(
            core = core,
            coreMirroredU = coreMirroredU,
            glow = glow,
            glowMirroredU = glowMirroredU,
            wispBodies = wispBodies,
            wispGlows = wispGlows,
            time = 0f,
            wispLenSmooth = length,
            returnParticles = ArrayList(),
            returnSpawnAcc = 0f,
            smokeT = 0f,
            smokeIntervalCur = 1.6f,
            fadingOut = false,
        ).also { state = it }
    }

    private fun initFlowParams(e: TrailEntity, textureSpeed: Float) {
        try {
            e.setTexturePixels(MAIN_TEX_PIXELS)
            e.setTextureSpeed(textureSpeed)
            e.setFlowWhenPaused(false)
            e.setUVOffset((rand01() * 2f) - 1f)
            e.setJitterPower(0.03f)
            e.setFlick(false)
            e.setSyncFlick(false)
        } catch (_: Throwable) {
        }
    }

    private fun initEndFade(e: TrailEntity) {
        try {
            e.setFillStartAlpha(0.22f)
            e.setFillStartFactor(START_FADE_FACTOR)
            e.setFillEndAlpha(0.18f)
            e.setFillEndFactor(END_FADE_FACTOR)
        } catch (_: Throwable) {
        }
    }

    private fun initHeartbeat(e: TrailEntity) {
        try { e.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) } catch (_: Throwable) {}
    }

    private fun createWispTrail(
        engine: CombatEngineAPI,
        diffuseSprite: SpriteAPI,
        emissiveSprite: SpriteAPI,
        width: Float,
        additive: Boolean,
        mixFactor: Float,
        texSpeed: Float,
        interpolation: Short,
        glowPower: Float,
    ): CurveEntity? {
        return try {
            val e = CurveEntity()
            e.setInterpolation(interpolation)
            e.setGlobalUV(true) // 让 UV 绑定到节点索引，避免长度变化造成纹理“拉伸/抖动”
            
            // 初始化节点数据（使用默认构造器，自带 flat tangent: left=(-1,0), right=(1,0)）
            val initCount = WISP_NODES_INIT.coerceAtLeast(2)
            val nodes = ArrayList<NodeData>(initCount)
            repeat(initCount) {
                val n = NodeData() // 默认 flat tangent
                n.setWidth(width)
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
            // 与 BoxUtilCombatVfx.createTaperedBeamTrail* 保持一致：避免 emissive 再乘 diffuse alpha 导致“看不见”。
            val mat = e.materialData
            mat.setAlphaToEmissive(0f)
            mat.setColorToEmissive(0f)
            mat.setGlowPower(glowPower)
            mat.setColor(DEFAULT_GLOW_COLOR)
            mat.setEmissiveColor(DEFAULT_GLOW_COLOR)

            e.setTexturePixels(WISP_TEX_PIXELS)
            e.setTextureSpeed(texSpeed)
            e.setUVOffset((rand01() * 2f) - 1f)
            
            // CurveEntity 没有 setStartWidth/MixFactor/Flick，这些特性由节点数据或者材质控制
            // 宽度会在 updateWisp 中每帧刷新

            // 末端淡化交给 updateWisp 的 per-node envelope（更稳定，且兼容“只渲染部分节点”）。
            e.setFillStartAlpha(1f)
            e.setFillStartFactor(0f)
            e.setFillEndAlpha(1f)
            e.setFillEndFactor(0f)

            // CurveEntity 也是一种 RenderData，使用 ENTITY_CURVE = 2
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_CURVE, e)
            if (state != 0) {
                e.delete()
                null
            } else {
                e
            }
        } catch (_: Throwable) {
            null
        }
    }

    private enum class WispStyle {
        BODY,
        GLOW,
    }

    private fun updateStraight(
        e: TrailEntity,
        start: Vector2f,
        facing: Float,
        length: Float,
        width: Float,
        ramp: Float,
        baseAlpha: Float,
        emissiveAlpha: Float,
        reversedU: Boolean,
    ) {
        if (e.hasDelete()) return
        try {
            val nodes = e.nodes
            if (nodes != null && nodes.size >= 2) {
                if (!reversedU) {
                    nodes[0].set(0f, 0f)
                    nodes[1].set(length, 0f)
                } else {
                    nodes[0].set(length, 0f)
                    nodes[1].set(0f, 0f)
                }
                e.setNodeRefreshIndex(0)
                e.setNodeRefreshAllFromCurrentIndex()
                e.submitNodes()
            }

            e.setTexturePixels(MAIN_TEX_PIXELS)

            val s = ramp.coerceIn(0f, 1f)
            val bodyW = (width * (0.78f + 0.22f * s)).coerceAtLeast(2f)
            e.setStartWidth(bodyW)
            e.setEndWidth(bodyW)

            // 亮度不随 ramp/强度变化：alpha/emissive 固定，仅宽度可随 ramp 改变
            val a = baseAlpha.coerceIn(0f, 1.2f)
            val ea = emissiveAlpha.coerceIn(0f, 8f)

            e.setStartColor(1f, 1f, 1f, a)
            e.setEndColor(1f, 1f, 1f, a)
            e.setStartEmissive(1f, 1f, 1f, ea)
            e.setEndEmissive(1f, 1f, 1f, ea)

            initEndFade(e)
            e.setStateVanilla(start, facing)
            e.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) // 心跳刷新
        } catch (_: Throwable) {
        }
    }

    private fun updateWisp(
        e: CurveEntity,
        start: Vector2f,
        facing: Float,
        length: Float,
        time: Float,
        ramp: Float,
        index: Int,
        style: WispStyle,
        baseWidthVal: Float, // 传入基础宽度
        baseColor: Color // 传入基础颜色
    ) {
        if (e.hasDelete()) return

        val nodes = try { e.nodes } catch (_: Throwable) { null } ?: return
        if (nodes.isEmpty()) return

        val s = ramp.coerceIn(0f, 1f)

        // 双螺旋：两条丝带仅通过相位差区分；螺旋“节距”由 WISP_PITCH_SU 固定为 100su。
        // 注意：相位使用 localX/WISP_PITCH_SU（而不是 turns*t）来保证频率与长度无关。
        val phaseBase = index * PI.toFloat()
        val travel = time * WISP_TRAVEL_SPEED * WISP_ROT_SPEED_MUL
        val spin = time * WISP_SPIN_SPEED * WISP_ROT_SPEED_MUL

        val radius = WISP_LOCAL_RADIUS_SU * WISP_RADIUS_MUL

        // BODY：更像“光带”；GLOW：作为内发光高光。
        val aMul = if (style == WispStyle.BODY) 1.00f else 0.72f
        val eaMul = if (style == WispStyle.BODY) 0.55f else 1.35f

        // 亮度不随 ramp/强度变化：baseAlpha/baseEmissiveAlpha 固定
        val styleBrightMul = when (style) {
            WispStyle.BODY -> WISP_BODY_BRIGHTNESS_MUL
            WispStyle.GLOW -> WISP_GLOW_BRIGHTNESS_MUL
        }
        val baseAlpha = (0.40f * aMul * styleBrightMul).coerceIn(0f, 0.95f)
        // NodeData emissiveColor alpha 是 0..1
        val baseEmissiveAlpha = (0.60f * eaMul * styleBrightMul).coerceIn(0f, 1.0f)
        
        // 随 ramp 变粗
        val baseW = if (style == WispStyle.BODY) {
            (baseWidthVal * (0.8f + 0.2f * s))
        } else {
            (baseWidthVal * (0.85f + 0.15f * s))
        }

        // NodeData "Location based on entity space" —— 使用局部坐标，
        // 后续通过 setStateVanilla(start, facing) 变换到世界坐标。

        // 按射程自适应：如果当前 beam 比现有节点覆盖更长，则扩容节点列表（只增不减）。
        // 扩容时会触发 VBO 重新分配，但这是“偶发”的；用户允许适当牺牲性能换取自适应。
        val wantCount = ((length / WISP_NODE_STEP_SU).toInt() + 1).coerceIn(2, WISP_NODES_MAX)
        if (nodes.size < wantCount) {
            val old = nodes.size
            val add = wantCount - old
            // 追加默认节点：flat tangent + 默认颜色，后续会在本帧被覆盖
            repeat(add) {
                nodes.add(NodeData())
            }
            try {
                // 提示 CurveEntity 全量刷新（新 buffer）
                e.setNodeRefreshIndex(0)
                e.setNodeRefreshAllFromCurrentIndex()
            } catch (_: Throwable) {
            }
        }

        val totalNodes = nodes.size
        val maxLen = (totalNodes - 1).toFloat() * WISP_NODE_STEP_SU
        val lenUse = length.coerceAtMost(maxLen).coerceAtLeast(0f)
        val nodeCount = ((lenUse / WISP_NODE_STEP_SU).toInt() + 1).coerceIn(2, totalNodes)
        val lastIdx = nodeCount - 1

        // 颜色基色（避免 per-node new Color 分配）
        val br = baseColor.red / 255f
        val bg = baseColor.green / 255f
        val bb = baseColor.blue / 255f

        // 末端淡化：用节点索引做 envelope，避免 fillStart/End 在“只渲染部分节点”时不准确。
        val edge = 0.12f

        val xs = wispScratchX
        val ys = wispScratchY

        // 先算位置/颜色/宽度
        for (i in 0 until nodeCount) {
            val t = if (lastIdx <= 0) 0f else i.toFloat() / lastIdx.toFloat()

            val localX = if (i == lastIdx) lenUse else i.toFloat() * WISP_NODE_STEP_SU
            val phase = 2f * PI.toFloat() * (localX / WISP_PITCH_SU + travel) + spin + phaseBase
            val localY = sin(phase) * radius

            xs[i] = localX
            ys[i] = localY

            // 3D 深度模拟：z = cos(phase)
            val z = cos(phase)
            val front = (z * 0.5f + 0.5f).coerceIn(0f, 1f)

            // 深度调节：同时影响 alpha + 少量 RGB 亮度（让过渡更“看得见”）
            val alphaMod = lerp(0.50f, 1.55f, front)
            val rgbMod = lerp(0.55f, 1.00f, front)
            val emissiveAMod = lerp(0.20f, 1.00f, front)

            val head = smoothstep01((t / edge).coerceIn(0f, 1f))
            val tail = smoothstep01(((1f - t) / edge).coerceIn(0f, 1f))
            val endEnv = head * tail

            val zWidthMod = 1.0f + z * 0.18f

            val node = nodes[i]
            node.setLocation(localX, localY)
            node.setWidth((baseW * zWidthMod).coerceAtLeast(1f))
            node.setMixFactor(if (style == WispStyle.GLOW) 0.92f else 0.85f)

            val nodeAlpha = (baseAlpha * alphaMod * endEnv).coerceIn(0f, 1f)
            val nodeEmA = (baseEmissiveAlpha * emissiveAMod * endEnv).coerceIn(0f, 1f)

            node.setColor((br * rgbMod).coerceIn(0f, 1f), (bg * rgbMod).coerceIn(0f, 1f), (bb * rgbMod).coerceIn(0f, 1f), nodeAlpha)

            // emissive：GLOW 更偏白、更亮；BODY 更克制。
            val toWhite = if (style == WispStyle.GLOW) 0.35f else 0.12f
            val w = (front * toWhite).coerceIn(0f, 1f)
            val er = lerp(br * 0.75f, 1f, w).coerceIn(0f, 1f)
            val eg = lerp(bg * 0.75f, 1f, w).coerceIn(0f, 1f)
            val eb = lerp(bb * 0.75f, 1f, w).coerceIn(0f, 1f)
            node.setEmissiveColor(er, eg, eb, nodeEmA)
        }

        // 再算切线（让插值曲线不呈现折线/菱形）
        val handleLen = WISP_NODE_STEP_SU * WISP_TANGENT_HANDLE_MUL
        for (i in 0 until nodeCount) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i1 = (i + 1).coerceAtMost(lastIdx)
            val dx = xs[i1] - xs[i0]
            val dy = ys[i1] - ys[i0]
            val invLen = 1f / sqrt((dx * dx + dy * dy).coerceAtLeast(0.0001f))
            val dirX = dx * invLen
            val dirY = dy * invLen
            val tx = dirX * handleLen
            val ty = dirY * handleLen
            val node = nodes[i]
            node.setTangentLeft(-tx, -ty)
            node.setTangentRight(tx, ty)
        }

        try {
            e.setNodeRenderingCount(nodeCount)
            e.setNodeRefreshIndex(0)
            e.setNodeRefreshSize(nodeCount)
            e.submitNodes()

            e.setTexturePixels(WISP_TEX_PIXELS)
            val speedMul = if (style == WispStyle.BODY) 0.85f else 1.00f
            e.setTextureSpeed(WISP_TEX_SPEED * speedMul * (0.80f + 0.35f * s))

            // 材质：白色基底，让 per-node color 决定最终颜色
            e.materialData.setColor(Color(255, 255, 255, 255))
            e.materialData.setEmissiveColor(baseColor)

            // 关键：使用 setStateVanilla 将局部坐标系变换到世界坐标
            e.setStateVanilla(start, facing)
            e.setGlobalTimer(0f, HEARTBEAT, FADE_OUT) // 心跳刷新
        } catch (_: Throwable) {
        }
    }

    private fun updateHitFx(
        engine: CombatEngineAPI,
        amount: Float,
        start: Vector2f,
        facing: Float,
        length: Float,
        ramp: Float,
    ) {
        val s = state ?: return

        val fx = ramp.coerceIn(0f, 1f)

        // 击中点世界坐标
        val dx = facingUnitX(facing)
        val dy = facingUnitY(facing)
        val hitX = start.x + dx * length
        val hitY = start.y + dy * length
        val hit = Vector2f(hitX, hitY)

        // 1) 返回自身的紫色发光粒子（带小拖尾，随机小转向）
        // 频率：2（低强度）→ 4（高强度）
        val spawnRate = lerp(HIT_RETURN_SPAWN_PER_SEC_MIN, HIT_RETURN_SPAWN_PER_SEC_MAX, fx)
        s.returnSpawnAcc += amount * spawnRate
        val spawnN = s.returnSpawnAcc.toInt().coerceAtMost(6)
        if (spawnN > 0) s.returnSpawnAcc -= spawnN.toFloat()

        repeat(spawnN) {
            if (s.returnParticles.size >= HIT_RETURN_MAX) return@repeat

            val sign = if (rand01() < 0.5f) -1f else 1f
            val perpX = -dy
            val perpY = dx
            val jitter = randSigned01() * 9f
            val p = Vector2f(hitX + perpX * jitter, hitY + perpY * jitter)

            // 存活时间：至少覆盖从 hit 飞回 start 的时间（考虑绕束与转向导致的路径变长）
            val avgSpeed = HIT_RETURN_SPEED * 0.85f
            val travelNeed = (length / avgSpeed) * HIT_RETURN_LIFE_MUL + HIT_RETURN_LIFE_EXTRA
            val lifeMax = (travelNeed * (1.0f + 0.20f * rand01())).coerceAtMost(HIT_RETURN_LIFE_CAP)
            val interval = lerp(HIT_RETURN_TURN_INTERVAL_MIN, HIT_RETURN_TURN_INTERVAL_MAX, rand01())
            val steerDeg = randSigned01() * HIT_RETURN_TURN_DEG
            val steerRad = Math.toRadians(steerDeg.toDouble()).toFloat()

            // 初速度：朝向 start + 少量环绕
            val vx = (-dx + perpX * (HIT_RETURN_SWIRL * sign))
            val vy = (-dy + perpY * (HIT_RETURN_SWIRL * sign))
            val nd = normalize(vx, vy)
            val v = Vector2f(nd.first * HIT_RETURN_SPEED, nd.second * HIT_RETURN_SPEED)

            s.returnParticles.add(
                ReturnParticle(
                    pos = p,
                    vel = v,
                    prev = Vector2f(p.x, p.y),
                    life = 0f,
                    lifeMax = lifeMax,
                    steerT = 0f,
                    steerInterval = interval,
                    steerRad = steerRad,
                    swirlSign = sign,
                    trailT = 0f,
                )
            )
        }

        // 更新与绘制（更“看得见”：寿命更长、亮度更高；但整体尺寸 -50%）
        val headColor = Color(210, 130, 255, 255)
        val trailColor = Color(165, 75, 235, 175)
        val trailColor2 = Color(120, 40, 190, 120)

        var i = 0
        while (i < s.returnParticles.size) {
            val p = s.returnParticles[i]
            p.life += amount

            // 到达发射端/超时则移除
            val toSX = start.x - p.pos.x
            val toSY = start.y - p.pos.y
            val dist2 = toSX * toSX + toSY * toSY
            if (p.life >= p.lifeMax || dist2 <= 20f * 20f) {
                s.returnParticles.removeAt(i)
                continue
            }

            // 记录上一帧位置（用于拖尾）
            p.prev.set(p.pos)

            // 随机小转向：周期性改变 steerOffset
            p.steerT += amount
            if (p.steerT >= p.steerInterval) {
                p.steerT = 0f
                p.steerInterval = lerp(HIT_RETURN_TURN_INTERVAL_MIN, HIT_RETURN_TURN_INTERVAL_MAX, rand01())
                val deg = randSigned01() * HIT_RETURN_TURN_DEG
                p.steerRad = Math.toRadians(deg.toDouble()).toFloat()
            }

            // 期望方向：朝向 start，并沿垂直方向“环绕”光束
            // 改进：让粒子更“贴着光束轴线”回流，同时保持环绕。
            // 轴线方向：从 start 指向 hit 的 (dx,dy)，回流方向为 (-dx,-dy)
            val axX = -dx
            val axY = -dy

            // 计算粒子到轴线的偏移（用于向轴线回弹 + 围绕轴线切向）
            val relX = p.pos.x - start.x
            val relY = p.pos.y - start.y
            val proj = relX * dx + relY * dy
            val cx = start.x + dx * proj
            val cy = start.y + dy * proj
            val offX = p.pos.x - cx
            val offY = p.pos.y - cy
            val offLen = sqrt((offX * offX + offY * offY).coerceAtLeast(0.000001f))
            val invOff = 1f / offLen
            val radInX = -offX * invOff
            val radInY = -offY * invOff
            val tanX = -offY * invOff * p.swirlSign
            val tanY = offX * invOff * p.swirlSign

            // 环绕强度随“离轴距离”稍微提升，近轴时减少抖动
            val swirlK = (HIT_RETURN_SWIRL * (0.65f + 0.35f * (offLen / 60f).coerceIn(0f, 1f)))
            val pullK = (0.85f * (offLen / 45f).coerceIn(0f, 1f))

            var desX = axX + tanX * swirlK + radInX * pullK
            var desY = axY + tanY * swirlK + radInY * pullK
            val dn = normalize(desX, desY)
            desX = dn.first
            desY = dn.second

            // 施加 steer offset（±30°），使其产生随机小转向
            val rr = rotate2D(desX, desY, p.steerRad)
            desX = rr.first
            desY = rr.second

            // 当前方向
            val vLen = sqrt((p.vel.x * p.vel.x + p.vel.y * p.vel.y).coerceAtLeast(0.000001f))
            var curX = p.vel.x / vLen
            var curY = p.vel.y / vLen

            // 朝期望方向转向（不要“瞬移”）
            val turnK = (amount * 6.5f).coerceIn(0f, 1f)
            curX += (desX - curX) * turnK
            curY += (desY - curY) * turnK
            val cn = normalize(curX, curY)
            curX = cn.first
            curY = cn.second

            // 速度略微波动，避免完全“机械”
            val sp = HIT_RETURN_SPEED * (0.86f + 0.22f * sin(p.life * 11.7f + p.swirlSign))
            p.vel.set(curX * sp, curY * sp)

            // 移动
            p.pos.x += p.vel.x * amount
            p.pos.y += p.vel.y * amount

            // 绘制：头部 + 拖尾（拖尾跟随轨迹，不跟随转向）
            val fade = (1f - (p.life / p.lifeMax)).coerceIn(0f, 1f)
            val headSize = 6f + 3f * fade
            val headBright = 1.35f
            engine.addSmoothParticle(
                p.pos,
                v0,
                headSize,
                headBright,
                0.18f,
                Color(headColor.red, headColor.green, headColor.blue, (headColor.alpha * fade).toInt().coerceIn(0, 255))
            )
            // 轻微“光晕”，让返航粒子在强光背景下更容易看到
            engine.addNebulaParticle(
                p.pos,
                v0,
                9f,
                1.35f,
                0.05f,
                0.08f,
                0.20f,
                Color(170, 80, 240, (90f * fade).toInt().coerceIn(0, 255))
            )

            // 拖尾：沿路径撒点
            p.trailT += amount
            val trailStep = 0.030f
            if (p.trailT >= trailStep) {
                p.trailT -= trailStep
                val tx = p.prev.x
                val ty = p.prev.y
                engine.addSmoothParticle(Vector2f(tx, ty), v0, 6f, 0.65f, 0.42f, Color(trailColor.red, trailColor.green, trailColor.blue, (trailColor.alpha * fade).toInt().coerceIn(0, 255)))
                engine.addSmoothParticle(Vector2f(tx, ty), v0, 10f, 0.42f, 0.55f, Color(trailColor2.red, trailColor2.green, trailColor2.blue, (trailColor2.alpha * fade).toInt().coerceIn(0, 255)))
            }

            i++
        }

        // 2) 紫色烟雾涟漪（多个 nebula 组成环，扩散并变透明）
        // 频次：1~2 秒一次；随 fx 越强越接近 1s（不影响亮度）。
        s.smokeT += amount
        if (s.smokeT >= s.smokeIntervalCur) {
            s.smokeT = 0f
            spawnSmokeRipples(engine, hit, fx)
            val base = lerp(HIT_SMOKE_INTERVAL_SLOW, HIT_SMOKE_INTERVAL_FAST, fx)
            val jitter = randSigned01() * 0.25f
            s.smokeIntervalCur = (base + jitter).coerceIn(HIT_SMOKE_INTERVAL_FAST, HIT_SMOKE_INTERVAL_SLOW)
        }
    }

    private fun spawnSmokeRipples(engine: CombatEngineAPI, hit: Vector2f, fx: Float) {
        val baseSpeed = lerp(HIT_SMOKE_SPEED_MIN, HIT_SMOKE_SPEED_MAX, fx)
        val sizeBase = lerp(HIT_SMOKE_SIZE_MIN, HIT_SMOKE_SIZE_MAX, fx)

        // 烟雾色：偏紫、低亮度（不随 fx 变亮）
        val c0 = Color(120, 45, 170, 85)
        val c1 = Color(90, 30, 130, 60)

        for (ring in 0 until HIT_SMOKE_RINGS) {
            val ringR = 12f + ring * 18f + rand01() * 8f
            val puffs = HIT_SMOKE_PUFFS_PER_RING
            for (i in 0 until puffs) {
                val a = (i.toFloat() / puffs.toFloat()) * (2f * PI.toFloat()) + randSigned01() * 0.12f
                val ux = cos(a)
                val uy = sin(a)

                // 真实“同心环”烟雾：位置与速度都沿环的径向扩散
                val offX = ux * ringR
                val offY = uy * ringR

                val loc = Vector2f(hit.x + offX, hit.y + offY)

                val sp = baseSpeed * (0.70f + 0.55f * rand01())
                val vel = Vector2f(ux * sp, uy * sp)

                val size = sizeBase * (0.75f + 0.55f * rand01())
                val col = if (rand01() < 0.55f) c0 else c1

                // rampUp/full/fade：偏“烟雾”而不是爆闪
                engine.addNebulaParticle(loc, vel, size, HIT_SMOKE_END_SIZE_MUL, HIT_SMOKE_RAMP_UP, HIT_SMOKE_FULL, HIT_SMOKE_FADE, col)
            }
        }
    }

    private fun colorLerp(a: Color, b: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        val r = (a.red + (b.red - a.red) * tt).toInt().coerceIn(0, 255)
        val g = (a.green + (b.green - a.green) * tt).toInt().coerceIn(0, 255)
        val bl = (a.blue + (b.blue - a.blue) * tt).toInt().coerceIn(0, 255)
        val al = (a.alpha + (b.alpha - a.alpha) * tt).toInt().coerceIn(0, 255)
        return Color(r, g, bl, al)
    }
}
