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
 * 幽灵信号径向干扰波 shader 效果（LENS 紫罗兰主题，Task 9 / spec §3 无人·幽灵信号）。
 *
 * 动机：无人模式「幽灵信号」每个心跳（0.25s）从范围内敌方导弹身上剥离制导（电子战脉冲）。
 * 当某一 tick 实际剥离了至少一枚导弹时，以本舰为中心放一道由内向外扩张的紫罗兰干扰波，
 * 表现电子战脉冲在战场上的扩散——向玩家提示「幽灵信号刚刚生效了一次」。波形态参照
 * [cn.kasuminova.astd.renderer.effect.system.ArcJetShockwaveRingEffect]（径向扩张环），
 * 颜色走 LENS 主色紫罗兰（hue ≈ 0.76），叠扫描噪声做「干扰」质感。
 *
 * 本对象只持有效果参数与「波进度 → shader 提交」的转换；GL 程序、layer 插件、生命周期、
 * 状态管理全部委托共享 shader runtime。结构镜像 [EchoFixationFieldVisualEffect] /
 * [ArcJetShockwaveRingEffect]。
 *
 * **提交模型——设计决策（依据已核实的 renderer 行为）：keyed upsert + CPU 驱动 progress。**
 * 任务建议「一次性脉冲优先 one-shot emit」，但实测 renderer 契约不支持 one-shot 自播放动画：
 * [cn.kasuminova.astd.renderer.shader.runtime.ShaderLayerPlugin] 把 `u_time` 绑定为
 * `submission.ageSeconds = submittedAt - startedAt`；而 [ShaderRenderQueue.emit] 在入队时令
 * `submittedAt == startedAt`，且 `advance()` 永不更新 one-shot 的 `submittedAt`——故 one-shot 的
 * `u_time` 全生命周期恒为 0，无法在着色器内用 `u_time` 推进波扩张。能让时间前进的只有 keyed
 * upsert（每帧 upsert 刷新 `submittedAt`、保留 `startedAt`，`ageSeconds` 随之增长，ArcJet 即此机制）。
 *
 * 因此本效果用 keyed upsert，但波扩张进度不依赖 `u_time`，而是 **CPU 侧 `progress` uniform**
 * 显式驱动（0→1 推进波前半径与 alpha 包络）。调用方（[ASTDLensArrayCoreHullMod]）在每个产生剥离
 * 的 ghost tick 起一道波，记录起始时刻，随后每帧按 elapsed/[WAVE_DURATION] 重新 upsert，progress
 * 达到 1 即停止刷新，实例经 staleAfter 自然退休——语义上仍是「一次性脉冲，自生自灭」，只是技术上
 * 落在 keyed 通道以获得可动画的时间轴。`u_time` 仍用于叠加扫描线/噪声的细节抖动，非扩张主驱动。
 */
internal object GhostSignalWaveEffect {

    /**
     * 单道波的播放时长（s）：从波心扩张到外缘的总时间。
     * 0.6s 让脉冲明显可辨又不拖沓；ghost tick 间隔 0.25s，相邻波会自然叠加成连绵脉冲流。
     */
    const val WAVE_DURATION = 0.6f

    /**
     * 提交后超过此秒数无更新即判定过期。须大于一帧（约 1/60s）以容忍帧抖动，又须远小于
     * [WAVE_DURATION] 以保证 progress 推进到 1 停止刷新后能尽快退休。0.12s ≈ 7 帧。
     */
    const val STALE_AFTER_SECONDS = 0.12f

    /** 紫罗兰主色 hue（约 275°，色彩指令：LENS 主色 hue ≈ 0.76）。 */
    const val PRIMARY_HUE = 0.76f

    /** 主色饱和度（色彩指令：saturation ≈ 0.55~0.7，取中段 0.62）。 */
    const val PRIMARY_SATURATION = 0.62f

    /** 辅色 hue（淡紫高光，色彩指令：辅色淡紫/红，取淡紫 0.80 偏品红一侧）。 */
    const val ACCENT_HUE = 0.80f

    /** 辅色饱和度（低饱和淡紫）。 */
    const val ACCENT_SATURATION = 0.45f

