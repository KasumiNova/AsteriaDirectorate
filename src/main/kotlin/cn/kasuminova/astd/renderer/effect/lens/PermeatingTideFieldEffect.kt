package cn.kasuminova.astd.renderer.effect.lens

import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectKey
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.base.ShaderMaterialSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderProgramSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformDefinition
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSchema
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSet
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformType
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformValue
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import org.lwjgl.util.vector.Vector2f

/**
 * 渗透潮汐场 shader 效果（Permeating Tide field，LENS 紫罗兰主题，spec §5）。
 *
 * 动机（Task 11 / spec §5.1）：渗透潮汐插件在本舰周围 ~2500su 形成「电战潮汐场」。涨潮期需要把
 * 这片正在涨起来的水可视化——一片以本舰为心、向外渗透的紫罗兰潮汐场：FBM 径向涟漪 +
 * 随潮位涨落的整体 alpha 与边缘呼吸。「它不是一堵墙，是一片正在涨起来的水。」
 *
 * 与 [EchoFixationFieldVisualEffect]（细环边界 SDF）的关键差异：定影场强调**边界环**；潮汐场强调
 * **整片填充的渗透水面**——内核柔和填充辉光 + FBM 涟漪 + 一道柔和外缘羽化，无锐利刻度环。
 * FBM/valueNoise/hash21 GLSL 取自 [cn.kasuminova.astd.renderer.effect.system.ArcJetShockwaveRingEffect]。
 *
 * 本对象只持有效果参数与「潮位 → shader 提交」的转换；GL 程序、layer 插件、生命周期、状态管理
 * 全部委托给共享 shader runtime。结构镜像 [EchoFixationFieldVisualEffect]。
 *
 * 绑定：潮汐场绑**本舰**（per-ship instanceId），调用方（[cn.kasuminova.astd.combat.hullmods.lens.ASTDLensPermeatingTideHullMod]）
 * 用 "tide-${identityHashCode(ship)}" 拼 instanceId，每帧 keyed upsert 跟随本舰位置。
 *
 * **混合模式选择 Additive（注）**：潮汐场是「电战能量场辉光」，叠加发光在深空背景上观感最贴近
 * 「渗透/涨起来的水」的能量感，且与定影场/幽灵信号波等同系 BelowParticles Additive 场视觉统一；
 * frame 已将基础填充 alpha 压低（避免大面积 Additive 过曝盖住下方舰船），靠 tideLevel 调制涨落。
 */
internal object PermeatingTideFieldEffect {

    /** 提交后超过此秒数无更新即判定过期（涨潮期每帧推进，远快于此；退潮停止提交后由此自然退休）。 */
    const val STALE_AFTER_SECONDS = 0.18f

    /** 紫罗兰主色 hue（色彩指令：主色 hue ≈ 0.76）。 */
    const val PRIMARY_HUE = 0.76f

    /** 主色饱和度（色彩指令：saturation ≈ 0.55~0.7，取中段）。 */
    const val PRIMARY_SATURATION = 0.62f

    /** 内层辅色 hue（淡紫/偏红辅色，色彩指令：辅色淡紫或红色——取淡紫近端，仍属紫罗兰带）。 */
    const val ACCENT_HUE = 0.78f

    /** 内层辅色饱和度（低饱和淡紫，作潮心微光）。 */
    const val ACCENT_SATURATION = 0.5f

    /**
     * 潮汐场半径基础值（su）。与 [cn.kasuminova.astd.combat.hullmods.lens.PermeatingTideMath.FIELD_RADIUS]
     * 对齐（2500su）。用于给 spec 静态 renderRadius 一个稳定下限。
     */
    private const val BASE_FIELD_RADIUS = 2500f

    /**
     * 边缘羽化余量倍率：渲染 quad 须比场半径略大，给场外缘 smoothstep 羽化留空间，避免被 quad 硬切。
     */
    private const val FEATHER_MARGIN_MULT = 1.06f

    private const val EFFECT_ID = "astd_permeating_tide_field"
    private const val PROGRAM_ID = "astd_permeating_tide_field_program"

