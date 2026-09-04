package cn.kasuminova.astd.renderer.effect.system

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
import cn.kasuminova.astd.renderer.shader.domain.ShipSystemShaderEffect
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * Arc Jet ship-system shockwave ring shader effect.
 *
 * The object owns only effect parameters and domain-to-shader submission. All
 * GL program, layer plugin, lifecycle, and state management is delegated to the
 * shared shader runtime.
 */
internal object ArcJetShockwaveRingEffect : ShipSystemShaderEffect {
    const val STALE_AFTER_SECONDS = 0.18f
    private const val MIN_COLLISION_RADIUS = 80f
    private const val OUTER_RADIUS_MULT = 1.5f
    private const val EFFECT_ID = "astd_arc_jet_shockwave_ring"
    private const val PROGRAM_ID = "astd_arc_jet_shockwave_ring_program"

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

    private val SHADER_UNIFORMS = ShaderUniformSchema(
        listOf(
            ShaderUniformDefinition(
                key = "resolution",
                type = ShaderUniformType.Vec2,
                required = false,
                defaultValue = ShaderUniformValue.Vec2(1f, 1f),
            ),
            ShaderUniformDefinition("speed", ShaderUniformType.Float),
            ShaderUniformDefinition("thickness", ShaderUniformType.Float),
            ShaderUniformDefinition("ringCount", ShaderUniformType.Float),
            ShaderUniformDefinition("distortion", ShaderUniformType.Float),
            ShaderUniformDefinition("glow", ShaderUniformType.Float),
            ShaderUniformDefinition("hue", ShaderUniformType.Float),
            ShaderUniformDefinition("saturation", ShaderUniformType.Float),
            ShaderUniformDefinition("exposure", ShaderUniformType.Float),
            ShaderUniformDefinition("alphaMult", ShaderUniformType.Float),
            ShaderUniformDefinition("domainRadius", ShaderUniformType.Float),
        ),
    )

    override val effectSpec: ShaderEffectSpec = ShaderEffectSpec(
        id = ShaderEffectKey(EFFECT_ID),
        program = ShaderProgramSpec(
            id = PROGRAM_ID,
            vertexSource = VERTEX_SHADER_SOURCE,
            fragmentSource = FRAGMENT_SHADER_SOURCE,
        ),
        geometry = ShaderGeometrySpec.WorldQuad(outerRadiusWorld(MIN_COLLISION_RADIUS)),
        material = ShaderMaterialSpec(ShaderBlendMode.Additive),
        uniformSchema = SHADER_UNIFORMS,
        layer = ShaderEffectLayer.BelowParticles,
        lifetimeSeconds = 1f,
        staleAfterSeconds = STALE_AFTER_SECONDS,
        renderRadius = outerRadiusWorld(MIN_COLLISION_RADIUS),
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

    fun render(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        effectLevel: Float,
        pressureRatio: Float,
    ): Frame? {
        val frame = frame(ship.collisionRadius, effectLevel, pressureRatio)
        submitFrame(
            sink = CombatShaderRuntime.ensure(engine).sink,
            instanceId = instanceId(ship),
            center = ship.location,
            frame = frame,
        ) ?: return null
        return frame
    }

    override fun submit(
        engine: CombatEngineAPI,
        sink: ShaderSink,
        ship: ShipAPI,
        effectLevel: Float,
        intensity: Float,
    ): ShaderHandle? {
        return submitFrame(
            sink = sink,
            instanceId = instanceId(ship),
            center = ship.location,
            frame = frame(ship.collisionRadius, effectLevel, intensity),
        )
    }

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

    private fun instanceId(ship: ShipAPI): String = "ship-${System.identityHashCode(ship)}"

    private fun uniforms(frame: Frame): ShaderUniformSet = ShaderUniformSet(
        SHADER_UNIFORMS,
        mapOf(
            "speed" to ShaderUniformValue.FloatValue(frame.speed),
            "thickness" to ShaderUniformValue.FloatValue(frame.thickness),
            "ringCount" to ShaderUniformValue.FloatValue(frame.ringCount),
            "distortion" to ShaderUniformValue.FloatValue(frame.distortion),
            "glow" to ShaderUniformValue.FloatValue(frame.glow),
            "hue" to ShaderUniformValue.FloatValue(frame.hue),
            "saturation" to ShaderUniformValue.FloatValue(frame.saturation),
            "exposure" to ShaderUniformValue.FloatValue(frame.exposure),
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
}
