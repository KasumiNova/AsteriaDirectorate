package cn.kasuminova.astd.renderer.effect.lens

/**
 * 误差/深水标记高光的共享 GLSL 源（Task 8）。
 *
 * 动机（DRY 决策）：DriftMarkVisualEffect 与 DeepWaterMarkVisualEffect 的 fragment 形态
 * 完全一致——同一套「环形 SDF + Fresnel 边缘增强 + 层数驱动环宽/亮度」的高光环，两者唯一
 * 差异是默认 hue/saturation（紫罗兰 vs 红），而这两个值经 uniform 传入，**不进入 GLSL 文本**。
 * 因此 fragment/vertex 源字符串值得抽出为单一常量，两 effect 各自构造独立的 ShaderProgramSpec
 * （program id 必须唯一，否则 runtime 程序缓存会串），但引用同一份源字符串，避免重复维护两段
 * 几乎逐字符相同的 GLSL（一处改、两处同步的隐患）。
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
     * **必须与下方 FRAGMENT_SHADER_SOURCE 内 `ringRadius = 1.0 / 1.12` 同步**——GLSL 无法引用
     * Kotlin const，改此倍率时务必同步改 GLSL 字面量。Drift/DeepWater 两 effect 的 Kotlin 侧已由
     * 本常量统一不再漂移，仅 GLSL 一处仍需手动对齐。
     */
    const val FEATHER_MARGIN_MULT: Float = 1.12f

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
     * 共享片元着色器：绕敌舰边缘的发光高光环。
     *
     * 形态：
     * - 环半径 ringRadius 落在敌舰碰撞半径处（归一域中 = 1 / 羽化倍率，与 Kotlin 侧
     *   FEATHER_MARGIN_MULT 同步——GLSL 无法引用 Kotlin const，改一处务必改另一处，此处 1.12）。
     * - 环本体：圆形 SDF `abs(r - ringRadius)` 经 smoothstep 双侧羽化。
     * - Fresnel 边缘增强：朝外法向（径向）的边缘越靠环越亮，营造「贴敌舰边缘的高光」感。
     * - 层数驱动：u_markLevel 越高 → 环越宽、越亮、并叠加一道更内侧的细辅环，强度随层数线性升。
     * - 轻微角向调制 + u_time 缓动，避免环过于死板（电战/误差「持续扫描」的活感）。
     *
     * 颜色：hsv2rgb(vec3(u_hue, u_saturation, 1.0))，hue/sat 由 effect 各自默认传入（紫/红）。
     */
    const val FRAGMENT_SHADER_SOURCE: String = """
        uniform float u_time;
        uniform vec2 u_resolution;
        uniform float u_markLevel;
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

          // 高光环半径：固定落在敌舰碰撞半径处。quad 半边长 = collisionRadius × 羽化倍率，
          // 故环在归一域中的半径 = 1 / 羽化倍率(1.12)。必须与 Kotlin FEATHER_MARGIN_MULT 同步。
          float ringRadius = 1.0 / 1.12;

          // 层数驱动的环宽：层数越高环越宽（0.045 → 0.105）。
          float width = mix(0.045, 0.105, u_markLevel);

          // 圆形 SDF → 环 alpha，smoothstep 双侧羽化。
          float ringSdf = abs(r - ringRadius);
          float ring = 1.0 - smoothstep(0.0, width, ringSdf);

          // Fresnel 边缘增强：越靠近环中心线（SDF=0）能量越高，且整体随层数提亮。
          float fresnel = pow(1.0 - clamp(ringSdf / max(width, 1e-3), 0.0, 1.0), 1.5);
          ring *= fresnel;

          // 角向缓动调制（持续扫描的活感，振幅小以免破坏环的连续性）。
          float sweep = 0.88 + 0.12 * sin(ang * 6.0 + u_time * 1.2);
          ring *= sweep;

          // 内侧细辅环：层数越高越明显，给高层标记额外一圈强调。
          float innerRadius = ringRadius * 0.86;
          float innerSdf = abs(r - innerRadius);
          float inner = (1.0 - smoothstep(0.0, 0.03, innerSdf)) * (0.20 + 0.45 * u_markLevel);

          // 整体能量随层数线性增益（高层更亮更醒目）。
          float levelGain = 0.55 + 0.85 * u_markLevel;
          float energy = ring * levelGain + inner;

          vec3 tint = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
          vec3 color = tint * energy;

          float alpha = clamp(energy, 0.0, 1.0);
          gl_FragColor = vec4(color, alpha * u_alphaMult);
        }
    """
}
