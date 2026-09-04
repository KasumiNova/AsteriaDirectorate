package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectKey
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.base.ShaderMaterialSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderProgramSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformDefinition
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSchema
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSet
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformType
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformValue
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.util.IntervalUtil
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 湮灭涡旋（Annihilation Vortex）光束的节点族：深红束体调色 + 命中端涡旋节点（规格 04 §3）。
 *
 * 与 GCP 的区分要点（验收：深红与 GCP 暗红可区分）：GCP 核心为**白芯+粉边**，AV 核心直接上
 * **深红芯+亮红缘**，辉光用更暗的血红；涡旋端为旋转深红涡旋面（GCP 是静态坍缩点）。
 */

/** 湮灭涡旋共用调色（LENS 深红族）。 */
object AvBeam {
    val CORE_DEEP = Color(255, 60, 70, 240)
    val CORE_FRINGE = Color(255, 130, 120, 200)
    val GLOW_DEEP = Color(180, 20, 40, 220)
    val HOT = Color(255, 80, 80, 220)

    /**
     * 湮灭涡旋束体给公共 [BeamCoreComponent] 的规格：深红芯+亮红缘核心、血红辉光、tip 0.80 taper、
     * 淡出由 fadeMul 整体收束（变细+变淡）、固定色（不逐帧 lerp，本武器无充能 ramp）、不施加填充淡化。
     * 宽度：coreW = baseWidth×1.15（强度恒 1 无 ramp）；glowW = coreW×2.0。
     */
    fun coreSpec(): BeamCoreSpec {
        val baseAlphaCore = 0.55f
        val baseEmCore = 2.40f
        val baseAlphaGlow = 0.16f
        val baseEmGlow = 1.70f
        return BeamCoreSpec(
            coreWidthBase = 1.15f, coreWidthRamp = 0f, coreWidthMin = 0f,
            glowWidthMul = 2.0f, glowWidthRamp = 0f, glowWidthMin = 0f,
            bodyWidthBase = 1f, bodyWidthRamp = 0f, tipWidthMul = 0.80f,
            fadeMulScalesWidth = true, fadeMulScalesAlpha = true,
            lerpColorPerFrame = false, applyEndFade = false,
            pieces = listOf(
                BeamCorePieceSpec(false, false, BeamPalette.CORE, baseAlphaCore, baseEmCore, 3.25f, -480f, CORE_DEEP, CORE_FRINGE),
                BeamCorePieceSpec(true, false, BeamPalette.CORE, baseAlphaCore * 0.62f, baseEmCore * 0.62f, 3.25f, 480f * 0.92f, CORE_DEEP, CORE_FRINGE),
                BeamCorePieceSpec(false, true, BeamPalette.GLOW, baseAlphaGlow, baseEmGlow, 3.60f, -300f, GLOW_DEEP, HOT),
                BeamCorePieceSpec(true, true, BeamPalette.GLOW, baseAlphaGlow * 0.62f, baseEmGlow * 0.62f, 3.60f, 300f * 0.92f, GLOW_DEEP, HOT),
            ),
        )
    }
}

/**
 * 命中端涡旋节点（规格 04 §3，涡旋 Shader 优先裁定）：
 * - **涡旋面**：旋转深红涡旋 shader（WorldQuad keyed upsert，每帧提交、[STALE_AFTER_SECONDS] 自然退休，无常驻句柄）；
 * - **低频内收扭曲点缀**：DistortionEntity 间隔 ~1s 一枚，微弱引力透镜感；
 * - **环布火花**：少量向心旋进火花（纯引擎粒子，无常驻句柄）；
 * - **吸收迁移 flare**：[onAbsorbed] 登记一枚迁移光点，沿直线收束到涡旋心后爆闪并推高 shader 脉冲。
 *
 * 半径档位经 [vortexRadius] 由 BeamEffect 建树后写入（builders 为无参注册表，参数走树节点属性注入）。
 */