    /**
     * 幽灵信号作用半径（su），与 tooltip ~2000su 对齐。
     *
     * 此值仅为静态 [effectSpec] 的 renderRadius 占位下限（spec 构造期需要一个稳定的 culling 边界）；
     * 运行时 [submitFrame] 以传入 frame 的实际 `quadHalfExtentWorld` 覆盖 geometry/renderRadius，
     * 故 culling 始终按实际波尺寸走。阶段二 DIFFICULTY_FACTOR 对范围的缩放在
     * [cn.kasuminova.astd.combat.hullmods.lens.ASTDLensArrayCoreHullMod] 侧处理（调用方传已缩放的
     * range），此处无需随之缩放——effect 侧与 hullmod 侧的两个 GHOST_RANGE 各司其职，**不必保持同步**
     * （后来者勿误同步或漏改）。
     */
    const val GHOST_RANGE = 2000f

    /**
     * 边缘羽化余量倍率：渲染 quad 须比波最大半径略大，给波前外侧 smoothstep 羽化留空间，
     * 避免波被 quad 边界硬切。
     */
    private const val FEATHER_MARGIN_MULT = 1.06f

    /**
     * 归一域半径（FRAGMENT centeredAspect 缩放）：v_uv∈[0,1] 映射到 [-domainRadius, domainRadius]。
     *
     * 取 1.12（略小于范式 [EchoFixationFieldVisualEffect]/[ArcJetShockwaveRingEffect] 的 1.15）：
     * 波须铺满 quad 全域（波前会扩到 quad 边缘），1.12 让波前在到达归一域边缘时外侧仍留一圈余量供
     * 高斯环外缘羽化衰减，避免最大 progress 时波前被域边界硬切出锐边。
     */
    private const val SHADER_DOMAIN_RADIUS = 1.12f

    private const val EFFECT_ID = "astd_ghost_signal_wave"
    private const val PROGRAM_ID = "astd_ghost_signal_wave_program"

