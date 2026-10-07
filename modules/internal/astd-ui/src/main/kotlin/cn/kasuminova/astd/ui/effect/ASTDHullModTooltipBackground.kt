package cn.kasuminova.astd.ui.effect

import cn.kasuminova.astd.impl.ui.HullmodBackgroundStyleConfig
import cn.kasuminova.astd.ui.render.ASTDStencilRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.ui.PositionAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.awt.Color

/**
 * ASTD 船插 Tooltip 统一全息背景。
 *
 * 视觉规格（原型竞标终版，见 temp/fx-bakeoff/refine/）：
 * - 主风格 [HullmodBackgroundStyleConfig.STYLE_CORNER_PULSE]（k3-01 角标脉冲）：四角 L 形角标呼吸
 *   （嵌套三角描边/顶点光/能量核装饰按需求暂时移除），底层三角晶格 + 竖直扫描带 +
 *   漂浮微三角粒子铺满全面板，CRT 细扫描线与暗角收边，整体为暗色全息面板质感；
 * - 备选风格 [HullmodBackgroundStyleConfig.STYLE_PRISM_LATTICE]（deepseek-02 棱镜栅格）：紧贴边缘的面板
 *   结构边框 + 棱镜刻度 + 边框爬行光点，四角楔形线框 + 错落光锥，角部全息晶格，
 *   发光元素严格收敛于四角与边缘、中央文字区近黑静默；
 * - 颜色单点收口：全部发光元素跟随船插主题色（[accentColor] 随 Theme 传入）。
 *
 * GPU 负载控制（原型阶段实测教训：setInterval 叠加 requestAnimationFrame 造成
 * rAF 回调无限注册泄漏，每分钟每帧多渲染上千次，可将 GPU 打满）：
 * - Tooltip 面板渲染面积小（通常 < 600x400），时间由 [advance] 驱动、随游戏主循环推进，
 *   无任何自启动定时器；
 * - corner-pulse 角部装饰限制在角点 95 归一单位半径内（continue 早退，归一系定义见
 *   [FRAGMENT_CORNER_PULSE] 注释），prism-lattice 的 lattice/角部结构按权重早退，中央区域零循环开销。
 *
 * 渲染通道评估：BoxUtil 的渲染实体（SpriteEntity/TrailEntity 等）面向战斗/星图世界层，
 * 不提供 UI 面板内绘制工具；Tooltip 背景必须落在 UI stencil 裁剪区内嵌 GL 绘制，
 * 故沿用 GLSL 全屏四边形路径（本仓库唯一可用通道，与原版字体渲染同一固定管线）。
 *
 * 排版对齐（0.98 原版源码核验，详见 [renderBelow]）：addCustom 组件位于 tooltip
 * 内容流（x = 内容面板 + 5），addPostDescriptionSection 传入的 width 为内容流宽，
 * 直接按该几何绘制会比船插背景左右各窄约 12px（refit，总宽 = 流宽 + 24）或
 * 15/5px（图鉴，外层 box = 流宽 + 20）。渲染时读取 tooltip 自身位置（实现类即
 * UIComponent）按宿主类型换算 chrome 区域，使背景铺满船插背景的内侧区域。
 *
 * 编辑器镜像预设：`tools/tooltip-style-editor/src/model/defaultHullmodPreset.ts`
 * （shader id `corner-pulse` / `prism-lattice`，u_origin 在编辑器内以 (0,0) 全画布代入）。
 */