class AnnihilationVortexVortexComponent(
    id: String,
    layer: CombatEngineLayers = CombatEngineLayers.ABOVE_PARTICLES,
    renderOrder: Int = RENDER_ORDER,
) : RenderEntityImpl(id, layer, renderOrder) {

    /** 涡旋半径（世界 su）。默认玩家 v2 档 187.5；BeamEffect 开火起点按难度档位覆写。 */
    var vortexRadius: Float = 187.5f

    /** 迁移中的吸收 flare。 */
    private data class AbsorbFlare(val from: Vector2f, val pos: Vector2f, var life: Float)

    private val flares = ArrayList<AbsorbFlare>()
    private val distortionInterval = IntervalUtil(DISTORTION_INTERVAL_MIN, DISTORTION_INTERVAL_MAX)
    private var sparkAcc = 0f

    /** shader 脉冲 0..1：吸收抵达时推高、随时间衰减，驱动涡旋心亮度（吸收可见反馈的一环）。 */
    private var pulse = 0f
    private var warnedDistortion = false
    private val zeroVel = Vector2f(0f, 0f)

    /** 吸收反馈入口：BeamEffect 经 AbsorbImpl 回调逐发调用（弹体被移除的世界坐标）。 */
    fun onAbsorbed(location: Vector2f) {
        if (flares.size >= FLARE_MAX) flares.removeAt(0)
        flares += AbsorbFlare(Vector2f(location), Vector2f(location), 0f)
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val center = ctx.frame.endpoint ?: return
        val fade = ctx.frame.fadeMul.coerceIn(0f, 1f)
        val radius = vortexRadius.coerceAtLeast(30f)

        // 1) 涡旋 shader 面：keyed 每帧 upsert；fadeMul 收 alphaMult，停提交后 stale 自然退休。
        if (fade > 0.001f) {
            AvVortexShader.submitFrame(
                sink = CombatShaderRuntime.ensure(engine).sink,
                instanceId = instanceId,
                center = center,
                frame = AvVortexShader.frame(radius, fade, pulse),
            )
        }

        if (ctx.frame.active) {
            // 2) 低频内收扭曲点缀（微弱，避免与坍缩大扭曲抢戏）。
            distortionInterval.advance(amount)
            if (distortionInterval.intervalElapsed()) spawnAccentDistortion(engine, center, radius)

            // 3) 环布向心火花。
            sparkAcc += SPARK_RATE * amount
            val sparkCount = sparkAcc.toInt().coerceAtMost(SPARK_MAX_PER_FRAME)
            if (sparkCount > 0) sparkAcc -= sparkCount
            repeat(sparkCount) { spawnRingSpark(engine, center, radius) }
        }

        // 4) 吸收迁移 flare（停火淡出期继续收束，不打断反馈）。
        advanceFlares(engine, center, amount)

        pulse = (pulse - amount / PULSE_DECAY_SECONDS).coerceAtLeast(0f)
    }

    override fun onDetachSelf() {
        flares.clear()
    }

    private fun spawnAccentDistortion(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val e = DistortionEntity()
        e.setGlobalTimer(0.05f, 0.30f, 0.40f)
        e.setInnerIn(0.35f, 0.35f)
        e.setInnerFull(0.35f, 0.35f)
        e.setInnerOut(0.35f, 0.35f)
        e.setInnerHardness(0.90f)
        e.setRingHardness(0.70f)
        e.setSizeIn(radius * 0.55f, radius * 0.55f)
        e.setSizeFull(radius * 0.32f, radius * 0.32f)
        e.setSizeOut(radius * 0.12f, radius * 0.12f)
        e.setPowerIn(0.10f)
        e.setPowerFull(0.22f)
        e.setPowerOut(0.30f)
        e.setLocation(center)
        val result = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)
        if (result != 0 && !warnedDistortion) {
            warnedDistortion = true
            log.warn("[ASTD] 湮灭涡旋低频扭曲 DistortionEntity 注册失败（addEntity 返回 $result），扭曲点缀缺失（涡旋面/火花照常）")
        }
    }

    private fun spawnRingSpark(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        val ang = MathUtils.getRandomNumberInRange(0f, 360f)
        val dist = radius * MathUtils.getRandomNumberInRange(0.75f, 1.0f)
        val rad = Math.toRadians(ang.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val loc = Vector2f(center.x + ux * dist, center.y + uy * dist)
        // 速度 = 切向旋进 + 向心收束（向心分量略强，保证火花 visibly 被卷入）。
        val tangent = MathUtils.getRandomNumberInRange(60f, 140f)
        val inward = MathUtils.getRandomNumberInRange(90f, 180f)
        val sign = if (Math.random() < 0.5f) 1f else -1f
        val vel = Vector2f(
            -uy * tangent * sign - ux * inward,
            ux * tangent * sign - uy * inward,
        )
        val size = MathUtils.getRandomNumberInRange(5f, 11f)
        val dur = MathUtils.getRandomNumberInRange(0.25f, 0.50f)
        val c = if (Math.random() < 0.5f) AvBeam.CORE_DEEP else AvBeam.HOT
        engine.addSmoothParticle(loc, vel, size, 1.2f, dur, c)
    }

    private fun advanceFlares(engine: CombatEngineAPI, center: Vector2f, amount: Float) {
        val it = flares.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.life += amount
            val t = (f.life / FLARE_TRAVEL_SECONDS).coerceIn(0f, 1f)
            // 直线收束到涡旋心（ease-in：末段加速，观感「被吸入」）。
            val k = t * t
            f.pos.set(f.from.x + (center.x - f.from.x) * k, f.from.y + (center.y - f.from.y) * k)

            val alpha = (1f - t * 0.4f).coerceIn(0f, 1f)
            engine.addSmoothParticle(
                f.pos, zeroVel, 13f, 1.5f, 0.10f,
                Color(AvBeam.HOT.red, AvBeam.HOT.green, AvBeam.HOT.blue, (230f * alpha).toInt().coerceIn(0, 255)),
            )
            engine.addSmoothParticle(
                f.pos, zeroVel, 22f, 0.5f, 0.16f,
                Color(AvBeam.GLOW_DEEP.red, AvBeam.GLOW_DEEP.green, AvBeam.GLOW_DEEP.blue, (120f * alpha).toInt().coerceIn(0, 255)),
            )

            if (t >= 1f) {
                // 抵达涡旋心：爆闪 + 推高 shader 脉冲。
                engine.addSmoothParticle(
                    center, zeroVel, 30f, 1.8f, 0.14f,
                    Color(AvBeam.HOT.red, AvBeam.HOT.green, AvBeam.HOT.blue, 235),
                )
                pulse = 1f
                it.remove()
            }
        }
    }

    private val instanceId = "avortex:" + System.identityHashCode(this)

    companion object {
        /** 涡旋节点在树内的次级绘制序（束体 100 之上、GCP 系虹吸 300 之下）。 */
        const val RENDER_ORDER = 200

        private const val DISTORTION_INTERVAL_MIN = 0.85f
        private const val DISTORTION_INTERVAL_MAX = 1.15f
        private const val SPARK_RATE = 14f
        private const val SPARK_MAX_PER_FRAME = 4
        private const val FLARE_TRAVEL_SECONDS = 0.28f
        private const val FLARE_MAX = 48
        private const val PULSE_DECAY_SECONDS = 0.45f

        private val log = Global.getLogger(AnnihilationVortexVortexComponent::class.java)
    }
}

