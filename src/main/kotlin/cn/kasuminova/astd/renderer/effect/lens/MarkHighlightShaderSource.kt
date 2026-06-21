package cn.kasuminova.astd.renderer.effect.lens

/**
 * 误差/深水标记高光的共享 GLSL 源（Task 8）。
 *
 * 动机（DRY 决策）：DriftMarkVisualEffect 与 DeepWaterMarkVisualEffect 的 fragment 形态
 * 完全一致——同一套「progress 驱动的高斯扩散波 + 轻微 domain-warp 扭曲 + 层数驱动强度」的
 * 高光波，两者唯一差异是默认 hue/saturation（紫罗兰 vs 红），而这两个值经 uniform 传入，
 * **不进入 GLSL 文本**。因此 fragment/vertex 源字符串值得抽出为单一常量，两 effect 各自构造
 * 独立的 ShaderProgramSpec（program id 必须唯一，否则 runtime 程序缓存会串），但引用同一份源
 * 字符串，避免重复维护两段几乎逐字符相同的 GLSL（一处改、两处同步的隐患）。
 *
 * **形态变更（Task 8 用户反馈）：** 旧实现是绕敌舰一圈的「静态高光环」（环形 SDF + Fresnel
 * 边缘增强 + 角向扫描）。用户要求改为「轻量、周期性向外扩散的波，带轻微扭曲，颜色不变」——
 * 即敌舰持续吐出一圈圈由内向外扩张的波（一波接一波循环）。故 fragment 重写为 progress 驱动的
 * 高斯扩散环（参照 [GhostSignalWaveEffect] 已验证的做法），叠一道轻微 domain-warp 让波前略有
 * 起伏（「扭曲」质感），并保留 u_markLevel 驱动整体强度（层数越高波越亮）。周期循环由 CPU 侧
 * （[cn.kasuminova.astd.combat.hullmods.lens.ASTDLensArrayCoreHullMod]）每帧推进 progress 并取模
 * 实现，shader 只画「当前 progress 这一帧的波」。
 *
 * 共用结构、独立 program id 的取舍：runtime 以 program id 为 key 编译/缓存 GL 程序；若两 effect
 * 复用同一 program id，会被视作同一程序——这对本场景是可接受的（源完全相同），但保留独立 id 更
 * 安全（语义上是两类不同特效，未来若 drift/deep-water 的 GLSL 出现分叉无需回头拆程序），且不增加
 * 任何运行成本（两份相同源各编译一次，开销可忽略）。故选独立 program id + 共享源字符串。
 */
internal object MarkHighlightShaderSource {

    /**
     * 提交后超过此秒数无更新即判定过期（核心 hullmod 每帧 upsert，远快于此）。
     * Drift/DeepWater 共享，避免逐字重复。
     */
    const val STALE_AFTER_SECONDS: Float = 0.18f

    /**
     * 碰撞半径下限（su）：spec 静态构造时给 renderRadius 一个稳定下限，避免小舰渲染包围盒过小。
     * 运行期 frame() 传入实际 collisionRadius。Drift/DeepWater 共享。
     */
    const val MIN_COLLISION_RADIUS: Float = 60f

    /**
     * 羽化余量倍率：渲染 quad 须比碰撞半径略大，给高光环外侧 smoothstep 羽化留空间，
     * 避免环被 quad 边界硬切。
     *
     * **必须与下方 FRAGMENT_SHADER_SOURCE 内 `maxRadius = 1.0 / 1.12` 同步**——GLSL 无法引用
     * Kotlin const，改此倍率时务必同步改 GLSL 字面量。Drift/DeepWater 两 effect 的 Kotlin 侧已由
     * 本常量统一不再漂移，仅 GLSL 一处仍需手动对齐。
     */
    const val FEATHER_MARGIN_MULT: Float = 1.12f

    /**
     * 归一域半径（FRAGMENT centeredAspect 缩放）：v_uv∈[0,1] 映射到 [-domainRadius, domainRadius]。
     * Drift/DeepWater 共享。波前归一半径最大为 1/FEATHER_MARGIN_MULT，1.15 留出外圈羽化余量。
     */
    const val SHADER_DOMAIN_RADIUS: Float = 1.15f

    /**
     * alpha 包络峰值所在 progress：波「快升—缓降」的拐点，0.15 让波起手即近峰、随后缓散。
     * Drift/DeepWater 共享。
     */
    const val ALPHA_PEAK_PROGRESS: Float = 0.15f

    /**
     * 波前世界半径（纯几何，便于单测/调用方参考）：归一域中波前半径 = progress/FEATHER_MARGIN_MULT，
     * 映射回世界 = 该归一半径 / SHADER_DOMAIN_RADIUS × quadHalfExtent。
     *
     * @param progress 波扩张进度 0~1（已 clamp）。
     * @param quadHalfExtent 渲染 quad 半边长（= clamp(collisionRadius) × FEATHER_MARGIN_MULT）。
     */
    fun waveRadiusWorld(progress: Float, quadHalfExtent: Float): Float =
        progress * (1f / FEATHER_MARGIN_MULT) / SHADER_DOMAIN_RADIUS * quadHalfExtent

