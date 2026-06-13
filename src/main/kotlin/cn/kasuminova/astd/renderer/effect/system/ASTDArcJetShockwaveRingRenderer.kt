package cn.kasuminova.astd.renderer.effect.system

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.util.EnumSet

internal object ASTDArcJetShockwaveRingSpec {
    const val STALE_AFTER_SECONDS = 0.18f
    private const val MIN_COLLISION_RADIUS = 80f
    private const val OUTER_RADIUS_MULT = 1.5f

    val REFERENCE_PARAMETERS = Parameters(
        speed = 0.30f,
        thickness = 0.01f,
        ringCount = 3f,
        distortion = 0.50f,
        glow = 1.25f,
        hue = 0.52f,
        saturation = 0.60f,
        exposure = 1.25f,
    )

    data class Parameters(
        val speed: Float,
        val thickness: Float,
        val ringCount: Float,
        val distortion: Float,
        val glow: Float,
        val hue: Float,
        val saturation: Float,
        val exposure: Float,
    )

    data class Frame(
        val outerRadiusWorld: Float,
        val quadHalfExtentWorld: Float,
        val shaderDomainRadius: Float,
        val speed: Float,
        val thickness: Float,
        val ringCount: Float,
        val distortion: Float,
        val glow: Float,
        val hue: Float,
        val saturation: Float,
        val exposure: Float,
        val alphaMult: Float,
    )

    fun outerRadiusWorld(collisionRadius: Float): Float =
        collisionRadius.coerceAtLeast(MIN_COLLISION_RADIUS) * OUTER_RADIUS_MULT

    fun frame(collisionRadius: Float, effectLevel: Float, pressureRatio: Float): Frame {
        val level = effectLevel.coerceIn(0f, 1f)
        val pressure = pressureRatio.coerceIn(0f, 1f)
        val outerRadius = outerRadiusWorld(collisionRadius)
        val alphaMult = level * (0.72f + pressure * 0.28f)
        return Frame(
            outerRadiusWorld = outerRadius,
            quadHalfExtentWorld = outerRadius,
            shaderDomainRadius = 1.3f,
            speed = REFERENCE_PARAMETERS.speed,
            thickness = REFERENCE_PARAMETERS.thickness,
            ringCount = REFERENCE_PARAMETERS.ringCount,
            distortion = REFERENCE_PARAMETERS.distortion,
            glow = REFERENCE_PARAMETERS.glow,
            hue = REFERENCE_PARAMETERS.hue,
            saturation = REFERENCE_PARAMETERS.saturation,
            exposure = REFERENCE_PARAMETERS.exposure * (0.90f + pressure * 0.20f),
            alphaMult = alphaMult,
        )
    }

    fun shouldRetire(ageSinceLastSubmit: Float): Boolean = ageSinceLastSubmit > STALE_AFTER_SECONDS
}

internal object ASTDArcJetShockwaveRingRenderer {
    private const val ENGINE_KEY = "astd_arc_jet_shockwave_ring_renderer"
    private val LAYER = CombatEngineLayers.BELOW_SHIPS_LAYER

    fun render(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        effectLevel: Float,
        pressureRatio: Float,
    ): ASTDArcJetShockwaveRingSpec.Frame? {
        val frame = ASTDArcJetShockwaveRingSpec.frame(ship.collisionRadius, effectLevel, pressureRatio)
        if (frame.alphaMult <= 0.001f) return null
        getOrCreate(engine).submit(ship, frame)
        return frame
    }

    private fun getOrCreate(engine: CombatEngineAPI): Renderer {
        val existing = engine.customData[ENGINE_KEY] as? Renderer
        if (existing != null && !existing.isExpired) return existing

        val renderer = Renderer(engine)
        engine.addLayeredRenderingPlugin(renderer)
        engine.customData[ENGINE_KEY] = renderer
        return renderer
    }

    private class ActiveState(
        val ship: ShipAPI,
        var frame: ASTDArcJetShockwaveRingSpec.Frame,
        val startTime: Float,
        var lastSubmitTime: Float,
    )

