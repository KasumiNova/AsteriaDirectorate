package cn.kasuminova.astd.ui.effect

import cn.kasuminova.astd.impl.render.ShaderClip
import cn.kasuminova.astd.impl.render.ShaderCompatibility
import cn.kasuminova.astd.impl.render.ShaderGlState
import cn.kasuminova.astd.impl.render.ShaderQuadRenderer
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
 * 风格由 [HullmodBackgroundStyleConfig] 切换：STYLE_CORNER_PULSE 为四角 L 形角标呼吸 +
 * 底层三角晶格 + 竖直扫描带 + 漂浮微三角粒子，CRT 细扫描线与暗角收边；
 * STYLE_PRISM_LATTICE 为贴边结构边框 + 棱镜刻度 + 边框爬行光点 + 角部楔形线框与光锥，
 * 发光元素集中在四角与边缘，中央文字区近黑；
 * 全部发光元素跟随船插主题色（[accentColor] 随 Theme 传入）。
 *
 * GPU 负载控制：时间由 [advance] 驱动、随游戏主循环推进，禁止自启动定时器；
 * corner-pulse 角部装饰限制在角点 95 归一单位半径内早退（归一系定义见
 * [FRAGMENT_CORNER_PULSE] 注释），prism-lattice 的 lattice/角部结构按权重早退，
 * 中央区域零循环开销。
 *
 * 渲染通道：BoxUtil 渲染实体（SpriteEntity/TrailEntity 等）面向战斗/星图世界层，
 * 不提供 UI 面板内绘制工具；Tooltip 背景必须落在 UI stencil 裁剪区内嵌 GL 绘制，
 * 故使用 GLSL 全屏四边形路径。
 *
 * 排版对齐（详见 [renderBelow]）：addCustom 组件位于 tooltip 内容流（x = 内容面板 + 5），
 * addPostDescriptionSection 传入的 width 为内容流宽，直接按该几何绘制会比船插背景
 * 左右各窄一截。渲染时读取 tooltip 自身位置按宿主类型换算 chrome 区域，使背景铺满
 * 船插背景内侧；底部另按 [BOTTOM_EXTEND] 向下扩展，补偿内容流底部过小的内边距。
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

        // 原版排版机制：addCustom 的组件落在内容流上（x = 内容面板 + 5），
        // 而传入的 width 是内容流宽，直接按 (p.x, flowWidth) 绘制会比船插背景左右各窄一截。
        // 两种宿主的几何：
        // - refit 等带边框 tooltip（StandardTooltipV2Expandable）：总宽 = 内容流宽 + 24
        //   （内容面板 +10、左右各 7px 边框垫区），背景铺满内容面板区域、避开边框纹理；
        // - 图鉴（CodexDetailPanel）：mainText 无边框，宽度即内容流宽，外层 box 按
        //   wrapTooltipWithBox 常量外扩 10px 并带 1px 深色描边，背景铺满 box 描边内侧。
        // 两种宿主以 chrome 宽减内容流宽是否大于 20 区分（24 vs 0）。
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
        val y = p.y - h - BOTTOM_EXTEND
        val quadHeight = h + BOTTOM_EXTEND

        ShaderGlState.withSavedState {
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            ASTDStencilRenderer.withStencilMask(quadX, y, quadWidth, quadHeight) {
                renderShaderQuad(quadX, y, quadWidth, quadHeight, alphaMult)
            }
        }
    }

    private fun renderShaderQuad(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        val program = shaderProgram()
        val r = accentColor.red / 255f
        val g = accentColor.green / 255f
        val b = accentColor.blue / 255f
        val a = accentColor.alpha / 255f * alphaMult

        GL20.glUseProgram(program)
        ShaderCompatibility.uploadClip(
            program,
            ShaderClip.ui(Global.getSettings().screenWidth, Global.getSettings().screenHeight),
        )
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_time"), timeAcc)
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_origin"), x, y)
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_resolution"), w, h)
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "u_accentColor"), r, g, b, a)
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_alphaMult"), alphaMult)
        quadRenderer.draw(x, y, w, h)
    }

    companion object {
        private const val SHADER_ID = "astd-hullmod-bg"

        /**
         * 底部向下扩展量（逻辑像素）。内容流底部紧贴末行文本（末组件 pad 仅 2f），
         * 底部内边距明显小于左右两侧；且图鉴 box 下边距（13）大于左右（10+1）。
         * 取 6f：扩展后 tooltip 场景不触及下方的内置说明文本（净距 ~7px），
         * 图鉴场景不触及 box 下描边（净距 ~2px）。
         */
        private const val BOTTOM_EXTEND = 6f

        /** 各风格的 program 缓存（按需编译，0 值表示未编译）。 */
        private val shaderProgramIds = IntArray(HullmodBackgroundStyleConfig.STYLE_COUNT)

        /**
         * 角标脉冲 fragment shader（GLSL 110 / GLSL ES 100 共享语法）。
         *
         * 顶点由 [ShaderCompatibility] 统一提供，以 varying 传递 CPU 侧逻辑坐标。
         * 禁止使用 gl_FragCoord 换算面板局部坐标：它是物理 framebuffer 像素，
         * 与 SS UI 逻辑坐标（u_origin）在显示缩放/高分屏下存在比例差，会导致
         * 角部早退全部命中、背景完全消失。a_position 与 u_origin 同属逻辑坐标空间，
         * 差值插值后与物理像素解耦，任何缩放下自洽。
         *
         * 坐标系：面板局部逻辑坐标（v_pos - u_origin）除以 uiScale 后的归一空间，y 向上；
         * 面板宽度归一：以编辑器 580px 为基准（uiScale = clamp(u_resolution.x / 580, 0.6, 1.0)），
         * frag/res/uv 及全部像素尺寸元素（角标/晶格/粒子/扫描带/角部早退半径）均定义在归一空间，
         * 窄面板上等比缩小、占比与基准一致；编辑器镜像以 gl_FragCoord / uiScale 等价代入。
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

              // base triangular lattice
              float g = triGrid(frag, 56.0);
              float gridLine = 1.0 - smoothstep(0.0, 1.2, g);
              col += accent * gridLine * 0.1;

              // vertical sweep band
              float sweepY = mod(u_time * 81.0, res.y + 240.0) - 120.0;
              float band = exp(-pow((frag.y - sweepY) / 46.0, 2.0));
              col += accent * gridLine * band * 0.11;
              col += accent * band * 0.012;

              // drifting micro-triangle particles
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

              // corner ornaments: L-shaped brackets
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

              // thin border
              float bd = min(min(frag.x, res.x - frag.x), min(frag.y, res.y - frag.y));
              col += accent * (1.0 - smoothstep(0.0, 1.0, bd)) * 0.10;

              // CRT scanlines (period locked to logical px, avoids sub-pixel moire)
              col *= 0.96 + 0.04 * sin(frag.y * uiScale * PI);

              // vignette
              vec2 q = uv - 0.5;
              col *= 1.0 - 0.32 * dot(q, q);

              // dither against banding
              col += (hash12(frag + fract(u_time) * 13.7) - 0.5) * 0.008;

              // soft highlight rolloff, no hard clipping
              col = col / (1.0 + col * 0.12);

              gl_FragColor = vec4(col, 0.92 * u_alphaMult);
            }
        """

        /**
         * 棱镜栅格 fragment shader（GLSL 110 兼容）。
         *
         * 坐标系：面板局部逻辑像素（v_pos - u_origin）换算为以面板中心为原点、
         * 短边归一（p = (2px - res) / res.y）；lattice 与角部结构按权重早退；
         * PANEL = (1.0, 1.0)，描边/刻度/角部结构紧贴面板边缘，外侧衰减由 UI stencil 裁剪；
         * 角根核心并入 acc3 按 cmask*clip 缩放，贴边后角点外侧部分随 stencil 一并裁剪。
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

              // holo lattice: weight-gated early-out, center fully dark
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

              // panel border: crisp tactical structure line
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

              // corners: prism structure
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

              // inscribed guide lines
              vec2 c0 = hp - vec2(0.060, 0.060);
              float d00 = min(min(min(abs(p.x - c0.x), abs(p.x + c0.x)),
                                  abs(p.y - c0.y)), abs(p.y + c0.y));
              float line00 = 1.0 - smoothstep(0.0, 0.0022, d00);
              col += acc * (line00 * 0.16 + exp(-d00 * 120.0) * 0.04) * (0.5 + 0.5 * sin(t * 1.55));

              // atmosphere & tonemap
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

        private val quadRenderer = ShaderQuadRenderer()

        private fun shaderProgram(): Int {
            val style = HullmodBackgroundStyleConfig.activeStyle
            val cached = shaderProgramIds[style]
            if (cached != 0) return cached

            val fragmentSource = when (style) {
                HullmodBackgroundStyleConfig.STYLE_CORNER_PULSE -> FRAGMENT_CORNER_PULSE
                HullmodBackgroundStyleConfig.STYLE_PRISM_LATTICE -> FRAGMENT_PRISM_LATTICE
                else -> throw IllegalStateException("Unknown hullmod background style: $style")
            }
            val program = ShaderCompatibility.compileProgram("$SHADER_ID-style-$style", fragmentSource)
            shaderProgramIds[style] = program
            return program
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