    private val SHADER_UNIFORMS = ShaderUniformSchema(
        listOf(
            ShaderUniformDefinition(
                key = "resolution",
                type = ShaderUniformType.Vec2,
                required = false,
                defaultValue = ShaderUniformValue.Vec2(1f, 1f),
            ),
            // 潮位 0→1：驱动整体 alpha / 涟漪强度 / 边缘呼吸（涨潮升、退潮降）。
            ShaderUniformDefinition("tideLevel", ShaderUniformType.Float),
            ShaderUniformDefinition("hue", ShaderUniformType.Float),
            ShaderUniformDefinition("saturation", ShaderUniformType.Float),
            ShaderUniformDefinition("accentHue", ShaderUniformType.Float),
            ShaderUniformDefinition("accentSaturation", ShaderUniformType.Float),
            ShaderUniformDefinition("alphaMult", ShaderUniformType.Float),
            // 归一域半径：v_uv∈[0,1] → 中心化坐标半径范围（场缘落在 domain 内）。
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
        geometry = ShaderGeometrySpec.WorldQuad(quadHalfExtentFor(BASE_FIELD_RADIUS)),
        material = ShaderMaterialSpec(ShaderBlendMode.Additive),
        uniformSchema = SHADER_UNIFORMS,
        layer = ShaderEffectLayer.BelowParticles,
        lifetimeSeconds = 1f,
        staleAfterSeconds = STALE_AFTER_SECONDS,
        renderRadius = quadHalfExtentFor(BASE_FIELD_RADIUS),
    )

    /**
     * 单帧潮汐场渲染参数（纯几何/颜色，便于单测）。
     *
     * @property fieldRadiusWorld 场半径（世界单位，未含羽化）。
     * @property outerRadiusWorld 含羽化的外半径（= quadHalfExtentWorld，用作 renderRadius/culling）。
     * @property quadHalfExtentWorld 渲染 quad 半边长（覆盖场 + 羽化）。
     * @property shaderDomainRadius 归一域半径（FRAGMENT centeredAspect 缩放）。
     * @property tideLevel 潮位 0→1（涨潮升/退潮降，驱动 alpha 与涟漪强度）。
     * @property hue/saturation 主色（紫罗兰）。
     * @property accentHue/accentSaturation 内层辅色（淡紫潮心微光）。
     * @property alphaMult 整体不透明度（随 tideLevel 涨落）。
     */
    data class Frame(
        val fieldRadiusWorld: Float,
        val outerRadiusWorld: Float,
        val quadHalfExtentWorld: Float,
        val shaderDomainRadius: Float,
        val tideLevel: Float,
        val hue: Float,
        val saturation: Float,
        val accentHue: Float,
        val accentSaturation: Float,
        val alphaMult: Float,
    )

    /**
     * 含羽化余量的 quad 半边长 / renderRadius。
     *
     * coerceAtLeast(BASE_FIELD_RADIUS) 动机：spec 静态构造需稳定 renderRadius 下限——即便调用方传入
     * 偏小 radius，仍以 2500su 基础场半径算 quad/culling 边界，避免渲染包围盒过小裁掉场缘。
     */
    fun quadHalfExtentFor(fieldRadius: Float): Float =
        fieldRadius.coerceAtLeast(BASE_FIELD_RADIUS) * FEATHER_MARGIN_MULT

    /**
     * 计算单帧潮汐场参数。
     *
     * alphaMult 为随 tideLevel 的纯线性静态涨落包络（退潮趋近 0 时场近乎消散，涨满时最显）；
     * 逐帧涟漪流动由 FRAGMENT 内的 u_time 完成，此处不含逐帧脉动。
     *
     * @param fieldRadius 场半径（世界单位，默认基础 2500su；若按难度/属性缩放传实际值）。
     * @param tideLevel 潮位 0→1（涨潮节奏，来自 hullmod 涨落驱动）。
     */
    fun frame(tideLevel: Float, fieldRadius: Float = BASE_FIELD_RADIUS): Frame {
        val level = tideLevel.coerceIn(0f, 1f)
        val outer = quadHalfExtentFor(fieldRadius)
        // 包络：退潮（level→0）场近消散；涨满（level→1）约 0.26，降亮 ~53% 避免大战场抢眼（用户反馈）。
        // Additive 下 alphaMult 线性控制亮度贡献；GLSL 内部无额外固定过曝源（body/core 系形状设计），只改此处即可。
        val alphaMult = level * 0.26f
        return Frame(
            fieldRadiusWorld = fieldRadius,
            outerRadiusWorld = outer,
            quadHalfExtentWorld = outer,
            shaderDomainRadius = 1.12f,
            tideLevel = level,
            hue = PRIMARY_HUE,
            saturation = PRIMARY_SATURATION,
            accentHue = ACCENT_HUE,
            accentSaturation = ACCENT_SATURATION,
            alphaMult = alphaMult,
        )
    }

    fun shouldRetire(ageSinceLastSubmit: Float): Boolean = ageSinceLastSubmit > STALE_AFTER_SECONDS

    /**
     * 提交/更新一个潮汐场实例（keyed upsert）。
     *
     * @param instanceId 场实例稳定标识（调用方拼 "tide-${identityHashCode(ship)}"），per-ship。
     * @param center 场心（本舰世界坐标）。
     */
    fun submitFrame(
        sink: ShaderSink,
        instanceId: String,
        center: Vector2f,
        frame: Frame,
    ): ShaderHandle? {
        if (frame.alphaMult <= 0.001f) return null
        return sink.upsert(
            spec = effectSpec.copy(
                geometry = ShaderGeometrySpec.WorldQuad(frame.quadHalfExtentWorld),
                renderRadius = frame.outerRadiusWorld,
            ),
            instanceId = instanceId,
            center = center,
            facing = 0f,
            uniforms = uniforms(frame),
        )
    }

    private fun uniforms(frame: Frame): ShaderUniformSet = ShaderUniformSet(
        SHADER_UNIFORMS,
        mapOf(
            "tideLevel" to ShaderUniformValue.FloatValue(frame.tideLevel),
            "hue" to ShaderUniformValue.FloatValue(frame.hue),
            "saturation" to ShaderUniformValue.FloatValue(frame.saturation),
            "accentHue" to ShaderUniformValue.FloatValue(frame.accentHue),
            "accentSaturation" to ShaderUniformValue.FloatValue(frame.accentSaturation),
            "alphaMult" to ShaderUniformValue.FloatValue(frame.alphaMult),
            "domainRadius" to ShaderUniformValue.FloatValue(frame.shaderDomainRadius),
        ),
    )

    private const val VERTEX_SHADER_SOURCE = """
        varying vec2 v_uv;

        void main() {
          v_uv = gl_MultiTexCoord0.xy;
          gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
        }
    """

    private const val FRAGMENT_SHADER_SOURCE = """
        uniform float u_time;
        uniform vec2 u_resolution;
        uniform float u_tideLevel;
        uniform float u_hue;
        uniform float u_saturation;
        uniform float u_accentHue;
        uniform float u_accentSaturation;
        uniform float u_alphaMult;
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
          for (int i = 0; i < 5; i++) {
            value += amplitude * valueNoise(p);
            p *= 2.02;
            amplitude *= 0.5;
          }
          return value;
        }

        vec3 hsv2rgb(vec3 c) {
          vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
          return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
        }

        void main() {
          vec2 p = centeredAspect(v_uv);
          float r = length(p);
          float ang = atan(p.y, p.x);

          // Field edge in normalized domain: quad half-extent = fieldRadius * feather mult,
          // so the edge sits at radius = 1 / feather mult. This 1.06 MUST stay in sync with the
          // Kotlin-side FEATHER_MARGIN_MULT (GLSL cannot reference a Kotlin const).
          // NOTE: GLSL comments must be ASCII only - Starsector's GL driver fails to lex
          // multibyte UTF-8 in comments ("unexpected end of file"). Do not use CJK here.
          float fieldEdge = 1.0 / 1.06;

          // Outside the circular field: fully transparent.
          if (r > fieldEdge) {
            gl_FragColor = vec4(0.0);
            return;
          }

          // Normalized radius 0 (center) -> 1 (edge): drives center falloff and ripple phase.
          float rn = r / fieldEdge;

          // ---- Permeating ripples: concentric FBM wavefronts pushing slowly outward ----
          // Radial phase advances with time; FBM + angular drift avoid a mechanical look.
          float ripplePhase = rn * 9.0 - u_time * 0.6;
          float ripple = 0.5 + 0.5 * sin(ripplePhase);
          float turbulence = fbm(vec2(rn * 4.0 + u_time * 0.15, ang * 1.5));
          // Mix ripple + turbulence; higher tide level = denser/brighter water texture.
          float waterTexture = mix(0.35, 1.0, u_tideLevel) * (ripple * 0.55 + turbulence * 0.45);

          // ---- Center fill glow: the water body itself (soft, bright center, dim edge) ----
          float fill = (1.0 - smoothstep(0.0, fieldEdge, r));
          fill = pow(fill, 1.4);

          // ---- Edge breathing feather: the field edge swells gently over time ----
          float edgeBreath = 0.04 * sin(u_time * 0.8 + ang * 2.0);
          float edge = 1.0 - smoothstep(fieldEdge - 0.18, fieldEdge + edgeBreath, r);

          // Water brightness = fill glow * ripple texture, plus a brighter tidal core glow.
          float body = fill * (0.45 + 0.55 * waterTexture) * edge;
          float core = (1.0 - smoothstep(0.0, fieldEdge * 0.35, r)) * 0.35 * u_tideLevel;

          vec3 primary = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
          vec3 accent = hsv2rgb(vec3(u_accentHue, u_accentSaturation, 1.0));

          vec3 color = primary * body + accent * core;

          float alpha = clamp(body + core, 0.0, 1.0);
          gl_FragColor = vec4(color, alpha * u_alphaMult);
        }
    """
}
