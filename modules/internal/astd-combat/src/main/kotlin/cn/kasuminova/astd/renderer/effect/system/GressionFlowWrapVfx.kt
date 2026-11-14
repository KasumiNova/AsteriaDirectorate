package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.impl.render.ShaderCompatibility
import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectKey
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.base.ShaderMaterialSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderProgramSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderTextureSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformDefinition
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSchema
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSet
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformType
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformValue
import cn.kasuminova.astd.renderer.shader.domain.ShipSystemShaderEffect
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object GressionFlowWrapVfx {
    const val FLOW_TEXTURE = "graphics/fx/astd_gression_flow.png"
    const val MIST_TEXTURE = "graphics/fx/astd_gression_mist.png"
    private const val TRACKER_KEY = "astd_gression_flow_wrap"
    private const val FADE_OUT = 0.24f

    fun preloadTextures() {
        for (path in listOf(FLOW_TEXTURE, MIST_TEXTURE)) {
            try {
                Global.getSettings().loadTexture(path)
            } catch (e: Exception) {
                AstdLog.logger.warn("[ASTD] 贯虹突进流纹预加载失败（path=$path）", e)
            }
        }
    }

    fun ensure(engine: CombatEngineAPI, ship: ShipAPI) {
        val key = "$TRACKER_KEY.${ship.id}"
        if (engine.customData[key] is WrapTracker) return
        val tracker = WrapTracker(engine, ship, key)
        engine.customData[key] = tracker
        engine.addPlugin(tracker)
    }

    private class WrapTracker(
        private val engine: CombatEngineAPI,
        private val ship: ShipAPI,
        private val key: String,
    ) : BaseEveryFrameCombatPlugin() {
        private val sink = CombatShaderRuntime.ensure(engine).sink
        private val flow = WrapLayer(false, ship.collisionRadius)
        private val mist = WrapLayer(true, ship.collisionRadius)
        private var flowHandle: ShaderHandle? = null
        private var mistHandle: ShaderHandle? = null
        private var alpha = 0f
        private var extension = 0f
        private var phase = 0f

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            if (ship.isHulk || !engine.isEntityInPlay(ship)) {
                remove()
                return
            }
            val system = ship.system
            val active = system?.state == ShipSystemAPI.SystemState.IN ||
                system?.state == ShipSystemAPI.SystemState.ACTIVE
            if (active) {
                alpha = if (system!!.state == ShipSystemAPI.SystemState.ACTIVE) 1f else system.effectLevel.coerceIn(0f, 1f)
                extension = alpha
            } else {
                alpha = max(0f, alpha - amount / FADE_OUT)
                if (alpha == 0f) {
                    remove()
                    return
                }
            }
            phase = (phase + amount) % 16f
            flow.phase = phase
            mist.phase = phase
            flowHandle = flow.submit(engine, sink, ship, extension, alpha)
            mistHandle = mist.submit(engine, sink, ship, extension, alpha)
        }

        private fun remove() {
            flowHandle?.let(sink::remove)
            mistHandle?.let(sink::remove)
            engine.customData.remove(key)
            engine.removePlugin(this)
        }
    }

    private class WrapLayer(private val outer: Boolean, private val radius: Float) : ShipSystemShaderEffect {
        var phase = 0f
        override val effectSpec = ShaderEffectSpec(
            id = ShaderEffectKey(if (outer) "gression_flow_mist" else "gression_flow_core"),
            program = ShaderProgramSpec("gression_flow_wrap", ShaderCompatibility.VERTEX_SHADER_SOURCE, FRAGMENT_SHADER_SOURCE),
            geometry = ShaderGeometrySpec.WorldQuad(radius * 2f),
            material = ShaderMaterialSpec(ShaderBlendMode.Additive, ShaderTextureSpec(if (outer) MIST_TEXTURE else FLOW_TEXTURE)),
            uniformSchema = SCHEMA,
            layer = if (outer) ShaderEffectLayer.BelowParticles else ShaderEffectLayer.AboveShips,
            lifetimeSeconds = 1f,
            staleAfterSeconds = 0.5f,
            renderRadius = radius * 2f,
        )

        override fun submit(
            engine: CombatEngineAPI,
            sink: ShaderSink,
            ship: ShipAPI,
            effectLevel: Float,
            intensity: Float,
        ): ShaderHandle {
            val angle = Math.toRadians(ship.facing.toDouble())
            val forwardX = cos(angle).toFloat()
            val forwardY = sin(angle).toFloat()
            val tipX = ship.location.x + forwardX * radius * 1.07f
            val tipY = ship.location.y + forwardY * radius * 1.07f
            val extension = 0.72f + 0.28f * effectLevel
            val values = mapOf(
                "tip" to ShaderUniformValue.Vec2(tipX, tipY),
                "forward" to ShaderUniformValue.Vec2(forwardX, forwardY),
                "length" to ShaderUniformValue.FloatValue(radius * (if (outer) 2.06f else 2.55f) * extension),
                "width" to ShaderUniformValue.FloatValue(radius * (if (outer) 2.41f else 1.99f) * extension),
                "repeat" to ShaderUniformValue.FloatValue(if (outer) 1f else 2f),
                "flow" to ShaderUniformValue.FloatValue(phase * (if (outer) 0.5f else 1.25f)),
                "alphaMult" to ShaderUniformValue.FloatValue(intensity * (if (outer) 0.9f else 1.15f)),
                "edgeSoft" to ShaderUniformValue.FloatValue(0.12f),
                "tipFade" to ShaderUniformValue.FloatValue(0.055f),
                "tailFade" to ShaderUniformValue.FloatValue(if (outer) 0.38f else 0.32f),
                "color" to if (outer) ShaderUniformValue.Vec4(0.55f, 0.80f, 1f, 1f) else ShaderUniformValue.Vec4(0.88f, 0.95f, 1f, 1f),
            )
            return sink.upsert(effectSpec, ship.id, Vector2f(ship.location), ship.facing, ShaderUniformSet(SCHEMA, values))
        }
    }

    private val SCHEMA = ShaderUniformSchema(
        listOf(
            ShaderUniformDefinition("tip", ShaderUniformType.Vec2),
            ShaderUniformDefinition("forward", ShaderUniformType.Vec2),
            ShaderUniformDefinition("color", ShaderUniformType.Vec4),
        ) + listOf("length", "width", "repeat", "flow", "alphaMult", "edgeSoft", "tipFade", "tailFade")
            .map { ShaderUniformDefinition(it, ShaderUniformType.Float) },
    )

    const val FRAGMENT_SHADER_SOURCE = """
        varying vec2 v_pos;
        varying vec2 v_uv;
        uniform sampler2D u_texture;
        uniform vec2 u_tip;
        uniform vec2 u_forward;
        uniform float u_length;
        uniform float u_width;
        uniform float u_repeat;
        uniform float u_flow;
        uniform float u_alphaMult;
        uniform float u_edgeSoft;
        uniform float u_tipFade;
        uniform float u_tailFade;
        uniform vec4 u_color;

        void main() {
            vec2 relative = v_pos - u_tip;
            float behind = -dot(relative, u_forward);
            float across = dot(relative, vec2(-u_forward.y, u_forward.x));
            float t = sqrt(clamp(behind / u_length, 0.0, 1.0));
            float halfWidth = max(0.5 * u_width * t, 0.5);
            float side = abs(across) / halfWidth;
            float v = 0.5 + across / (2.0 * halfWidth);
            float u = t * u_repeat - u_flow;
            float edge = 1.0 - smoothstep(1.0 - u_edgeSoft, 1.0, side);
            float head = smoothstep(0.0, u_tipFade, t);
            float tail = 1.0 - smoothstep(1.0 - u_tailFade, 1.0, t);
            float mask = edge * head * tail * step(0.0, behind) * step(behind, u_length);
            float stream = texture2D(u_texture, vec2(u, clamp(v, 0.0, 1.0))).a * mask;
            float quadEdge = step(0.0, v_uv.x) * step(v_uv.x, 1.0) * step(0.0, v_uv.y) * step(v_uv.y, 1.0);
            float alpha = clamp(stream * u_alphaMult * u_color.a, 0.0, 1.0) * quadEdge;
            gl_FragColor = vec4(u_color.rgb, alpha);
        }
    """
}
