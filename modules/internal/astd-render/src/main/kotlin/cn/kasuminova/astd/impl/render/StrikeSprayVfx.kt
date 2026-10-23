package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.StrikeSprayVfx.spawnSpray
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

object StrikeSprayVfx {
    private val log = Global.getLogger(StrikeSprayVfx::class.java)

    private const val MIN_DRIVER_TTL = 0.70f

    private const val DRIVER_TAIL_MARGIN = 0.15f

    enum class FacingMode {
        OUTWARD,
        INWARD,
    }

    data class SmokeStyle(
        val puffCountBase: Int = 6,
        val puffCountExtra: Int = 4,
        val spreadArc: Float = 28f,
        val sizeMin: Float = 60f,
        val sizeMax: Float = 120f,
        val speedMin: Float = 80f,
        val speedMax: Float = 180f,
        val durationMin: Float = 0.45f,
        val durationMax: Float = 0.85f,
        val endSizeMult: Float = 1.35f,
    )

    data class SprayStyle(
        val baseRaysMin: Int = 22,
        val baseRaysExtra: Int = 9,
        val arc: Float = 65f,
        val lengthMin: Float = 140f,
        val lengthMax: Float = 310f,
        val widthMin: Float = 12.0f,
        val widthMax: Float = 24.0f,
        val fullMin: Float = 0.06f,
        val fullMax: Float = 0.12f,
        val fadeOutMin: Float = 0.44f,
        val fadeOutMax: Float = 0.64f,
        val speedMin: Float = 240f,
        val speedMax: Float = 560f,
        val impactScale: Float = 1f,
        val introRampSeconds: Float = 0.07f,
        val exactRays: Int? = null,
    )

    data class StrikeSpraySpec(
        val origin: Vector2f,

        val facingDeg: Float,

        val arcDeg: Float,

        val coreColor: Color,

        val fringeColor: Color,

        val style: SprayStyle,
    )

    fun spawnSpray(engine: CombatEngineAPI, spec: StrikeSpraySpec): OneShotVfxPlugin? {
        if (spec.arcDeg.isNaN() || spec.arcDeg <= 0f) {
            log.warn("刺束喷散 arcDeg 非正（${spec.arcDeg}），属配置错误，本次不生成特效")
            return null
        }
        val style = spec.style
        if (style.lengthMax <= 0f || style.lengthMin <= 0f || style.widthMin <= 0f || style.speedMin <= 0f) {
            log.warn("刺束喷散显式参数组存在非正区间端点（$style），属配置错误，本次不生成特效")
            return null
        }

        val tree: RenderEntity = StrikeSprayComponent(
            id = "strike_spray@" + System.identityHashCode(spec),
            spec = spec,
        )
        val host = PointHost(
            hostId = "spray@" + System.identityHashCode(tree),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
        )
        // 针由树内组件逐帧推进、detach 即摘除，驱动寿命必须覆盖最迟激活 + 针最长寿命，否则提前掐掉
        val ttl = (style.introRampSeconds + style.fullMax + style.fadeOutMax + DRIVER_TAIL_MARGIN).coerceAtLeast(MIN_DRIVER_TTL)
        val plugin = OneShotVfxPlugin(engine, host, tree, ttl)
        engine.addPlugin(plugin)
        return plugin
    }

    fun spawnSmoke(
        engine: CombatEngineAPI,
        point: Vector2f,
        facingDeg: Float,
        smokeColor: Color,
        style: SmokeStyle = SmokeStyle(),
    ) {
        val puffs = (style.puffCountBase + MathUtils.getRandomNumberInRange(0, style.puffCountExtra)).coerceAtLeast(1)
        for (i in 0 until puffs) {
            val ang = facingDeg + MathUtils.getRandomNumberInRange(-style.spreadArc * 0.5f, style.spreadArc * 0.5f)
            val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(style.speedMin, style.speedMax), ang)
            val loc = MathUtils.getRandomPointInCircle(point, 8f)
            engine.addNebulaParticle(
                loc,
                v,
                MathUtils.getRandomNumberInRange(style.sizeMin, style.sizeMax),
                style.endSizeMult,
                0.08f,
                0.28f,
                MathUtils.getRandomNumberInRange(style.durationMin, style.durationMax),
                smokeColor,
                true,
            )
        }
    }

    fun spawnImpactFx(
        engine: CombatEngineAPI,
        point: Vector2f,
        towardTargetFacing: Float,
        facingMode: FacingMode,
        smokeColor: Color,
        coreColor: Color,
        fringeColor: Color,
        sprayStyle: SprayStyle = SprayStyle(),
        smokeStyle: SmokeStyle = SmokeStyle(),
    ): OneShotVfxPlugin? {
        val facing = computeImpactFxFacing(towardTargetFacing, facingMode)
        spawnSmoke(engine, point, facing, smokeColor, smokeStyle)
        return spawnSpray(
            engine,
            StrikeSpraySpec(
                origin = Vector2f(point),
                facingDeg = facing,
                arcDeg = sprayStyle.arc,
                coreColor = coreColor,
                fringeColor = fringeColor,
                style = sprayStyle,
            ),
        )
    }

    private fun computeImpactFxFacing(towardTargetFacing: Float, mode: FacingMode): Float {
        val f = ((towardTargetFacing % 360f) + 360f) % 360f
        return when (mode) {
            FacingMode.OUTWARD -> (f + 180f) % 360f
            FacingMode.INWARD -> f
        }
    }
}