    /**
     * progress 驱动的 alpha 包络（与 GLSL 波形配套的 CPU 侧整体强度）：在 [ALPHA_PEAK_PROGRESS]
     * 处达峰、起点与 progress=1 处归零，叠加 markLevel 线性增益（层数越高波越亮）。Drift/DeepWater 共享。
     *
     * 「轻量」取舍（用户反馈）：扩散波视觉上比静态环更醒目，故基础 alpha 较旧实现（0.30+0.70×level）
     * 略降为 0.22+0.55×level，避免刷屏。
     *
     * @param progress 波扩张进度 0~1（已 clamp）。
     * @param markLevel 标记层数归一 0~1（已 clamp）。
     */
    fun alphaEnvelope(progress: Float, markLevel: Float): Float {
        val fadeIn = (progress / ALPHA_PEAK_PROGRESS).coerceIn(0f, 1f)
        val fadeOut = (1f - (progress - ALPHA_PEAK_PROGRESS) / (1f - ALPHA_PEAK_PROGRESS)).coerceIn(0f, 1f)
        val base = 0.22f + 0.55f * markLevel
        return base * fadeIn * fadeOut
    }

    /**
     * 共享顶点着色器：与项目内其他 lens/system shader 一致——直接透传 uv 与 MVP 变换。
     */
    const val VERTEX_SHADER_SOURCE: String = """
        varying vec2 v_uv;

        void main() {
          v_uv = gl_MultiTexCoord0.xy;
          gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
        }
    """

    /**
     * 共享片元着色器：绕敌舰由内向外周期扩散的发光高光波（带轻微扭曲）。
     *
     * 形态（Task 8 重写——见类注释「形态变更」）：
     * - 波最大半径 maxRadius 落在敌舰碰撞半径处（归一域中 = 1 / 羽化倍率，与 Kotlin 侧
     *   FEATHER_MARGIN_MULT 同步——GLSL 无法引用 Kotlin const，改一处务必改另一处，此处 1.12）。
     * - 波前：以 u_progress × maxRadius 为中心的高斯环 `exp(-(dr*dr)/thickness^2)`，progress 越大
     *   波前外扩、环略增厚（与 GhostSignalWaveEffect 同构）。一波接一波由 CPU progress 取模循环驱动。
     * - 轻微扭曲（distortion）：对采样半径叠一道小幅 domain-warp（`r += distortAmp * sin(ang*K + u_time)`），
     *   让波前略有起伏褶皱，呈「误差/深水」的轻微干扰质感，振幅很小以保持轻量。
     * - 层数驱动：u_markLevel 越高 → 波越亮（levelGain 线性升），保留「层数越高越醒目」语义。
     *
     * 颜色：hsv2rgb(vec3(u_hue, u_saturation, 1.0))，hue/sat 由 effect 各自默认传入（紫/红），未变。
     */
    const val FRAGMENT_SHADER_SOURCE: String = """
        uniform float u_time;
        uniform vec2 u_resolution;
        uniform float u_markLevel;
        uniform float u_progress;
        uniform float u_hue;
        uniform float u_saturation;
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

          // Wavefront max normalized radius: quad half-extent = collisionRadius * feather mult, so the
          // wave max radius in normalized domain = 1 / feather mult (1.12). MUST stay in sync with the
          // Kotlin FEATHER_MARGIN_MULT (GLSL cannot reference a Kotlin const).
          // NOTE: GLSL comments must be ASCII only - Starsector's GL driver fails to lex multibyte
          // UTF-8 in comments ("unexpected end of file"). Do not use CJK here.
          float maxRadius = 1.0 / 1.12;

          // Mild domain-warp distortion: a small radial offset undulating around the ring and drifting
          // over time. Amplitude is deliberately tiny (~0.012) so the wave stays light, only lightly
          // rippled rather than visibly broken up. u_time drives the slow drift of the ripple pattern.
          float distortAmp = 0.012;
          float warp = distortAmp * sin(ang * 7.0 + u_time * 2.0)
                     + 0.5 * distortAmp * sin(ang * 13.0 - u_time * 1.3);
          float rd = r + warp;

          // Main spreading wavefront: a gaussian ring centered at progress*maxRadius, slightly thicker
          // and softer as it expands outward (mirrors GhostSignalWaveEffect's proven wave form).
          float waveRadius = u_progress * maxRadius;
          float thickness = mix(0.035, 0.10, u_progress);
          float dr = rd - waveRadius;
          float wave = exp(-(dr * dr) / max(thickness * thickness, 1e-5));

          // Slightly brighter leading edge (the outward front reads sharper than the inner trail).
          float frontBias = 0.70 + 0.30 * smoothstep(thickness, -thickness, dr);
          wave *= frontBias;

          // Overall energy gains linearly with mark level (high stacks brighter / more salient).
          float levelGain = 0.55 + 0.85 * u_markLevel;
          float energy = wave * levelGain;

          vec3 tint = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
          vec3 color = tint * energy;

          float alpha = clamp(energy, 0.0, 1.0);
          gl_FragColor = vec4(color, alpha * u_alphaMult);
        }
    """
}
