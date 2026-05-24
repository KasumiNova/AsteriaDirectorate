package cn.kasuminova.astd.ui.effect

import cn.kasuminova.astd.ui.render.ASTDStencilRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.ui.PositionAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.awt.Color
import kotlin.math.sin

/**
 * Tooltip background port of the tooltip editor's "Lattice Pulse" GLSL preset (`lattice-pulse`).
 *
 * Browser reference:
 * `tools/tooltip-style-editor/src/model/defaultHullmodPreset.ts`, shader id `lattice-pulse`.
 */
class ASTDLatticePulseTooltipBackground private constructor(
    private val panelWidth: Float,
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

        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()

        GL20.glUseProgram(previousProgram)
    }

    fun latticePulse(uvX: Float): Float = 0.55f + 0.45f * sin(timeAcc * 1.2f + uvX * 8f)

    companion object {
        const val LATTICE_COLUMNS: Int = 18
        const val LATTICE_ROWS: Int = 11

        private const val BASE_R = 0f
        private const val BASE_G = 0.01f
        private const val BASE_B = 0.012f
        private const val BASE_ALPHA = 0.92f
        private const val LINE_ALPHA = 0.13f
        private const val SHADER_ID = "lattice-pulse"

        private var shaderProgramId = 0

        private const val VERTEX_SHADER_SOURCE = """
            void main() {
              gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
            }
        """

        private const val FRAGMENT_SHADER_SOURCE = """
            uniform float u_time;
            uniform vec2 u_origin;
            uniform vec2 u_resolution;
            uniform vec4 u_accentColor;
            uniform float u_alphaMult;

            void main() {
              vec2 uv = (gl_FragCoord.xy - u_origin) / u_resolution.xy;
              uv = clamp(uv, vec2(0.0), vec2(1.0));
              vec2 cell = abs(fract(uv * vec2(18.0, 11.0)) - 0.5);
              float line = smoothstep(0.018, 0.0, min(cell.x, cell.y));
              float pulse = 0.55 + 0.45 * sin(u_time * 1.2 + uv.x * 8.0);
              vec3 base = vec3(0.0, 0.01, 0.012);
              vec3 color = base + u_accentColor.rgb * line * 0.13 * pulse;
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
        ): ASTDLatticePulseTooltipBackground {
            val plugin = ASTDLatticePulseTooltipBackground(width, accentColor)
            val panel = Global.getSettings().createCustom(0f, 0f, plugin)
            tooltip.addCustom(panel, 0f)
            return plugin
        }
    }
}
