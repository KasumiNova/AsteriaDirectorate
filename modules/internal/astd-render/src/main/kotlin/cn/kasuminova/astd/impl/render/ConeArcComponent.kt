package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.impl.render.ConeArcComponent.Companion.ARC_EMISSIVE_GAIN
import cn.kasuminova.astd.impl.render.ConeArcComponent.Companion.B_RATIO
import cn.kasuminova.astd.impl.render.ConeArcComponent.Companion.CENTER_DEG
import cn.kasuminova.astd.impl.render.ConeArcComponent.Companion.LINE_WIDTHS_PX
import cn.kasuminova.astd.impl.render.ConeArcComponent.Companion.NODE_COUNT
import cn.kasuminova.astd.impl.render.ConeArcComponent.Companion.SWEEP_DEG
import cn.kasuminova.astd.impl.render.ConeArcComponent.Companion.VIEW_MULT_PX_PER_SU
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLease
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseEnvelope
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class ConeArcComponent(
    id: String,
    private val origin: Vector2f,
    facingDeg: Float,
    halfAngleDeg: Float,
    length: Float,
    private val fringeColor: Color,
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(ConeArcComponent::class.java)

    internal val facingDeg: Float = BoxUtilCombatVfx.normalizeFacingDeg(facingDeg)

    internal val arcs = ArrayList<Arc>(ARC_COUNT)

    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var spritesAttempted = false
    private var driveWarned = false

    init {
        for (i in 0 until ARC_COUNT) {
            val dist = length * AXIS_FRACS[i]
            val center = MathUtils.getPointOnCircumference(origin, dist, facingDeg)
            val aSide = dist * tan(Math.toRadians(halfAngleDeg.toDouble())).toFloat() * SIDE_MUL
            arcs += Arc(
                delay = DELAYS[i],
                duration = DURATIONS[i],
                center = center,
                aSide0 = aSide,
                bAlong0 = aSide * B_RATIO,
                expandSpeed = EXPAND_SPEEDS[i],
                widthSu = WIDTHS_SU[i],
                alphaNorm = ALPHAS[i] / 255f,
            )
        }
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val dt = amount.coerceAtLeast(0f)
        for (arc in arcs) {
            arc.elapsed += dt
            if (!arc.activated) {
                if (arc.elapsed < arc.delay) continue
                activateArc(engine, arc)
            }
            val entity = arc.entity ?: continue
            if (entity.hasDelete()) {
                arc.entity = null
                arc.lease = null
                continue
            }
            val driveAge = arc.elapsed - arc.delay
            if (driveAge >= arc.duration) continue
            try {
                rewriteNodes(arc, entity, driveAge)
            } catch (t: Throwable) {
                if (!driveWarned) {
                    driveWarned = true
                    log.warn("锥面弧曲梁节点推送异常（id=$id），本组件后续推送静默（组件级去重）", t)
                }
            }
        }
    }

    override fun onDetachSelf() {
        for (arc in arcs) {
            // release() 为泊车归还（alpha 归零、实体留池复用），禁止走 delete
            arc.lease?.release()
            arc.lease = null
            arc.entity = null
        }
    }

    private fun rewriteNodes(arc: Arc, entity: TrailEntity, driveAge: Float) {
        val a = expandedHalfAxis(arc.aSide0, arc.expandSpeed, driveAge)
        val b = expandedHalfAxis(arc.bAlong0, arc.expandSpeed, driveAge)
        val nodes = entity.nodes
        for (i in UNIT_NODES.indices) {
            val u = UNIT_NODES[i]
            nodes[i].set(b * u.x, a * u.y)
        }
        entity.setNodeRefreshIndex(0)
        entity.setNodeRefreshAllFromCurrentIndex()
        entity.submitNodes()
    }

    private fun activateArc(engine: CombatEngineAPI, arc: Arc) {
        arc.activated = true
        loadSprites() ?: return
        val lease = PooledCombatVfx.checkoutTrail(
            engine, POOL_KEY,
            TrailLeaseSpec(
                location = arc.center,
                facingDeg = facingDeg,
                nodes = UNIT_NODES.map { Vector2f(arc.bAlong0 * it.x, arc.aSide0 * it.y) },
                startWidth = arc.widthSu,
                endWidth = arc.widthSu,
                coreColor = fringeColor,
                fringeColor = fringeColor,
                startAlpha = arc.alphaNorm,
                endAlpha = arc.alphaNorm,
                startEmissiveAlpha = arc.alphaNorm * ARC_EMISSIVE_GAIN,
                endEmissiveAlpha = arc.alphaNorm * ARC_EMISSIVE_GAIN,
                fillStartAlpha = 0f,
                fillStartFactor = FILL_START_FACTOR,
                fillEndAlpha = 0f,
                fillEndFactor = FILL_END_FACTOR,
                scale = UNIT_SCALE,
                envelope = TrailLeaseEnvelope(0f, 0f, arc.duration),
            ),
        )
        if (lease == null) return
        arc.lease = lease
        arc.entity = lease.entity
    }

    private fun loadSprites(): Pair<SpriteAPI, SpriteAPI>? {
        if (!spritesAttempted) {
            spritesAttempted = true
            sprites = BeamSprites.load()
            if (sprites == null) {
                log.warn("锥面弧曲梁贴图加载失败（id=$id），本组件弧视觉缺席")
            }
        }
        return sprites
    }

    internal class Arc(
        val delay: Float,
        val duration: Float,
        val center: Vector2f,
        val aSide0: Float,
        val bAlong0: Float,
        val expandSpeed: Float,
        val widthSu: Float,
        val alphaNorm: Float,
        var elapsed: Float = 0f,
        var activated: Boolean = false,
        var lease: TrailLease? = null,
        var entity: TrailEntity? = null,
    )

    companion object {
        const val ARC_COUNT = 4
        val DELAYS = floatArrayOf(0.03f, 0.07f, 0.11f, 0.15f)

        val AXIS_FRACS = floatArrayOf(0.3f, 0.5f, 0.7f, 0.9f)
        val DURATIONS = floatArrayOf(0.22f, 0.20f, 0.18f, 0.16f)

        val EXPAND_SPEEDS = floatArrayOf(260f, 287f, 313f, 340f)

        val ALPHAS = intArrayOf(120, 95, 75, 55)

        val LINE_WIDTHS_PX = floatArrayOf(12f, 11f, 10f, 10f)

        const val VIEW_MULT_PX_PER_SU = 1.5637f

        internal val WIDTHS_SU = FloatArray(ARC_COUNT) { LINE_WIDTHS_PX[it] / VIEW_MULT_PX_PER_SU }

        const val SIDE_MUL = 0.85f
        const val B_RATIO = 0.5f

        const val SWEEP_DEG = 130f
        const val CENTER_DEG = 90f
        const val NODE_COUNT = 20

        const val ALPHA_PEAK_POS = 0.65f
        const val FILL_START_FACTOR = 1f - ALPHA_PEAK_POS
        const val FILL_END_FACTOR = ALPHA_PEAK_POS

        const val ARC_EMISSIVE_GAIN = 1.0f

        private const val ARC_MIX_FACTOR = 1.0f

        internal val POOL_KEY = TrailLeaseKey(
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            coreSpritePath = BeamSprites.CORE_PATH,
            fringeSpritePath = BeamSprites.FRINGE_PATH,
            mixPower = ARC_MIX_FACTOR,
            capacity = 48,
        )

        private val UNIT_SCALE = Vector2f(1f, 1f)

        internal val UNIT_NODES: List<Vector2f> = unitArcNodes()

        internal fun expandedHalfAxis(half0: Float, expandSpeed: Float, ageSeconds: Float): Float =
            half0 + expandSpeed * ageSeconds.coerceAtLeast(0f)

        internal fun unitArcNodes(): List<Vector2f> {
            val startDeg = CENTER_DEG - SWEEP_DEG * 0.5f
            val step = SWEEP_DEG / (NODE_COUNT - 1).toFloat()
            return (0 until NODE_COUNT).map { i ->
                val rad = Math.toRadians((startDeg + step * i).toDouble())
                Vector2f(sin(rad).toFloat(), cos(rad).toFloat())
            }
        }
    }
}