    private class Renderer(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(LAYER) {
        private val active = LinkedHashMap<Int, ActiveState>()
        private var elapsed = 0f
        private var expired = false

        fun submit(ship: ShipAPI, frame: ASTDArcJetShockwaveRingSpec.Frame) {
            if (expired) return
            val key = System.identityHashCode(ship)
            val state = active[key]
            if (state == null) {
                active[key] = ActiveState(ship, frame, elapsed, elapsed)
            } else {
                state.frame = frame
                state.lastSubmitTime = elapsed
            }
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> = EnumSet.of(LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun isExpired(): Boolean = expired

        override fun cleanup() {
            active.clear()
            expired = true
        }

        override fun advance(amount: Float) {
            if (expired || engine.isPaused || amount <= 0f) return
            elapsed += amount
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val state = iterator.next().value
                if (state.ship.isHulk || state.ship.hitpoints <= 0f ||
                    ASTDArcJetShockwaveRingSpec.shouldRetire(elapsed - state.lastSubmitTime)
                ) {
                    iterator.remove()
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired || layer != LAYER || active.isEmpty()) return

            val program = shaderProgram()
            val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_TEXTURE_BIT)
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glDisable(GL11.GL_CULL_FACE)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                GL11.glColor4f(1f, 1f, 1f, 1f)
                GL20.glUseProgram(program)
                GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_resolution"), 1f, 1f)

                for (state in active.values) {
                    renderState(program, state)
                }
            } finally {
                GL20.glUseProgram(previousProgram)
                GL11.glPopAttrib()
            }
        }

        private fun renderState(program: Int, state: ActiveState) {
            val frame = state.frame
            val localTime = (elapsed - state.startTime).coerceAtLeast(0f)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_time"), localTime)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_speed"), frame.speed)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_thickness"), frame.thickness)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_ringCount"), frame.ringCount)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_distortion"), frame.distortion)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_glow"), frame.glow)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_hue"), frame.hue)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_saturation"), frame.saturation)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_exposure"), frame.exposure)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_alphaMult"), frame.alphaMult)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_domainRadius"), frame.shaderDomainRadius)

            val center = state.ship.location
            val extent = frame.quadHalfExtentWorld
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glTexCoord2f(0f, 0f)
            GL11.glVertex2f(center.x - extent, center.y - extent)
            GL11.glTexCoord2f(1f, 0f)
            GL11.glVertex2f(center.x + extent, center.y - extent)
            GL11.glTexCoord2f(1f, 1f)
            GL11.glVertex2f(center.x + extent, center.y + extent)
            GL11.glTexCoord2f(0f, 1f)
            GL11.glVertex2f(center.x - extent, center.y + extent)
            GL11.glEnd()
        }
    }

    private const val SHADER_ID = "astd-arc-jet-shockwave-ring"
    private var shaderProgramId = 0

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
        uniform float u_speed;
        uniform float u_thickness;
        uniform float u_ringCount;
        uniform float u_distortion;
        uniform float u_glow;
        uniform float u_hue;
        uniform float u_saturation;
        uniform float u_exposure;
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

        vec3 acesTonemap(vec3 x) {
          const float a = 2.51;
          const float b = 0.03;
          const float c = 2.43;
          const float d = 0.59;
          const float e = 0.14;
          return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
        }

        void main() {
          vec2 p = centeredAspect(v_uv);
          float r = length(p);
          vec2 dir = p / max(r, 1e-4);
          float wobble = (fbm(dir * 3.0 + vec2(u_time * 0.5, 0.0)) - 0.5) * u_distortion;
          float rr = r + wobble * 0.08;

          float energy = 0.0;
          for (int i = 0; i < 4; i++) {
            if (float(i) >= u_ringCount) {
              break;
            }
            float phase = fract(u_time * u_speed - float(i) * 0.26);
            float radius = phase * 1.3;
            float ring = exp(-pow((rr - radius) / max(u_thickness, 1e-3), 2.0));
            float fade = 1.0 - phase;
            energy += ring * fade;
          }

          float flash = exp(-rr * rr * 8.0) * 0.6;
          energy = (energy * u_glow + flash) * u_exposure;

          vec3 tint = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
          vec3 color = tint * energy;
          color = mix(color, vec3(1.0), clamp(flash, 0.0, 1.0));
          color = acesTonemap(color);

          float alpha = clamp(max(color.r, max(color.g, color.b)) + energy * 0.1, 0.0, 1.0);
          gl_FragColor = vec4(color, alpha * u_alphaMult);
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
            throw IllegalStateException("Failed to link shader $SHADER_ID: $message")
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
            throw IllegalStateException("Failed to compile shader $SHADER_ID: $message")
        }
        return shader
    }
}
