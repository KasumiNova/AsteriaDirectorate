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
 * Echo-Fixation field boundary shader effect (LENS violet theme).
 *
 * 动机（Task 6 / spec §2.1）：回声定影舰船系统在落点建一个圆形「定影场」（半径基础 700su）。
 * 施放/定影期内需要把场边界可视化出来——一道圆形 SDF 环 + 随定影进度脉动的紫罗兰发光环，
 * 用以向玩家提示「这片区域正在被定影」。
 *
 * 本对象只持有效果参数与「domain 状态 → shader 提交」的转换；GL 程序、layer 插件、生命周期、
 * 状态管理全部委托给共享 shader runtime。结构镜像
 * [cn.kasuminova.astd.renderer.effect.system.ArcJetShockwaveRingEffect]。
 *
 * 与 ArcJet 的关键差异：ArcJet 绑定单船（per-ship instanceId），本效果绑定一个定影场实例
 * （per-field instanceId）——场不是绑定到某条 ShipAPI，而是绑定到落点。调用方（EchoFixationField）
 * 用场的稳定自增 id 拼出 instanceId，见 [submitFrame]。
 */
internal object EchoFixationFieldVisualEffect {

    /** 提交后超过此秒数无更新即判定过期（定影期每帧推进，远快于此）。 */
    const val STALE_AFTER_SECONDS = 0.18f

    /** 紫罗兰主色 hue（约 275°，色彩指令：主色紫色 hue ≈ 0.76）。 */
    const val PRIMARY_HUE = 0.76f

    /** 主色饱和度（色彩指令：saturation ≈ 0.6~0.75，取中段偏高）。 */
    const val PRIMARY_SATURATION = 0.68f

    /** 内圈辅色 hue（淡紫，色彩指令：辅色淡紫 0.72~0.78、低饱和）。 */
    const val ACCENT_HUE = 0.74f

    /** 内圈辅色饱和度（低饱和淡紫高光）。 */
    const val ACCENT_SATURATION = 0.45f

    /**
     * 场半径基础值（su）。与 [cn.kasuminova.astd.combat.lens.system.EchoFixationField] 的
     * BASE_FIELD_RADIUS 对齐；用于在无场实例时给 spec 一个静态 renderRadius 下限。
     */
    private const val BASE_FIELD_RADIUS = 700f

    /**
     * 边缘羽化余量倍率：渲染 quad 须比场半径略大，给环外侧 smoothstep 羽化留出空间，
     * 避免环被 quad 边界硬切。
     */
    private const val FEATHER_MARGIN_MULT = 1.08f

    private const val EFFECT_ID = "astd_echo_fixation_field"
    private const val PROGRAM_ID = "astd_echo_fixation_field_program"

