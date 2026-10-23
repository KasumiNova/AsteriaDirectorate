package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderEntity
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

data class ConeImpactVfxSpec(
    val origin: Vector2f,

    val facingDeg: Float,

    val halfAngleDeg: Float,

    val length: Float,

    val coreColor: Color,

    val fringeColor: Color,

    val flashColor: Color = coreColor,

    val duration: Float = 0.45f,

    val expandSeconds: Float = 0.14f,

    val fadeOutSeconds: Float = 0.22f,

    val distortion: DistortionSpec? = DistortionSpec(),

    val shardCounts: ShardCountSpec = ShardCountSpec(),
)

data class DistortionSpec(
    val fadeIn: Float = 0.03f,

    val full: Float = 0.05f,

    val fadeOut: Float = 0.18f,

    val sizeIn: Float = 16f,

    val sizeFull: Float = 52f,

    val sizeOut: Float = 96f,

    val powerFull: Float = 0.34f,
)

data class ShardCountSpec(
    val vertex: Int = 6,

    val cone: Int = 8,

    val edge: Int = 4,
)

object ConeImpactVfx {
    private val log = Global.getLogger(ConeImpactVfx::class.java)

    private const val MIN_DRIVER_TTL = 0.70f

    fun spawn(engine: CombatEngineAPI, spec: ConeImpactVfxSpec): OneShotVfxPlugin? {
        if (spec.length.isNaN() || spec.length <= 0f) {
            log.warn("锥面冲击特效 length 非正（${spec.length}），属配置错误，本次不生成特效")
            return null
        }
        if (spec.duration.isNaN() || spec.duration <= 0f) {
            log.warn("锥面冲击特效 duration 非正（${spec.duration}），属配置错误，本次不生成特效")
            return null
        }

        var halfAngle = spec.halfAngleDeg
        if (halfAngle.isNaN() || halfAngle <= 0f || halfAngle > 90f) {
            val clamped = if (halfAngle.isNaN()) 30f else halfAngle.coerceIn(1f, 90f)
            log.warn("锥面冲击特效 halfAngleDeg 越界（${spec.halfAngleDeg}），clamp 到 $clamped")
            halfAngle = clamped
        }
        if (spec.expandSeconds.isNaN() || spec.expandSeconds <= 0f || spec.expandSeconds > spec.duration) {
            log.warn("锥面冲击特效 expandSeconds 越界（${spec.expandSeconds}），v2 视觉层未消费该字段，请检查配置")
        }
        if (spec.fadeOutSeconds.isNaN() || spec.fadeOutSeconds <= 0f || spec.fadeOutSeconds >= spec.duration) {
            log.warn("锥面冲击特效 fadeOutSeconds 越界（${spec.fadeOutSeconds}），v2 视觉层未消费该字段，请检查配置")
        }

        val distortion = spec.distortion?.let { d ->
            val clean = d.copy(
                fadeIn = d.fadeIn.coerceAtLeast(0f),
                full = d.full.coerceAtLeast(0f),
                fadeOut = d.fadeOut.coerceAtLeast(0f),
                sizeIn = d.sizeIn.coerceAtLeast(0f),
                sizeFull = d.sizeFull.coerceAtLeast(0f),
                sizeOut = d.sizeOut.coerceAtLeast(0f),
                powerFull = d.powerFull.coerceAtLeast(0f),
            )
            if (clean != d) log.warn("锥面冲击特效 distortion 含负值（$d），已逐项 clamp 到 0")
            clean
        }
        val shardCounts = spec.shardCounts.let { s ->
            val clean = s.copy(
                vertex = s.vertex.coerceAtLeast(0),
                cone = s.cone.coerceAtLeast(0),
                edge = s.edge.coerceAtLeast(0),
            )
            if (clean != s) log.warn("锥面冲击特效 shardCounts 含负值（$s），已逐项 clamp 到 0")
            clean
        }

        val tree: RenderEntity = ConeImpactVfxComponent(
            id = "cone_impact_vfx@" + System.identityHashCode(spec),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
            halfAngleDeg = halfAngle,
            length = spec.length,
            coreColor = spec.coreColor,
            fringeColor = spec.fringeColor,
            flashColor = spec.flashColor,
            distortion = distortion,
            shardCounts = shardCounts,
        )
        val host = PointHost(
            hostId = "cone@" + System.identityHashCode(tree),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
        )
        // 树内组件 detach 即摘除，驱动寿命必须覆盖最长期子组件，否则提前掐掉碎片/针/弧
        val plugin = OneShotVfxPlugin(engine, host, tree, maxOf(spec.duration + 0.15f, MIN_DRIVER_TTL))
        engine.addPlugin(plugin)
        return plugin
    }
}
