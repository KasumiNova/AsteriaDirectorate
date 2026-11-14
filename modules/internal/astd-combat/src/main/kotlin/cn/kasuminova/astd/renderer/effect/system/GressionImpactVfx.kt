package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.impl.render.ShaderCompatibility
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.FlareParticlePool
import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectKey
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.base.ShaderMaterialSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderProgramSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformDefinition
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSchema
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSet
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformType
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformValue
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object GressionImpactVfx {
    private const val DURATION = 0.78f
    private const val SPARK_POOL = "astd_gression_impact_sparks"
    private val CORE = Color(225, 242, 255, 235)
    private val FRINGE = Color(105, 185, 255, 150)
    private val SCHEMA = ShaderUniformSchema(listOf(
        ShaderUniformDefinition("origin", ShaderUniformType.Vec2),
        ShaderUniformDefinition("forward", ShaderUniformType.Vec2),
        ShaderUniformDefinition("range", ShaderUniformType.Float),
        ShaderUniformDefinition("halfAngle", ShaderUniformType.Float),
    ))

    fun spawn(engine: CombatEngineAPI, origin: Vector2f, facingDeg: Float, range: Float, halfAngleDeg: Float) {
        if (!range.isFinite() || range <= 0f || !halfAngleDeg.isFinite() || halfAngleDeg <= 0f || halfAngleDeg > 90f) {
            AstdLog.logger.warn("[ASTD] 贯虹冲击参数无效（range=$range halfAngleDeg=$halfAngleDeg）")
            return
        }
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(facingDeg)
        val angle = Math.toRadians(facing.toDouble())
        val spec = ShaderEffectSpec(
            id = ShaderEffectKey("gression_impact_wave"),
            program = ShaderProgramSpec("gression_impact_wave", ShaderCompatibility.VERTEX_SHADER_SOURCE, FRAGMENT_SHADER_SOURCE),
            geometry = ShaderGeometrySpec.WorldQuad(range * 1.12f),
            material = ShaderMaterialSpec(ShaderBlendMode.Additive),
            uniformSchema = SCHEMA,
            layer = ShaderEffectLayer.AboveShips,
            lifetimeSeconds = DURATION,
            staleAfterSeconds = DURATION,
            renderRadius = range * 1.12f,
        )
        CombatShaderRuntime.ensure(engine).sink.emit(
            spec, Vector2f(origin), facing,
            ShaderUniformSet(SCHEMA, mapOf(
                "origin" to ShaderUniformValue.Vec2(origin.x, origin.y),
                "forward" to ShaderUniformValue.Vec2(cos(angle).toFloat(), sin(angle).toFloat()),
                "range" to ShaderUniformValue.FloatValue(range),
                "halfAngle" to ShaderUniformValue.FloatValue(Math.toRadians(halfAngleDeg.toDouble()).toFloat()),
            )),
        )
        spawnSparks(engine, origin, facing, range, halfAngleDeg)
    }

    private fun spawnSparks(engine: CombatEngineAPI, origin: Vector2f, facing: Float, range: Float, halfAngle: Float) {
        val pool = FlareParticlePool.obtain(
            engine, SPARK_POOL, CombatEngineLayers.ABOVE_SHIPS_LAYER,
            smooth = false, sizeX = 60f, sizeY = 8f,
            capacity = 2048, maxDuration = 0.9f, glowPower = 0.35f,
        ) ?: return
        val count = (60f + halfAngle * 0.4f).roundToInt().coerceIn(70, 100)
        repeat(count) { index ->
            val fraction = (index + MathUtils.getRandomNumberInRange(0.1f, 0.9f)) / count
            val angle = BoxUtilCombatVfx.normalizeFacingDeg(facing + (fraction * 2f - 1f) * halfAngle * 0.94f)
            val rad = Math.toRadians(angle.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            val distance = range * MathUtils.getRandomNumberInRange(0.025f, 0.18f)
            val speed = range * MathUtils.getRandomNumberInRange(1.05f, 2.05f)
            val size = MathUtils.getRandomNumberInRange(0.55f, 1.5f)
            pool.spawn(
                Vector2f(origin.x + dx * distance, origin.y + dy * distance), angle,
                Vector2f(dx * speed, dy * speed),
                size, size, CORE, FRINGE,
                fadeIn = MathUtils.getRandomNumberInRange(0.008f, 0.035f),
                full = MathUtils.getRandomNumberInRange(0.035f, 0.11f),
                fadeOut = MathUtils.getRandomNumberInRange(0.25f, 0.48f),
            )
        }
    }

    const val FRAGMENT_SHADER_SOURCE = """
        varying vec2 v_pos;
        varying vec2 v_uv;
        uniform vec2 u_origin;
        uniform vec2 u_forward;
        uniform float u_range;
        uniform float u_halfAngle;
        uniform float u_time;

        void main() {
            vec2 relative = v_pos - u_origin;
            float along = dot(relative, u_forward);
            float across = dot(relative, vec2(-u_forward.y, u_forward.x));
            float radius = length(relative);
            float theta = atan(abs(across), max(along, 0.001));
            float angular = 1.0 - smoothstep(u_halfAngle * 0.76, u_halfAngle, theta);
            angular *= step(0.0, along);
            float age = max(u_time, 0.0);
            float front = u_range * (1.0 - exp(-6.0 * age));
            float thickness = u_range * (0.006 + 0.012 * age);
            float distance = radius - front;
            float ridgeDistance = distance / max(thickness, 0.5);
            float fringeDistance = distance / max(thickness * 5.0, 1.0);
            float ridge = exp(-ridgeDistance * ridgeDistance);
            float fringe = exp(-fringeDistance * fringeDistance);
            float innerDistance = (radius - front + u_range * 0.045) / max(thickness * 0.55, 0.5);
            float echo = exp(-innerDistance * innerDistance);
            float wake = exp(-abs(distance + u_range * 0.055) / (u_range * 0.09));
            wake *= 1.0 - smoothstep(-thickness, thickness * 2.0, distance);
            float envelope = smoothstep(0.0, 0.025, age) * (1.0 - smoothstep(0.28, 0.78, age));
            vec3 color = vec3(0.80, 0.93, 1.0) * (ridge * 1.5 + echo * 0.25);
            color += vec3(0.20, 0.53, 1.0) * (fringe * 0.6 + wake * 0.16);
            float quad = step(0.0, v_uv.x) * step(v_uv.x, 1.0) * step(0.0, v_uv.y) * step(v_uv.y, 1.0);
            gl_FragColor = vec4(color * angular * envelope * quad, 1.0);
        }
    """
}
