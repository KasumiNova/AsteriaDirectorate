package cn.kasuminova.astd.ui.effect

import cn.kasuminova.astd.ui.render.ASTDStencilRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.ui.PositionAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.awt.Color
import kotlin.random.Random

/**
 * ASTD 船插 Tooltip 统一背景（四角几何簇 + 曲线透明度动画）。
 *
 * 视觉规格：
 * - 四角各一簇 4 枚几何体（三角/六边形），沿面板对角线向中心递推，越靠边缘越亮、越近中心越淡；
 * - 描边 / 全着色形态按实例随机混合；颜色跟随船插主题色（[accentColor] 随 Theme 传入）；
 * - 形状不透明度上限 50%、下限 0%；
 * - 动画变体（每实例随机其一）：
 *   0 棱簇呼吸：随机点亮某侧（该侧两角全幅呼吸，其余角弱幅），smoothstep 曲线呼吸；
 *   1 棱簇流光：对角流光带周期性扫过面板，形状随带经过依次点亮；
 *   2 晶巢呼吸：六边形簇 + 更慢节奏的呼吸。
 *
 * 渲染通道评估：BoxUtil 的渲染实体（SpriteEntity/TrailEntity 等）面向战斗/星图世界层，
 * 不提供 UI 面板内绘制工具；Tooltip 背景必须落在 UI stencil 裁剪区内嵌 GL 绘制，
 * 故沿用 GLSL 全屏四边形路径（本仓库唯一可用通道，与原版字体渲染同一固定管线）。
 *
 * 编辑器镜像预设：`tools/tooltip-style-editor/src/model/defaultHullmodPreset.ts`，
 * shader id `prism-cluster`（u_origin 在编辑器内以 (0,0) 全画布代入）。
 */