/**
 * 湮灭涡旋命中端的旋转深红涡旋 shader（结构镜像 [cn.kasuminova.astd.renderer.effect.lens.PermeatingTideFieldEffect]）。
 *
 * GL 程序、layer 插件、生命周期全部委托共享 shader runtime；本对象只持有效果参数与「半径/fade/脉冲 → shader 提交」
 * 的转换。keyed upsert 按组件实例区分（多武器同时开火互不覆盖），停提交 [STALE_AFTER_SECONDS] 后自然退休。
 * 混合模式 Additive：能量场辉光口径，与潮汐场/定影场同系 BelowParticles 统一。
 */
private object AvVortexShader {

    /** 提交后超过此秒数无更新即判定过期（开火期每帧推进，远快于此）。 */
    const val STALE_AFTER_SECONDS = 0.18f

    /** 渲染 quad 须比涡旋半径略大，给外缘羽化留空间，避免被 quad 硬切。与 GLSL 内 1.15 字面量保持同步。 */
    private const val FEATHER_MARGIN_MULT = 1.15f

    private const val EFFECT_ID = "astd_annihilation_vortex_field"
    private const val PROGRAM_ID = "astd_annihilation_vortex_field_program"

    private val SHADER_UNIFORMS = ShaderUniformSchema(
        listOf(
            ShaderUniformDefinition(
                key = "resolution",
                type = ShaderUniformType.Vec2,
                required = false,
                defaultValue = ShaderUniformValue.Vec2(1f, 1f),
            ),
            // 整体不透明度（fadeMul 收束包络 × 基础亮度）。
            ShaderUniformDefinition("alphaMult", ShaderUniformType.Float),
            // 吸收脉冲 0→1：涡旋心亮度瞬时推高后衰减（吸收反馈）。
            ShaderUniformDefinition("pulse", ShaderUniformType.Float),
            // 归一域半径：v_uv∈[0,1] → 中心化坐标半径范围（涡旋缘落在 domain 内）。
            ShaderUniformDefinition("domainRadius", ShaderUniformType.Float),
        ),
    )

