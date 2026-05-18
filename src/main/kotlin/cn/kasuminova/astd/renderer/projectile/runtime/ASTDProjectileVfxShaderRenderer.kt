package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxGlowLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

object ASTDProjectileVfxShaderRenderer {
    enum class Kind(val id: Int) { Body(0), Glow(1), Head(2) }

    data class Bounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
    )

    data class Params(
        val visibleLength: Float,
        val pulse: Float,
        val widthBase: Float,
        val bodyXScale: Float = 1f,
        val bodyYScale: Float = 1f,
        val bodyShadowBlur: Float = 0f,
        val tailColor: ASTDColor = ASTDColor(0f, 0f, 0f, 0f),
        val headColor: ASTDColor = ASTDColor(1f, 1f, 1f, 1f),
        val tailEmissive: ASTDColor = ASTDColor(0f, 0f, 0f, 0f),
        val headEmissive: ASTDColor = ASTDColor(1f, 1f, 1f, 1f),
        val headWidth: Float = 0f,
        val headAlpha: Float = 0f,
        val headVisible: Float = 0f,
        val headRearX: Float = 0f,
        val headFilterBlur: Float = 0f,
        val headShadowBlur: Float = 0f,
        val headStart: ASTDColor = ASTDColor(0f, 0f, 0f, 0f),
        val headMid: ASTDColor = ASTDColor(0f, 0f, 0f, 0f),
        val headEnd: ASTDColor = ASTDColor(1f, 1f, 1f, 1f),
        val glowLineWidth: Float = 0f,
        val glowAlpha: Float = 0f,
        val glowBlur: Float = 0f,
        val glowYOffset: Float = 0f,
        val glowTail: ASTDColor = ASTDColor(0f, 0f, 0f, 0f),
        val glowHead: ASTDColor = ASTDColor(1f, 1f, 1f, 1f),
    )

    data class Quad(
        val kind: Kind,
        val bounds: Bounds,
        val params: Params,
        val combatLayer: CombatEngineLayers,
        val blendMode: String,
    )

    fun bodyQuadForTests(
        trail: ASTDTrailEntitySpec,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): Quad {
        val layer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(layer)
        val pulse = context.beamAlpha.coerceIn(0f, 1f) * alphaScale.coerceIn(0f, 1f)
        val blur = max(8f, widthBase * 2.4f)
        val halfHeight = (widthBase * 4.6f + blur * 0.55f).coerceAtLeast(18f)
        return Quad(
            kind = Kind.Body,
            bounds = Bounds(-context.visibleLength.coerceAtLeast(6f) * 0.98f, 0f, -halfHeight, halfHeight),
            params = Params(
                visibleLength = context.visibleLength,
                pulse = pulse,
                widthBase = widthBase,
                bodyXScale = 1.55f,
                bodyYScale = 0.58f,
                bodyShadowBlur = blur,
                tailColor = layer.endColor,
                headColor = layer.startColor,
                tailEmissive = layer.endEmissive,
                headEmissive = layer.startEmissive,
            ),
            combatLayer = layer.combatLayer,
            blendMode = "additive",
        )
    }

    fun headQuadForTests(
        trail: ASTDTrailEntitySpec,
        layer: ASTDProjectileVfxHeadLayerSpec,
        context: ASTDProjectileVfxRenderContext,
        headSizeScale: Float = 1f,
        alphaScale: Float = 1f,
    ): Quad {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val layout = ASTDProjectileVfxLayout.headFillLayout(baseLayer, layer, headSizeScale, widthBase, context.beamAlpha.coerceIn(0f, 1f))
        val vertical = (layout.width * 1.6f + max(8f, widthBase * 2.8f) * layout.headVisible + layer.blur).coerceAtLeast(4f)
        return Quad(
            kind = Kind.Head,
            bounds = Bounds(layout.rearX, 0f, -vertical, vertical),
            params = Params(
                visibleLength = context.visibleLength,
                pulse = context.beamAlpha.coerceIn(0f, 1f) * alphaScale.coerceIn(0f, 1f),
                widthBase = widthBase,
                bodyXScale = 1.2f,
                bodyYScale = 0.54f,
                headWidth = layout.width,
                headAlpha = layout.alpha * alphaScale.coerceIn(0f, 1f),
                headVisible = layout.headVisible,
                headRearX = layout.rearX,
                headFilterBlur = layer.blur,
                headShadowBlur = max(8f, widthBase * 2.8f) * layout.headVisible,
                headStart = layout.colors.start,
                headMid = layout.colors.mid,
                headEnd = layout.colors.end,
            ),
            combatLayer = baseLayer.combatLayer,
            blendMode = layer.blendMode,
        )
    }

    fun glowQuadsForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxGlowLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): List<Quad> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        return layers.filter { it.enabled }.map { layer ->
            val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, layer)
            val headGap = max(14f, lineWidth * 0.55f)
            val (tail, head) = ASTDProjectileVfxGlowRenderer.colors(baseLayer, layer)
            val vertical = (lineWidth * 0.58f + layer.blur * context.beamAlpha.coerceIn(0f, 1f) * 0.10f + kotlin.math.abs(layer.yOffset)).coerceAtLeast(4f)
            Quad(
                kind = Kind.Glow,
                bounds = Bounds(-context.visibleLength * 0.8f, -headGap, -vertical, vertical),
                params = Params(
                    visibleLength = context.visibleLength,
                    pulse = context.beamAlpha.coerceIn(0f, 1f) * alphaScale.coerceIn(0f, 1f),
                    widthBase = widthBase,
                    bodyXScale = 1.2f,
                    bodyYScale = 0.34f,
                    glowLineWidth = lineWidth,
                    glowAlpha = layer.alphaScale * context.beamAlpha.coerceIn(0f, 1f) * alphaScale.coerceIn(0f, 1f),
                    glowBlur = layer.blur * context.beamAlpha.coerceIn(0f, 1f),
                    glowYOffset = layer.yOffset,
                    glowTail = tail,
                    glowHead = head,
                ),
                combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
                blendMode = "additive",
            )
        }
    }

    internal class Program {
        private val id: Int
        private val uniforms: Map<String, Int>

        init {
            id = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            uniforms = UNIFORM_NAMES.associateWith { GL20.glGetUniformLocation(id, it) }
            val missing = uniforms.filterValues { it < 0 }.keys
            if (missing.isNotEmpty()) {
                GL20.glDeleteProgram(id)
                error("ASTD projectile shader missing uniforms: ${missing.joinToString()}")
            }
        }

        fun render(snapshot: ASTDProjectileVfxBodyRenderManager.Snapshot, quad: Quad) {
            GL20.glUseProgram(id)
            put(quad)
            GL11.glBegin(GL11.GL_QUADS)
            try {
                vertex(snapshot, quad.bounds.minX, quad.bounds.minY, quad)
                vertex(snapshot, quad.bounds.maxX, quad.bounds.minY, quad)
                vertex(snapshot, quad.bounds.maxX, quad.bounds.maxY, quad)
                vertex(snapshot, quad.bounds.minX, quad.bounds.maxY, quad)
            } finally {
                GL11.glEnd()
                GL20.glUseProgram(0)
            }
        }

        fun delete() {
            GL20.glDeleteProgram(id)
        }

        private fun put(quad: Quad) {
            val p = quad.params
            GL20.glUniform1i(uniforms.getValue("uKind"), quad.kind.id)
            GL20.glUniform1f(uniforms.getValue("uVisibleLength"), p.visibleLength)
            GL20.glUniform1f(uniforms.getValue("uPulse"), p.pulse)
            GL20.glUniform1f(uniforms.getValue("uWidthBase"), p.widthBase)
            GL20.glUniform1f(uniforms.getValue("uBodyShadowBlur"), p.bodyShadowBlur)
            GL20.glUniform1f(uniforms.getValue("uHeadWidth"), p.headWidth)
            GL20.glUniform1f(uniforms.getValue("uHeadAlpha"), p.headAlpha)
            GL20.glUniform1f(uniforms.getValue("uHeadVisible"), p.headVisible)
            GL20.glUniform1f(uniforms.getValue("uHeadRearX"), p.headRearX)
            GL20.glUniform1f(uniforms.getValue("uHeadShadowBlur"), p.headShadowBlur)
            GL20.glUniform1f(uniforms.getValue("uGlowLineWidth"), p.glowLineWidth)
            GL20.glUniform1f(uniforms.getValue("uGlowAlpha"), p.glowAlpha)
            GL20.glUniform1f(uniforms.getValue("uGlowBlur"), p.glowBlur)
            GL20.glUniform1f(uniforms.getValue("uGlowYOffset"), p.glowYOffset)
            putColor("uTailColor", p.tailColor)
            putColor("uHeadColor", p.headColor)
            putColor("uTailEmissive", p.tailEmissive)
            putColor("uHeadEmissive", p.headEmissive)
            putColor("uShellStart", p.headStart)
            putColor("uShellMid", p.headMid)
            putColor("uShellEnd", p.headEnd)
            putColor("uGlowTail", p.glowTail)
            putColor("uGlowHead", p.glowHead)
        }

        private fun putColor(name: String, color: ASTDColor) {
            GL20.glUniform4f(uniforms.getValue(name), color.red, color.green, color.blue, color.alpha)
        }

        private fun vertex(snapshot: ASTDProjectileVfxBodyRenderManager.Snapshot, x: Float, y: Float, quad: Quad) {
            GL11.glTexCoord2f(x, y)
            val scaled = Vector2f(x * quad.params.bodyXScale, y * quad.params.bodyYScale)
            val world = ASTDProjectileVfxBodyRenderManager.transformLocalPointForTests(scaled, snapshot.location, snapshot.facing)
            GL11.glVertex2f(world.x, world.y)
        }
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vertex)
        GL20.glAttachShader(program, fragment)
        GL20.glLinkProgram(program)
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetProgramInfoLog(program, 8192)
            GL20.glDeleteShader(vertex)
            GL20.glDeleteShader(fragment)
            GL20.glDeleteProgram(program)
            error("ASTD projectile shader link failed: $log")
        }
        GL20.glDeleteShader(vertex)
        GL20.glDeleteShader(fragment)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(shader, 8192)
            GL20.glDeleteShader(shader)
            error("ASTD projectile shader compile failed type=$type: $log")
        }
        return shader
    }

    private val UNIFORM_NAMES = listOf(
        "uKind", "uVisibleLength", "uPulse", "uWidthBase", "uBodyShadowBlur",
        "uHeadWidth", "uHeadAlpha", "uHeadVisible", "uHeadRearX", "uHeadShadowBlur",
        "uGlowLineWidth", "uGlowAlpha", "uGlowBlur", "uGlowYOffset",
        "uTailColor", "uHeadColor", "uTailEmissive", "uHeadEmissive",
        "uShellStart", "uShellMid", "uShellEnd", "uGlowTail", "uGlowHead",
    )

    private const val VERTEX_SHADER = """
#version 120
varying vec2 vLocal;
void main() {
    vLocal = gl_MultiTexCoord0.xy;
    gl_Position = ftransform();
}
"""

    private const val FRAGMENT_SHADER = """
#version 120
varying vec2 vLocal;
uniform int uKind;
uniform float uVisibleLength;
uniform float uPulse;
uniform float uWidthBase;
uniform float uBodyShadowBlur;
uniform float uHeadWidth;
uniform float uHeadAlpha;
uniform float uHeadVisible;
uniform float uHeadRearX;
uniform float uHeadShadowBlur;
uniform float uGlowLineWidth;
uniform float uGlowAlpha;
uniform float uGlowBlur;
uniform float uGlowYOffset;
uniform vec4 uTailColor;
uniform vec4 uHeadColor;
uniform vec4 uTailEmissive;
uniform vec4 uHeadEmissive;
uniform vec4 uShellStart;
uniform vec4 uShellMid;
uniform vec4 uShellEnd;
uniform vec4 uGlowTail;
uniform vec4 uGlowHead;

float saturate(float v) { return clamp(v, 0.0, 1.0); }
float sstep(float a, float b, float v) {
    float t = saturate((v - a) / max(b - a, 0.0001));
    return t * t * (3.0 - 2.0 * t);
}
vec4 mix4(vec4 a, vec4 b, float t) { return mix(a, b, saturate(t)); }

float bodyHalf(float x) {
    float tailReach = max(uVisibleLength, 6.0);
    float tailWidth = max(1.0, uWidthBase * 0.72);
    float headVisible = sstep(0.28, 0.82, uPulse);
    float projectileWidth = max(4.8, uWidthBase * 1.72) * headVisible;
    float headLength = max(30.0, uWidthBase * 12.4) * headVisible;
    float coreLength = max(20.0, uWidthBase * 8.8) * headVisible;
    float shoulderX = -headLength * 0.42;
    float tail0 = -tailReach * 0.86;
    float tail1 = -tailReach * 0.36;
    if (x < tail1) {
        float t = saturate((x - tail0) / max(tail1 - tail0, 0.0001));
        return mix(tailWidth * 0.12, tailWidth * 0.32, t);
    }
    if (x < -coreLength) {
        float t = saturate((x - tail1) / max(-coreLength - tail1, 0.0001));
        return mix(tailWidth * 0.32, projectileWidth * 0.56, t);
    }
    if (x < shoulderX) {
        float t = saturate((x + coreLength) / max(shoulderX + coreLength, 0.0001));
        return mix(projectileWidth * 0.56, projectileWidth * 0.76, t);
    }
    float t = saturate((x - shoulderX) / max(-shoulderX, 0.0001));
    return mix(projectileWidth * 0.76, 0.0, t);
}

vec4 bodyColor(float x, float halfBase) {
    float tailReach = max(uVisibleLength, 6.0) * 0.86;
    float g = saturate((x + tailReach) / max(tailReach, 0.0001));
    vec4 body = mix4(uTailColor, uHeadColor, 0.42);
    vec4 color = mix4(uTailColor * vec4(0.16, 0.16, 0.16, 1.0), body, sstep(0.0, 0.24, g));
    color = mix4(color, mix4(uHeadColor, uHeadEmissive, 0.18), sstep(0.24, 0.70, g));
    color = mix4(color, vec4(1.0), sstep(0.70, 0.88, g));
    float fillAlpha = 0.0;
    if (g < 0.24) fillAlpha = mix(0.0, 0.08 * uPulse, saturate(g / 0.24));
    else if (g < 0.70) fillAlpha = mix(0.08 * uPulse, 0.58 * uPulse, saturate((g - 0.24) / 0.46));
    else if (g < 0.88) fillAlpha = mix(0.58 * uPulse, 0.92 * uPulse, saturate((g - 0.70) / 0.18));
    else fillAlpha = mix(0.92 * uPulse, 0.0, saturate((g - 0.88) / 0.12));
    float fill = 1.0 - sstep(halfBase * 0.74, halfBase, abs(vLocal.y));
    float glow = exp(-pow(max(abs(vLocal.y) - halfBase, 0.0) / max(uBodyShadowBlur * 0.34, 0.001), 2.0)) * 0.075 * uPulse;
    vec4 emissive = mix4(uTailEmissive, uHeadEmissive, 0.55);
    return color * fill * fillAlpha + emissive * glow;
}

vec4 glowColor() {
    float lineStart = -uVisibleLength * 0.72;
    float headGap = max(14.0, uGlowLineWidth * 0.55);
    float t = saturate((vLocal.x - lineStart) / max(-headGap - lineStart, 0.0001));
    vec4 color;
    float alpha;
    if (t <= 0.22) {
        color = uGlowTail * vec4(0.36, 0.36, 0.36, 1.0);
        alpha = mix(0.0, uGlowAlpha * 0.22, saturate(t / 0.22));
    } else if (t <= 0.68) {
        color = uGlowTail;
        alpha = mix(uGlowAlpha * 0.22, uGlowAlpha * 0.52, saturate((t - 0.22) / 0.46));
    } else if (t <= 0.90) {
        color = mix4(uGlowTail, uGlowHead, 0.55);
        alpha = mix(uGlowAlpha * 0.52, uGlowAlpha, saturate((t - 0.68) / 0.22));
    } else {
        color = mix4(uGlowHead, vec4(1.0, 0.9, 0.98, 1.0), saturate((t - 0.88) / 0.12));
        alpha = mix(uGlowAlpha, uGlowAlpha * 0.46, saturate((t - 0.88) / 0.12));
    }
    float centerY = mix(uGlowYOffset, uGlowYOffset * 0.18, t);
    float halfWidth = max(1.0, uGlowLineWidth) * 0.5;
    float blur = max(uGlowBlur, 0.001);
    float dist = abs(vLocal.y - centerY);
    float core = 1.0 - sstep(halfWidth * 0.72, halfWidth, dist);
    float halo = exp(-pow(max(dist - halfWidth, 0.0) / max(blur * 0.24, 0.001), 2.0)) * 0.18;
    return color * alpha * max(core, halo);
}

vec4 shellGradient(float progress) {
    if (progress <= 0.36) return mix4(uShellStart, uShellMid, progress / 0.36);
    if (progress <= 0.74) return mix4(uShellMid, uShellEnd, (progress - 0.36) / 0.38);
    return mix4(uShellEnd, vec4(1.0), (progress - 0.74) / 0.26);
}

vec4 headColor() {
    if (uHeadVisible <= 0.01) return vec4(0.0);
    float p = saturate((vLocal.x - uHeadRearX) / max(-uHeadRearX, 0.0001));
    float top;
    if (p < 0.47) top = mix(uHeadWidth * 0.2, uHeadWidth * 0.52, p / 0.47);
    else top = mix(uHeadWidth * 0.52, 0.0, sstep(0.47, 1.0, p));
    float fill = 1.0 - sstep(top * 0.70, max(top, 0.001), abs(vLocal.y));
    float halo = exp(-pow(max(abs(vLocal.y) - top, 0.0) / max(uHeadShadowBlur * 0.28, 0.001), 2.0)) * 0.12;
    vec4 color = shellGradient(p);
    return color * uHeadAlpha * max(fill, halo);
}

void main() {
    vec4 color = vec4(0.0);
    if (uKind == 0) {
        float halfBase = bodyHalf(vLocal.x);
        color = bodyColor(vLocal.x, halfBase);
    } else if (uKind == 1) {
        color = glowColor();
    } else {
        color = headColor();
    }
    gl_FragColor = vec4(color.rgb, saturate(color.a));
}
"""
}