class ASTDHullModTooltipBackground private constructor(
    private val panelWidth: Float,
    private val accentColor: Color,
) : BaseCustomUIPanelPlugin() {

    var contentHeight: Float = 0f

    private var pos: PositionAPI? = null
    private var timeAcc: Float = 0f

    /** 实例随机种子：形状形态/抖动/点亮侧的确定性随机源（同一次打开内稳定）。 */
    private val seed: Float = Random.nextFloat()

    /** 动画变体：0 棱簇呼吸 / 1 棱簇流光 / 2 晶巢呼吸。 */
    private val variant: Int = Random.nextInt(VARIANT_COUNT)

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
        val x = p.x
        val y = p.y - h

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
        GL11.glPushMatrix()
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

        ASTDStencilRenderer.withStencilMask(x, y, panelWidth, h) {
            renderShaderQuad(x, y, panelWidth, h, alphaMult)
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
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_seed"), seed)
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "u_variant"), variant)

        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()

        GL20.glUseProgram(previousProgram)
    }

    companion object {
        /** 动画变体数量（u_variant 取值域 [0, VARIANT_COUNT)）。 */
        const val VARIANT_COUNT: Int = 3

        private const val SHADER_ID = "astd-hullmod-cluster"

        private var shaderProgramId = 0

        private const val VERTEX_SHADER_SOURCE = """
            void main() {
              gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
            }
        """

        /**
         * 四角几何簇 fragment shader（GLSL 110 兼容：无数组初始化器、常量循环界）。
         *
         * 坐标系：像素空间（gl_FragCoord - u_origin），y 向上；
         * 角序 c：0=左下 1=右下 2=右上 3=左上；形状沿对角线向面板中心递推 4 枚。
         */
        private const val FRAGMENT_SHADER_SOURCE = """
            uniform float u_time;
            uniform vec2 u_origin;
            uniform vec2 u_resolution;
            uniform vec4 u_accentColor;
            uniform float u_alphaMult;
            uniform float u_seed;
            uniform int u_variant;

            float hash11(float n) {
              return fract(sin(n) * 43758.5453123);
            }

            vec2 rot2(vec2 p, float a) {
              float c = cos(a);
              float s = sin(a);
              return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
            }

            // 等边三角形 SDF（尖端朝 +Y；iq 口径）
            float sdTriangle(vec2 p, float r) {
              const float k = 1.7320508;
              p.x = abs(p.x) - r;
              p.y = p.y + r / k;
              if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
              p.x -= clamp(p.x, -2.0 * r, 0.0);
              return -length(p) * sign(p.y);
            }

            // 正六边形 SDF（iq 口径）
            float sdHexagon(vec2 p, float r) {
              const vec3 k = vec3(-0.866025404, 0.5, 0.577350269);
              p = abs(p);
              p -= 2.0 * min(dot(k.xy, p), 0.0) * k.xy;
              p -= vec2(clamp(p.x, -k.z * r, k.z * r), r);
              return length(p) * sign(p.y);
            }

            void main() {
              vec2 px = gl_FragCoord.xy - u_origin;
              float w = u_resolution.x;
              float h = u_resolution.y;
              vec3 base = vec3(0.0, 0.01, 0.012);

              // 距最近角超过簇半径的片元直接输出底色（省 16 次 SDF）
              float minD = min(
                min(distance(px, vec2(0.0, 0.0)), distance(px, vec2(w, 0.0))),
                min(distance(px, vec2(w, h)), distance(px, vec2(0.0, h))));
              if (minD > 175.0) {
                gl_FragColor = vec4(base, 0.92 * u_alphaMult);
                return;
              }

              // 随机点亮侧：0=左 1=右 2=上 3=下
              float litSide = floor(hash11(u_seed * 91.7) * 4.0);

              // 呼吸曲线（smoothstep 缓动的周期波，variant 2 更慢）
              float breathSpeed = (u_variant == 2) ? 0.20 : 0.30;

              float acc = 0.0;
              for (int c = 0; c < 4; c++) {
                vec2 cPos;
                float angDeg;
                if (c == 0) { cPos = vec2(0.0, 0.0); angDeg = 45.0; }
                else if (c == 1) { cPos = vec2(w, 0.0); angDeg = 135.0; }
                else if (c == 2) { cPos = vec2(w, h); angDeg = 225.0; }
                else { cPos = vec2(0.0, h); angDeg = 315.0; }

                float ang = radians(angDeg);
                vec2 dir = vec2(cos(ang), sin(ang));
                vec2 perp = vec2(-dir.y, dir.x);

                // 本角是否落在被点亮侧上
                bool lit =
                  (litSide < 0.5 && (c == 0 || c == 3)) ||
                  (litSide >= 0.5 && litSide < 1.5 && (c == 1 || c == 2)) ||
                  (litSide >= 1.5 && litSide < 2.5 && (c == 2 || c == 3)) ||
                  (litSide >= 2.5 && (c == 0 || c == 1));

                // 呼吸相位：逐角错相；伴生形状的垂向落侧逐角随机
                float phase = hash11(u_seed * 131.7 + float(c) * 7.31);
                float sideSign = hash11(u_seed * 557.3 + float(c) * 3.17) < 0.5 ? -1.0 : 1.0;

                for (int k = 0; k < 4; k++) {
                  // 簇布局（沿对角线递推 + 一枚垂向伴生；全部完整收进面板内，
                  // 最近点距角 ≥2px，不被 stencil 裁切）：
                  //   k0 主形 18px 贴角 / k1 次形 12px / k2 伴生 9px 垂向偏移 / k3 末形 7px
                  float size;
                  vec2 off;
                  if (k == 0)      { size = 18.0; off = vec2(32.0, 0.0); }
                  else if (k == 1) { size = 12.0; off = vec2(72.0, 0.0); }
                  else if (k == 2) { size = 9.0;  off = vec2(66.0, 26.0); }
                  else             { size = 7.0;  off = vec2(100.0, -6.0); }
                  if (u_variant == 2) size *= 0.9;

                  float jx = (hash11(u_seed * 371.3 + float(c * 17 + k * 7)) - 0.5) * 5.0;
                  float jy = (hash11(u_seed * 733.1 + float(c * 11 + k * 5)) - 0.5) * 5.0;
                  vec2 center = cPos + dir * (off.x + jx) + perp * (off.y * sideSign + jy);
                  vec2 lp = rot2(px - center, radians(90.0) - ang);

                  float sd = (u_variant == 2) ? sdHexagon(lp, size) : sdTriangle(lp, size);

                  bool filled = hash11(u_seed * 917.1 + float(c * 31 + k * 13)) < 0.30;
                  float shape;
                  if (filled) {
                    shape = 1.0 - smoothstep(-0.75, 0.75, sd);
                  } else {
                    shape = 1.0 - smoothstep(1.0, 1.8, abs(sd));
                  }

                  // 动画系数
                  float anim;
                  if (u_variant == 1) {
                    // 流光：对角带周期扫过（左下 -> 右上）
                    float proj = (px.x + px.y) / (w + h);
                    float band = fract(u_time * 0.16);
                    float dd = abs(fract(proj - band + 0.5) - 0.5);
                    anim = 0.15 + 0.85 * smoothstep(0.16, 0.02, dd);
                  } else {
                    float br = 0.5 - 0.5 * cos(6.2831853 * fract(u_time * breathSpeed + phase));
                    br = br * br * (3.0 - 2.0 * br);
                    anim = br * (lit ? 1.0 : 0.35);
                  }

                  // 边缘亮、中心淡：递推透明度 0.50 -> 0.26（上限 50%）
                  float alphaK;
                  if (k == 0) alphaK = 0.50;
                  else if (k == 1) alphaK = 0.42;
                  else if (k == 2) alphaK = 0.34;
                  else alphaK = 0.26;
                  acc = max(acc, shape * alphaK * anim);
                }
              }

              vec3 color = base + u_accentColor.rgb * acc;
              gl_FragColor = vec4(color, 0.92 * u_alphaMult);
            }
        """

        private fun shaderProgram(): Int {
            if (shaderProgramId != 0) return shaderProgramId

            val vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE)
            val fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE)
            val program = GL20.glCreateProgram()
            GL20.glAttachShader(program, vertexShader)
            GL20.glAttachShader(program, fragmentShader)
            GL20.glLinkProgram(program)
            GL20.glDeleteShader(vertexShader)
            GL20.glDeleteShader(fragmentShader)

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                val message = GL20.glGetProgramInfoLog(program, 4096)
                GL20.glDeleteProgram(program)
                throw IllegalStateException("Failed to link tooltip background shader $SHADER_ID: $message")
            }

            shaderProgramId = program
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GL20.glCreateShader(type)
            GL20.glShaderSource(shader, source)
            GL20.glCompileShader(shader)
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                val message = GL20.glGetShaderInfoLog(shader, 4096)
                GL20.glDeleteShader(shader)
                throw IllegalStateException("Failed to compile tooltip background shader $SHADER_ID: $message")
            }
            return shader
        }

        @JvmStatic
        fun create(
            tooltip: TooltipMakerAPI,
            width: Float,
            accentColor: Color,
        ): ASTDHullModTooltipBackground {
            val plugin = ASTDHullModTooltipBackground(width, accentColor)
            val panel = Global.getSettings().createCustom(0f, 0f, plugin)
            tooltip.addCustom(panel, 0f)
            return plugin
        }
    }
}