    private val SHADER_UNIFORMS = ShaderUniformSchema(
        listOf(
            ShaderUniformDefinition(
                key = "resolution",
                type = ShaderUniformType.Vec2,
                required = false,
                defaultValue = ShaderUniformValue.Vec2(1f, 1f),
            ),
            // 定影进度 0→1：驱动环宽脉动 / alpha 渐显与收束。
            ShaderUniformDefinition("progress", ShaderUniformType.Float),
            ShaderUniformDefinition("hue", ShaderUniformType.Float),
            ShaderUniformDefinition("saturation", ShaderUniformType.Float),
            ShaderUniformDefinition("accentHue", ShaderUniformType.Float),
            ShaderUniformDefinition("accentSaturation", ShaderUniformType.Float),
            ShaderUniformDefinition("alphaMult", ShaderUniformType.Float),
            // 归一域半径：v_uv∈[0,1] → 中心化坐标的半径范围（环落在 domain 内）。
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
     * 单帧场边界渲染参数（纯几何/颜色，便于单测）。
     *
     * @property fieldRadiusWorld 场半径（世界单位，未含羽化）。
     * @property outerRadiusWorld 含羽化的外半径（= quadHalfExtentWorld，用作 renderRadius/culling）。
     * @property quadHalfExtentWorld 渲染 quad 半边长（覆盖环 + 羽化）。
     * @property shaderDomainRadius 归一域半径（FRAGMENT centeredAspect 缩放）。
     * @property progress 定影进度 0→1。
     * @property hue/saturation 主色（紫罗兰）。
     * @property accentHue/accentSaturation 内圈辅色（淡紫高光）。
     * @property alphaMult 整体不透明度（随 progress 渐显）。
     */
    data class Frame(
        val fieldRadiusWorld: Float,
        val outerRadiusWorld: Float,
        val quadHalfExtentWorld: Float,
        val shaderDomainRadius: Float,
        val progress: Float,
        val hue: Float,
        val saturation: Float,
        val accentHue: Float,
        val accentSaturation: Float,
        val alphaMult: Float,
    )

    /**
     * 含羽化余量的 quad 半边长 / renderRadius。
     *
     * coerceAtLeast(BASE_FIELD_RADIUS) 的动机：spec 在无场实例时（effectSpec 静态构造）需要一个
     * 稳定的 renderRadius 下限——若调用方传入的 fieldRadius 因系统射程缩成小于基础值，仍以
     * 700su 基础场半径计算 quad/culling 边界，避免渲染包围盒过小裁掉环。运行期每帧的 frame()
     * 传入的是已按射程缩放的实际 radius，正常 >= 700su。
     */
    fun quadHalfExtentFor(fieldRadius: Float): Float =
        fieldRadius.coerceAtLeast(BASE_FIELD_RADIUS) * FEATHER_MARGIN_MULT

    /**
     * 计算单帧场边界参数。
     *
     * alphaMult 为随 progress 的纯线性静态渐显包络（定影刚起淡入，接近完成时略增亮）；
     * 逐帧脉动由 FRAGMENT 内的 u_time 正弦完成，此处不含脉动。
     *
     * @param fieldRadius 场半径（世界单位，来自 EchoFixationField.Field.radius）。
     * @param progress 定影进度 0→1（elapsed / fixateDuration）。
     */
    fun frame(fieldRadius: Float, progress: Float): Frame {
        val p = progress.coerceIn(0f, 1f)
        val outer = quadHalfExtentFor(fieldRadius)
        // 包络：起始 0.35 淡入，随 progress 升至约 0.85（接近定影完成时最显）。
        val alphaMult = 0.35f + p * 0.50f
        return Frame(
            fieldRadiusWorld = fieldRadius,
            outerRadiusWorld = outer,
            quadHalfExtentWorld = outer,
            shaderDomainRadius = 1.15f,
            progress = p,
            hue = PRIMARY_HUE,
            saturation = PRIMARY_SATURATION,
            accentHue = ACCENT_HUE,
            accentSaturation = ACCENT_SATURATION,
            alphaMult = alphaMult,
        )
    }

    fun shouldRetire(ageSinceLastSubmit: Float): Boolean = ageSinceLastSubmit > STALE_AFTER_SECONDS

    /**
     * 提交/更新一个定影场实例的边界效果（keyed upsert）。
     *
     * @param instanceId 场实例稳定标识（调用方拼 "echo-field-{fieldId}"），per-field 而非 per-ship。
     * @param center 场心（世界坐标）。
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
            "progress" to ShaderUniformValue.FloatValue(frame.progress),
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
        uniform float u_progress;
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

        vec3 hsv2rgb(vec3 c) {
          vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
          return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
        }

        void main() {
          vec2 p = centeredAspect(v_uv);
          float r = length(p);
          float ang = atan(p.y, p.x);

          // Boundary ring radius: ring sits at the field radius. quad half-extent = fieldRadius *
          // feather mult, so the ring in normalized domain = 1 / feather mult. This 1.08 MUST stay
          // in sync with the Kotlin-side FEATHER_MARGIN_MULT (GLSL cannot reference a Kotlin const).
          // NOTE: GLSL comments must be ASCII only - Starsector's GL driver fails to lex
          // multibyte UTF-8 in comments ("unexpected end of file"). Do not use CJK here.
          float ringRadius = 1.0 / 1.08;

          // Fixation pulse: u_time sine drives ring width and brightness; higher progress = faster.
          float pulseFreq = 2.0 + u_progress * 4.0;
          float pulse = 0.5 + 0.5 * sin(u_time * pulseFreq);

          // Ring width: base + pulse contraction (narrower/sharper at high progress).
          float baseWidth = mix(0.10, 0.035, u_progress);
          float width = baseWidth * (0.65 + 0.35 * pulse);

          // Circular SDF boundary -> ring alpha, two-sided smoothstep feather.
          float ringSdf = abs(r - ringRadius);
          float ring = 1.0 - smoothstep(0.0, width, ringSdf);

          // Fine angular tick modulation (scanning feel), rotating over time.
          float ticks = 0.85 + 0.15 * sin(ang * 48.0 + u_time * 1.5);
          ring *= ticks;

          // Inner light-violet accent highlight: a thinner ring further inward, low saturation.
          float innerRadius = ringRadius * 0.82;
          float innerSdf = abs(r - innerRadius);
          float inner = (1.0 - smoothstep(0.0, 0.04, innerSdf)) * (0.35 + 0.35 * pulse);

          // Very faint interior fill glow (makes the enclosed area readable, center falloff).
          float fill = (1.0 - smoothstep(0.0, ringRadius, r)) * 0.06 * u_progress;

          vec3 primary = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
          vec3 accent = hsv2rgb(vec3(u_accentHue, u_accentSaturation, 1.0));

          float ringEnergy = ring * (0.85 + 0.45 * pulse);
          vec3 color = primary * (ringEnergy + fill) + accent * inner;

          float alpha = clamp(ringEnergy + inner + fill, 0.0, 1.0);
          gl_FragColor = vec4(color, alpha * u_alphaMult);
        }
    """
}