    private val SHADER_UNIFORMS = ShaderUniformSchema(
        listOf(
            ShaderUniformDefinition(
                key = "resolution",
                type = ShaderUniformType.Vec2,
                required = false,
                defaultValue = ShaderUniformValue.Vec2(1f, 1f),
            ),
            // 波扩张进度 0→1：CPU 驱动波前归一半径与 alpha 包络（不依赖 u_time，见类注释）。
            ShaderUniformDefinition("progress", ShaderUniformType.Float),
            ShaderUniformDefinition("hue", ShaderUniformType.Float),
            ShaderUniformDefinition("saturation", ShaderUniformType.Float),
            ShaderUniformDefinition("accentHue", ShaderUniformType.Float),
            ShaderUniformDefinition("accentSaturation", ShaderUniformType.Float),
            ShaderUniformDefinition("alphaMult", ShaderUniformType.Float),
            // 归一域半径：v_uv∈[0,1] → 中心化坐标的半径范围（波落在 domain 内）。
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
        geometry = ShaderGeometrySpec.WorldQuad(quadHalfExtentFor(GHOST_RANGE)),
        material = ShaderMaterialSpec(ShaderBlendMode.Additive),
        uniformSchema = SHADER_UNIFORMS,
        // AboveParticles：电子战脉冲是场景上层的氛围波，置于粒子之上更醒目，又不像 AboveShips
        // 那样压在舰体精灵上喧宾夺主（导弹剥离的反馈应铺在战场背景层上方而非盖住单位）。
        layer = ShaderEffectLayer.AboveParticles,
        lifetimeSeconds = 1f,
        staleAfterSeconds = STALE_AFTER_SECONDS,
        renderRadius = quadHalfExtentFor(GHOST_RANGE),
    )

    /**
     * 单帧波渲染参数（纯几何/颜色，便于单测）。
     *
     * @property rangeWorld 波最大扩张半径（世界单位，= 幽灵信号作用半径）。
     * @property waveRadiusWorld 当前波前世界半径（= rangeWorld × progress，供测试/调用方参考）。
     * @property quadHalfExtentWorld 渲染 quad 半边长（覆盖最大波 + 羽化，全程固定）。
     * @property shaderDomainRadius 归一域半径（FRAGMENT centeredAspect 缩放）。
     * @property progress 波扩张进度 0→1。
     * @property hue/saturation 主色（紫罗兰）。
     * @property accentHue/accentSaturation 辅色（淡紫高光）。
     * @property alphaMult 整体不透明度（淡入—淡出包络，随 progress）。
     */
    data class Frame(
        val rangeWorld: Float,
        val waveRadiusWorld: Float,
        val quadHalfExtentWorld: Float,
        val shaderDomainRadius: Float,
        val progress: Float,
        val hue: Float,
        val saturation: Float,
        val accentHue: Float,
        val accentSaturation: Float,
        val alphaMult: Float,
    )

    /** 含羽化余量的 quad 半边长 / renderRadius（按波最大半径算，全程固定）。 */
    fun quadHalfExtentFor(range: Float): Float = range * FEATHER_MARGIN_MULT

    /**
     * 计算单帧波参数（纯函数）。
     *
     * progress 单调驱动波前半径（线性外扩）与 alpha 包络。alpha 包络为「快淡入—缓淡出」：
     * 起手 0→peak 在 progress≈0.15 处达峰，随后线性衰减至 progress=1 归零，呈现脉冲扩散后消散。
     *
     * @param range 波最大扩张半径（世界单位，调用方传幽灵信号作用半径）。
     * @param progress 波扩张进度 0→1（= elapsed / [WAVE_DURATION]）。
     */
    fun frame(range: Float, progress: Float): Frame {
        val p = progress.coerceIn(0f, 1f)
        val quad = quadHalfExtentFor(range)
        // 淡入段（0→0.15 线性升至 1）与淡出段（0.15→1 线性降至 0）的乘性包络。
        val fadeIn = (p / 0.15f).coerceIn(0f, 1f)
        val fadeOut = (1f - (p - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val alphaMult = 0.85f * fadeIn * fadeOut
        return Frame(
            rangeWorld = range,
            waveRadiusWorld = range * p,
            quadHalfExtentWorld = quad,
            shaderDomainRadius = SHADER_DOMAIN_RADIUS,
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
     * 提交/更新一道幽灵信号波（keyed upsert）。
     *
     * @param instanceId 波实例稳定标识（调用方拼 "ghost-wave-{shipId}-{seq}"），per-wave。
     *                   每道波一个唯一 seq，避免同舰相邻波互相覆盖。
     * @param center 波心（本舰世界坐标）。
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
                renderRadius = frame.quadHalfExtentWorld,
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

        vec3 hsv2rgb(vec3 c) {
          vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
          return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
        }

        void main() {
          vec2 p = centeredAspect(v_uv);
          float r = length(p);
          float ang = atan(p.y, p.x);

          // 波前归一半径：quad 半边长 = range × 羽化倍率，故波在归一域中的最大半径 = 1 / 羽化倍率。
          // 此 1.06 必须与 Kotlin 侧 FEATHER_MARGIN_MULT 同步（GLSL 无法引用 Kotlin const，
          // 改一处务必改另一处）。progress 线性推进波前由 0 扩到该最大半径。
          float maxRadius = 1.0 / 1.06;
          float waveRadius = u_progress * maxRadius;

          // 主波前环：以 waveRadius 为中心的高斯环，progress 越大环越宽越柔（扩散稀释感）。
          float thickness = mix(0.035, 0.11, u_progress);
          float dr = r - waveRadius;
          float wave = exp(-(dr * dr) / max(thickness * thickness, 1e-5));

          // 仅取波前外缘一侧略亮（电子战脉冲推进的「前沿」更锐），内侧拖尾稍弱。
          float frontBias = 0.65 + 0.35 * smoothstep(thickness, -thickness, dr);
          wave *= frontBias;

          // 干扰扫描线：沿角度的细密刻度 + 随时间旋转（u_time 仅作细节抖动，非扩张主驱动）。
          float scan = 0.78 + 0.22 * sin(ang * 64.0 - u_time * 6.0);
          // 径向噪声调制，给波前加电子战「噪声」颗粒。
          float grain = 0.80 + 0.20 * valueNoise(vec2(ang * 6.0, r * 14.0 - u_time * 2.0));
          wave *= scan * grain;

          // 内侧淡紫辅色拖尾：波前内侧一道更柔的辅色环，低饱和。
          float trailRadius = waveRadius * 0.86;
          float trailDr = r - trailRadius;
          float trail = exp(-(trailDr * trailDr) / 0.010) * 0.40;

          // 波心向外的极淡余辉填充（progress 越大越散，弱）。
          float core = exp(-r * r * 6.0) * (1.0 - u_progress) * 0.18;

          vec3 primary = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
          vec3 accent = hsv2rgb(vec3(u_accentHue, u_accentSaturation, 1.0));

          vec3 color = primary * (wave + core) + accent * trail;
          float alpha = clamp(wave + trail + core, 0.0, 1.0);
          gl_FragColor = vec4(color, alpha * u_alphaMult);
        }
    """
}