    val effectSpec: ShaderEffectSpec = ShaderEffectSpec(
        id = ShaderEffectKey(EFFECT_ID),
        program = ShaderProgramSpec(
            id = PROGRAM_ID,
            vertexSource = VERTEX_SHADER_SOURCE,
            fragmentSource = FRAGMENT_SHADER_SOURCE,
        ),
        geometry = ShaderGeometrySpec.WorldQuad(quadHalfExtentFor(187.5f)),
        material = ShaderMaterialSpec(ShaderBlendMode.Additive),
        uniformSchema = SHADER_UNIFORMS,
        layer = ShaderEffectLayer.BelowParticles,
        lifetimeSeconds = 1f,
        staleAfterSeconds = STALE_AFTER_SECONDS,
        renderRadius = quadHalfExtentFor(187.5f),
    )

    /** 单帧涡旋渲染参数（纯几何/亮度，便于单测）。 */
    data class Frame(
        val quadHalfExtentWorld: Float,
        val alphaMult: Float,
        val pulse: Float,
    )

    fun quadHalfExtentFor(radius: Float): Float = radius.coerceAtLeast(30f) * FEATHER_MARGIN_MULT

    /**
     * 计算单帧涡旋参数。alphaMult = fade × 基础 0.42（涡旋是主角之一，比潮汐场 0.26 略亮）；
     * 逐帧旋转/湍流由 FRAGMENT 内 u_time 完成，此处不含时间项。
     */
    fun frame(radius: Float, fade: Float, pulse: Float): Frame = Frame(
        quadHalfExtentWorld = quadHalfExtentFor(radius),
        alphaMult = fade.coerceIn(0f, 1f) * 0.42f,
        pulse = pulse.coerceIn(0f, 1f),
    )

    /** 提交/更新一个涡旋实例（keyed upsert）。 */
    fun submitFrame(sink: ShaderSink, instanceId: String, center: Vector2f, frame: Frame) {
        if (frame.alphaMult <= 0.001f) return
        sink.upsert(
            spec = effectSpec.copy(
                geometry = ShaderGeometrySpec.WorldQuad(frame.quadHalfExtentWorld),
                renderRadius = frame.quadHalfExtentWorld,
            ),
            instanceId = instanceId,
            center = center,
            facing = 0f,
            uniforms = ShaderUniformSet(
                SHADER_UNIFORMS,
                mapOf(
                    "alphaMult" to ShaderUniformValue.FloatValue(frame.alphaMult),
                    "pulse" to ShaderUniformValue.FloatValue(frame.pulse),
                    "domainRadius" to ShaderUniformValue.FloatValue(FEATHER_MARGIN_MULT),
                ),
            ),
        )
    }