class ASTDHullModTooltipBackground private constructor(
    private val tooltip: TooltipMakerAPI,
    private val flowWidth: Float,
    private val accentColor: Color,
) : BaseCustomUIPanelPlugin() {

    var contentHeight: Float = 0f

    private var pos: PositionAPI? = null
    private var timeAcc: Float = 0f

    override fun positionChanged(position: PositionAPI) {
        pos = position
    }

    override fun advance(amount: Float) {
        if (amount > 0f) timeAcc += amount
    }

    override fun renderBelow(alphaMult: Float) {
        val h = contentHeight
        if (h <= 0f) return
        val p = pos ?: return

        // 原版排版机制（0.98 源码核验）：addCustom 的组件落在内容流上
        // （x = 内容面板 + 5），而传入的 width 是内容流宽，直接按 (p.x, flowWidth)
        // 绘制会比船插背景左右各窄一截。两种宿主的几何：
        // - refit 等带边框 tooltip（StandardTooltipV2Expandable）：总宽 = 内容流宽 + 24
        //   （内容面板 +10、左右各 7px 边框垫区），背景铺满内容面板区域、避开边框纹理；
        // - 图鉴（CodexDetailPanel）：mainText 无边框，宽度即内容流宽，外层 box 按
        //   wrapTooltipWithBox 常量外扩 10px 并带 1px 深色描边，背景铺满 box 描边内侧。
        // 两种宿主以「chrome 宽 - 内容流宽」是否大于 20 区分（24 vs 0）。
        val chrome = (tooltip as UIComponentAPI).position
        val quadX: Float
        val quadWidth: Float
        if (chrome.width - flowWidth > 20f) {
            quadX = chrome.x + 7f
            quadWidth = chrome.width - 14f
        } else {
            quadX = chrome.x - 9f
            quadWidth = chrome.width + 18f
        }
        val y = p.y - h

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
        GL11.glPushMatrix()
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

        ASTDStencilRenderer.withStencilMask(quadX, y, quadWidth, h) {
            renderShaderQuad(quadX, y, quadWidth, h, alphaMult)
        }

        GL11.glPopMatrix()
        GL11.glPopAttrib()
    }

    private fun renderShaderQuad(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        val program = shaderProgram()
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val r = accentColor.red / 255f
        val g = accentColor.green / 255f
        val b = accentColor.blue / 255f
        val a = accentColor.alpha / 255f * alphaMult

        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL20.glUseProgram(program)
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_time"), timeAcc)
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_origin"), x, y)
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_resolution"), w, h)
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "u_accentColor"), r, g, b, a)
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_alphaMult"), alphaMult)

        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()

        GL20.glUseProgram(previousProgram)
    }

    companion object {
        private const val SHADER_ID = "astd-hullmod-bg"

        /** 各风格的 program 缓存（按需编译，0 值表示未编译）。 */
        private val shaderProgramIds = IntArray(HullmodBackgroundStyleConfig.STYLE_COUNT)

        /**
         * 顶点着色器：以 varying 传递顶点的 CPU 侧逻辑坐标。
         *
         * 注意：禁止使用 gl_FragCoord 换算面板局部坐标——它是物理 framebuffer 像素，
         * 与 SS UI 逻辑坐标（u_origin）在显示缩放/高分屏下存在比例差，会导致
         * 角部早退全部命中、背景完全消失。gl_Vertex 与 u_origin 同属逻辑坐标空间，
         * 差值插值后与物理像素解耦，任何缩放下自洽。
         */
        private const val VERTEX_SHADER_SOURCE = """
            varying vec2 v_pos;
            void main() {
              v_pos = gl_Vertex.xy;
              gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
            }
        """

        /**
         * k3-01 角标脉冲 fragment shader（GLSL 110 兼容）。
         *
         * 坐标系：面板局部逻辑坐标（v_pos - u_origin）除以 uiScale 后的归一空间，y 向上；
         * 面板宽度归一：以编辑器 580px 为基准（uiScale = clamp(u_resolution.x / 580, 0.6, 1.0)），
         * frag/res/uv 及全部像素尺寸元素（角标/晶格/粒子/扫描带/角部早退半径）均定义在归一空间，
         * 窄面板上等比缩小、占比与基准一致；编辑器镜像以 gl_FragCoord / uiScale 等价代入；
         * 与原型差异：uRes→u_resolution、ACCENT→u_accentColor.rgb、
         * 输出 alpha 0.92*u_alphaMult（与旧版面板一致）、角部循环 95 归一单位半径早退。
         */
        private const val FRAGMENT_CORNER_PULSE = """
            uniform float u_time;
            uniform vec2 u_origin;
            uniform vec2 u_resolution;
            uniform vec4 u_accentColor;
            uniform float u_alphaMult;

            varying vec2 v_pos;

            const vec3 BG = vec3(0.015, 0.025, 0.0425);
            const float PI = 3.14159265359;

            float hash12(vec2 p) {
              vec3 p3 = fract(vec3(p.xyx) * 0.1031);
              p3 += dot(p3, p3.yzx + 33.33);
              return fract((p3.x + p3.y) * p3.z);
            }

            // equilateral triangle SDF (apex toward +y, r ~ circumradius)
            float sdTri(vec2 p, float r) {
              const float k = 1.7320508;
              p.x = abs(p.x) - r;
              p.y = p.y + r / k;
              if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
              p.x -= clamp(p.x, -2.0 * r, 0.0);
              return -length(p) * sign(p.y);
            }

            float sdSeg(vec2 p, vec2 a, vec2 b) {
              vec2 pa = p - a, ba = b - a;
              float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
              return length(pa - ba * h);
            }

            // triangular lattice lines (three sets of 60-degree parallels)
            float triGrid(vec2 p, float s) {
              float d = 1e5;
              for (int i = 0; i < 3; i++) {
                float a = float(i) * PI / 3.0;
                vec2 n = vec2(cos(a), sin(a));
                float v = abs(fract(dot(p, n) / s + 0.5) - 0.5) * s;
                d = min(d, v);
              }
              return d;
            }

            void main() {
              // normalize to the 580px editor baseline: pixel-sized elements (corner
              // ornaments, lattice, particles) keep the same panel proportion on
              // narrower tooltips instead of dominating it
              float uiScale = clamp(u_resolution.x / 580.0, 0.6, 1.0);
              vec2 frag = (v_pos - u_origin) / uiScale;
              vec2 res = u_resolution / uiScale;
              vec2 uv = frag / res;
              vec3 accent = u_accentColor.rgb;
              vec3 col = BG;

              col += vec3(0.005, 0.010, 0.01875) * (1.0 - uv.y);

              // ---- base triangular lattice ----
              float g = triGrid(frag, 56.0);
              float gridLine = 1.0 - smoothstep(0.0, 1.2, g);
              col += accent * gridLine * 0.1;

              // ---- vertical sweep band ----
              float sweepY = mod(u_time * 81.0, res.y + 240.0) - 120.0;
              float band = exp(-pow((frag.y - sweepY) / 46.0, 2.0));
              col += accent * gridLine * band * 0.11;
              col += accent * band * 0.012;

              // ---- drifting micro-triangle particles ----
              {
                float cell = 90.0;
                vec2 gp = frag + vec2(0.0, u_time * 7.0);
                vec2 id = floor(gp / cell);
                vec2 lv = fract(gp / cell) * cell;
                float rnd = hash12(id);
                vec2 ctr = vec2(hash12(id + 7.1), hash12(id + 3.7)) * cell;
                float sz = 3.0 + 5.0 * hash12(id + 11.3);
                float tw = 0.5 + 0.5 * sin(u_time * (0.8 + rnd * 1.6) + rnd * 6.2832);
                float d = sdTri(lv - ctr, sz);
                float a = (1.0 - smoothstep(0.0, 1.0, abs(d))) * 0.2 * tw * step(0.55, rnd);
                col += accent * a;
              }

              // ---- corner ornaments: L-shaped brackets ----
              // (nested triangle strokes / apex light / energy core temporarily removed per spec)
              for (int i = 0; i < 4; i++) {
                vec2 corner;
                if (i == 0) { corner = vec2(10.0, 10.0); }
                else if (i == 1) { corner = vec2(res.x - 10.0, 10.0); }
                else if (i == 2) { corner = vec2(res.x - 10.0, res.y - 10.0); }
                else { corner = vec2(10.0, res.y - 10.0); }

                // early-out: bracket arm reach 50.4 units + glow ~20, in the normalized
                // 580px-baseline space; residual beyond 95 <= 0.001% accent, invisible
                if (distance(frag, corner) > 95.0) continue;

                float phase = float(i) * 1.7;
                float breathe  = 0.62 + 0.38 * sin(u_time * 1.5 + phase);

                // L-shaped bracket
                vec2 ex = (corner.x < res.x * 0.5) ? vec2(1.0, 0.0) : vec2(-1.0, 0.0);
                vec2 ey = (corner.y < res.y * 0.5) ? vec2(0.0, 1.0) : vec2(0.0, -1.0);
                float bl = min(sdSeg(frag, corner, corner + ex * 50.4),
                               sdSeg(frag, corner, corner + ey * 50.4));
                col += accent * (1.0 - smoothstep(0.5, 1.5, bl)) * 0.75 * breathe;
                col += accent * exp(-bl * 0.25) * 0.08 * breathe;
                vec2 corner2 = corner - (ex + ey) * 4.2;
                float bl2 = min(sdSeg(frag, corner2, corner2 + ex * 29.4),
                                sdSeg(frag, corner2, corner2 + ey * 29.4));
                col += accent * (1.0 - smoothstep(0.5, 1.5, bl2)) * 0.38;
              }

              // ---- thin border ----
              float bd = min(min(frag.x, res.x - frag.x), min(frag.y, res.y - frag.y));
              col += accent * (1.0 - smoothstep(0.0, 1.0, bd)) * 0.10;

              // ---- CRT scanlines (period locked to logical px, avoids sub-pixel moire) ----
              col *= 0.96 + 0.04 * sin(frag.y * uiScale * PI);

              // ---- vignette ----
              vec2 q = uv - 0.5;
              col *= 1.0 - 0.32 * dot(q, q);

              // ---- dither against banding ----
              col += (hash12(frag + fract(u_time) * 13.7) - 0.5) * 0.008;

              // soft highlight rolloff, no hard clipping
              col = col / (1.0 + col * 0.12);

              gl_FragColor = vec4(col, 0.92 * u_alphaMult);
            }
        """

        /**
         * deepseek-02 棱镜栅格 fragment shader（GLSL 110 兼容，备选风格）。
         *
         * 坐标系：面板局部逻辑像素（v_pos - u_origin）换算为以面板中心为原点、
         * 短边归一（p = (2px - res) / res.y），与原型一致；
         * 与原型差异：u_res→u_resolution、u_acc→u_accentColor.rgb、移除未使用的 u_deep/PI/TAU、
         * 输出 alpha 0.92*u_alphaMult；扩散三角环与旋转三角主轴已按需求移除，
         * lattice 与角部结构按权重早退；PANEL 由 (0.955, 0.915) 改为 (1.0, 1.0)，
         * 描边/刻度/角部结构紧贴面板边缘（外侧衰减由 UI stencil 裁剪）；
         * 角根核心由原型的独立 col+= 并入 acc3（改按 cmask*clip 缩放：核心 e 折半径
         * ~3.9px，贴边后角点外侧部分随 stencil 一并裁剪，视觉可忽略）。
         */
        private const val FRAGMENT_PRISM_LATTICE = """
            uniform float u_time;
            uniform vec2 u_origin;
            uniform vec2 u_resolution;
            uniform vec4 u_accentColor;
            uniform float u_alphaMult;

            varying vec2 v_pos;

            // panel half-extents in p units: border flush with the panel edge,
            // outer stroke falloff clipped by the UI stencil
            const vec2  PANEL = vec2(1.0, 1.0);

            float hash21(vec2 p) {
              p = fract(p * vec2(123.34, 456.21));
              p += dot(p, p + 45.32);
              return fract(p.x * p.y);
            }
            float hash11(float x) { return fract(sin(x * 127.1) * 43758.5453); }

            float triSDF(vec2 p, float r) {
              const float k = 1.7320508;
              p.x = abs(p.x) - r;
              p.y = p.y + r / k;
              if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
              p.x -= clamp(p.x, -2.0 * r, 0.0);
              return -length(p) * sign(p.y);
            }

            float segDist(vec2 p, vec2 a, vec2 b, out float t) {
              vec2 pa = p - a, ba = b - a;
              t = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
              return length(pa - ba * t);
            }

            void rectOutline(vec2 p, vec2 h, out float d, out float arc) {
              vec2 a0 = vec2(-h.x, -h.y), a1 = vec2(h.x, -h.y);
              vec2 b0 = vec2( h.x, -h.y), b1 = vec2(h.x,  h.y);
              vec2 c0 = vec2( h.x,  h.y), c1 = vec2(-h.x, h.y);
              vec2 e0 = vec2(-h.x,  h.y), e1 = vec2(-h.x, -h.y);
              float t0, t1, t2, t3;
              float d0 = segDist(p, a0, a1, t0);
              float d1 = segDist(p, b0, b1, t1);
              float d2 = segDist(p, c0, c1, t2);
              float d3 = segDist(p, e0, e1, t3);
              float per = 2.0 * h.x, we = 2.0 * h.y;
              d = d0; arc = t0 * per;
              if (d1 < d) { d = d1; arc = per + t1 * we; }
              if (d2 < d) { d = d2; arc = per + we + t2 * per; }
              if (d3 < d) { d = d3; arc = 2.0 * per + we + t3 * we; }
            }

            // three-way line raster forming triangular lattice
            float lattice(vec2 p, float t) {
              float s = 0.0;
              for (int i = 0; i < 3; i++) {
                float a = float(i) * 2.0943951 + 0.15 * sin(t * 0.21);
                vec2  d = vec2(cos(a), sin(a));
                float ph = dot(p, d) * 26.0 + t * (0.35 + 0.12 * float(i));
                s += exp(-abs(sin(ph)) * 14.0);
              }
              return s;
            }

            void main() {
              vec2 px = v_pos - u_origin;
              float aspect = u_resolution.x / u_resolution.y;
              vec2 p = (2.0 * px - u_resolution) / u_resolution.y;
              float R = length(p);
              float t = u_time;
              vec3 acc = u_accentColor.rgb;

              vec2 hp = vec2(PANEL.x * aspect, PANEL.y);
              vec2 a  = abs(p);
              vec2 cu = vec2(hp.x - a.x, hp.y - a.y);       // corner-local coords (positive inward)
              float cornerId = step(0.0, p.x) + step(0.0, p.y) * 2.0; // per-corner index 0..3

              vec3 col = vec3(0.0);

              // ---- holo lattice: weight-gated early-out, center fully dark ----
              float cw = exp(-mix(length(cu), max(cu.x, cu.y), 0.68) * 6.0);
              if (cw > 0.004) {
                vec2 dir = normalize(p + 1e-5);
                float off = 0.0030;
                vec3 lat = vec3(
                  lattice(p + dir * off * 2.0, t),
                  lattice(p, t),
                  lattice(p - dir * off * 2.0, t)
                );
                float latMono = (lat.r + lat.g + lat.b) / 3.0;
                float latPow = smoothstep(0.35, 0.95, latMono);
                float latBase = 0.085 * cw;
                col += acc * latPow * latBase * (0.80 + 0.40 * lat);

                // sparse nodes: faint flicker near corners only
                vec2 gid = floor(p * 22.0);
                float cell = hash21(gid);
                vec2 gc = (gid + 0.5) / 22.0;
                float node = 1.0 - smoothstep(0.0, 0.008, length(p - gc));
                col += acc * node * step(0.97, cell) * cw *
                       (0.35 + 0.65 * max(0.0, sin(t * 1.6 + cell * 40.0))) * 0.30;
              }

              // ---- panel border: crisp tactical structure line ----
              float bd, barc;
              rectOutline(p, hp, bd, barc);
              float perim = 4.0 * (hp.x + hp.y);
              float uPer  = barc / perim;
              col += acc * (1.0 - smoothstep(0.0, 0.0028, bd)) * 0.32;
              col += acc * (1.0 - smoothstep(0.0, 0.010, bd)) * 0.045;

              // tick marks inside border (prism scale)
              float marks = smoothstep(0.60, 0.95, hash11(floor(uPer * 150.0) + 3.0));
              float markMask = (1.0 - smoothstep(0.004, 0.012, bd)) * (1.0 - smoothstep(0.035, 0.040, bd));
              col += acc * marks * markMask * (0.16 + 0.10 * sin(t * 1.4 + uPer * 40.0));

              // refractive glints crawling along border
              float pos = fract(t / 13.0);
              float d1 = abs(fract(uPer - pos + 0.5) - 0.5);
              float d2 = abs(fract(uPer - fract(pos + 0.5) + 0.5) - 0.5);
              float edge = 1.0 - smoothstep(0.0, 0.008, bd);
              col += acc * exp(-d1 * 140.0) * edge * 0.45;
              col += acc * exp(-d2 * 110.0) * edge * 0.25;

              // ---- corners: prism structure (expanding rings / rotating axes removed per spec) ----
              float C = 0.56;
              float cmask = 1.0 - smoothstep(C * 0.70, C * 1.20, length(cu));
              float clip  = smoothstep(-0.004, 0.0015, cu.x) * smoothstep(-0.004, 0.0015, cu.y);
              float breathe = 0.65 + 0.35 * sin(t * 1.05);

              vec3 acc3 = vec3(0.0);

              if (cmask > 0.002) {
                // root solid wedge: weak fill, strong wireframe
                float wedge = triSDF(cu - vec2(0.085, 0.085), 0.108);
                float wedgeFill = 1.0 - smoothstep(0.0, 0.020, wedge);
                acc3 += acc * wedgeFill * 0.04 * breathe;
                acc3 += acc * (1.0 - smoothstep(0.0, 0.0035, abs(wedge))) * (0.45 + 0.35 * breathe);

                // outward light cones (fan beams): staggered random angle per corner
                for (int i = 0; i < 3; i++) {
                  float fi = float(i);
                  float angleShift = (hash11(cornerId * 13.71 + fi * 7.39) - 0.5) * 0.65;
                  float ang = 0.62 + fi * 0.72 + angleShift;
                  vec2  dv = vec2(cos(ang), sin(ang));
                  vec2  nv = vec2(-dv.y, dv.x);
                  float along  = dot(cu, dv);
                  float across = dot(cu, nv);
                  float ph = fract(along * 0.85 - t * (0.42 + 0.22 * fi));
                  float bandW = exp(-abs(across) * (200.0 - 20.0 * fi));
                  acc3 += acc * bandW * smoothstep(0.0, 0.18, ph) * (1.0 - smoothstep(0.52, 1.0, ph)) * 0.22;
                }

                // faint corner glimmer: clean wireframe, no diffuse buildup
                float tg = abs(triSDF(cu - vec2(0.115, 0.115), 0.135));
                acc3 += acc * (1.0 - smoothstep(0.0, 0.0028, tg)) * 0.22;

                // corner root micro core
                acc3 += acc * exp(-length(cu) * 42.0) * 0.40 * (0.7 + 0.3 * sin(t * 2.3));
              }

              col += acc3 * cmask * clip;

              // ---- inscribed guide lines ----
              vec2 c0 = hp - vec2(0.060, 0.060);
              float d00 = min(min(min(abs(p.x - c0.x), abs(p.x + c0.x)),
                                  abs(p.y - c0.y)), abs(p.y + c0.y));
              float line00 = 1.0 - smoothstep(0.0, 0.0022, d00);
              col += acc * (line00 * 0.16 + exp(-d00 * 120.0) * 0.04) * (0.5 + 0.5 * sin(t * 1.55));

              // ---- atmosphere & tonemap ----
              col *= 0.98 + 0.02 * hash21(px * 0.7 + fract(t) * 53.1);

              // deepen center darkness, keep center near-black
              float centerDark = smoothstep(0.20, 0.85, R);
              col *= mix(0.85, 1.0, centerDark);

              col = max(col, vec3(0.0));
              col = 1.0 - exp(-col * 1.15);
              col = pow(col, vec3(0.55));

              gl_FragColor = vec4(col, 0.92 * u_alphaMult);
            }
        """

        private fun shaderProgram(): Int {
            val style = HullmodBackgroundStyleConfig.activeStyle
            val cached = shaderProgramIds[style]
            if (cached != 0) return cached

            val fragmentSource = when (style) {
                HullmodBackgroundStyleConfig.STYLE_CORNER_PULSE -> FRAGMENT_CORNER_PULSE
                HullmodBackgroundStyleConfig.STYLE_PRISM_LATTICE -> FRAGMENT_PRISM_LATTICE
                else -> throw IllegalStateException("Unknown hullmod background style: $style")
            }
            // 编译失败时统一清理已创建的 shader 对象后重抛，避免按帧泄漏 GL 对象
            var vertexShader = 0
            var fragmentShader = 0
            try {
                vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE, style)
                fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource, style)
            } catch (e: Exception) {
                if (vertexShader != 0) GL20.glDeleteShader(vertexShader)
                if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader)
                throw e
            }
            val program = GL20.glCreateProgram()
            GL20.glAttachShader(program, vertexShader)
            GL20.glAttachShader(program, fragmentShader)
            GL20.glLinkProgram(program)
            GL20.glDeleteShader(vertexShader)
            GL20.glDeleteShader(fragmentShader)

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                val message = GL20.glGetProgramInfoLog(program, 4096)
                GL20.glDeleteProgram(program)
                throw IllegalStateException("Failed to link tooltip background shader $SHADER_ID style=$style: $message")
            }

            shaderProgramIds[style] = program
            return program
        }

        private fun compileShader(type: Int, source: String, style: Int): Int {
            val shader = GL20.glCreateShader(type)
            GL20.glShaderSource(shader, source)
            GL20.glCompileShader(shader)
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                val message = GL20.glGetShaderInfoLog(shader, 4096)
                GL20.glDeleteShader(shader)
                throw IllegalStateException("Failed to compile tooltip background shader $SHADER_ID style=$style: $message")
            }
            return shader
        }

        @JvmStatic
        fun create(
            tooltip: TooltipMakerAPI,
            width: Float,
            accentColor: Color,
        ): ASTDHullModTooltipBackground {
            val plugin = ASTDHullModTooltipBackground(tooltip, width, accentColor)
            val panel = Global.getSettings().createCustom(0f, 0f, plugin)
            tooltip.addCustom(panel, 0f)
            return plugin
        }
    }
}