    private const val VERTEX_SHADER_SOURCE = """
        varying vec2 v_uv;

        void main() {
          v_uv = gl_MultiTexCoord0.xy;
          gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
        }
    """

    // NOTE: GLSL comments must be ASCII only - Starsector's GL driver fails to lex
    // multibyte UTF-8 in comments ("unexpected end of file"). Do not use CJK here.
    private const val FRAGMENT_SHADER_SOURCE = """
        uniform float u_time;
        uniform vec2 u_resolution;
        uniform float u_alphaMult;
        uniform float u_pulse;
        uniform float u_domainRadius;

        varying vec2 v_uv;

        vec2 centeredAspect(vec2 uv) {
          vec2 p = uv * 2.0 - 1.0;
          p.x *= u_resolution.x / max(u_resolution.y, 1.0);
          return p * u_domainRadius;
        }

        float hash21(vec2 p) {
          p = fract(p * vec2(123.34, 345.45));
          p += dot(p, p + 34.345);
          return fract(p.x * p.y);
        }

        float valueNoise(vec2 p) {
          vec2 i = floor(p);
          vec2 f = fract(p);
          vec2 u = f * f * (3.0 - 2.0 * f);
          float a = hash21(i);
          float b = hash21(i + vec2(1.0, 0.0));
          float c = hash21(i + vec2(0.0, 1.0));
          float d = hash21(i + vec2(1.0, 1.0));
          return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        float fbm(vec2 p) {
          float value = 0.0;
          float amplitude = 0.5;
          for (int i = 0; i < 4; i++) {
            value += amplitude * valueNoise(p);
            p *= 2.03;
            amplitude *= 0.5;
          }
          return value;
        }

        void main() {
          vec2 p = centeredAspect(v_uv);
          float r = length(p);
          float ang = atan(p.y, p.x);

          // Vortex edge in normalized domain: quad half-extent = radius * feather mult,
          // so the edge sits at 1 / feather mult. This 1.15 MUST stay in sync with the
          // Kotlin-side FEATHER_MARGIN_MULT (GLSL cannot reference a Kotlin const).
          float vortexEdge = 1.0 / 1.15;
          if (r > vortexEdge) {
            gl_FragColor = vec4(0.0);
            return;
          }
          float rn = r / vortexEdge;

          // ---- Spiral: angle winds tighter toward center, whole pattern rotates over time ----
          float wound = ang + rn * 5.5 - u_time * 2.2;
          float arms = 0.5 + 0.5 * sin(wound * 3.0);
          float turbulence = fbm(vec2(rn * 4.5 - u_time * 0.35, wound * 0.8));
          float swirl = mix(arms, turbulence, 0.45);

          // ---- Radial profile: dark eye, bright mid ring, feathered edge ----
          float eye = smoothstep(0.02, 0.22, rn);
          float midRing = 1.0 - smoothstep(0.25, 1.0, rn);
          float edge = 1.0 - smoothstep(vortexEdge - 0.16, vortexEdge, r);

          float body = eye * midRing * (0.35 + 0.65 * swirl) * edge;
          // Absorption pulse: brightens the eye ring and inner region briefly.
          float pulseGlow = u_pulse * (1.0 - smoothstep(0.0, 0.55, rn));

          vec3 deepRed = vec3(0.71, 0.08, 0.16);
          vec3 hotRed = vec3(1.0, 0.31, 0.31);
          vec3 color = deepRed * body + hotRed * (body * swirl * 0.8 + pulseGlow * 0.9);

          float alpha = clamp(body + pulseGlow * 0.7, 0.0, 1.0);
          gl_FragColor = vec4(color, alpha * u_alphaMult);
        }
    """
}
