package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import com.fs.starfarer.api.combat.BoundsAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object StarfallShardWake {
    private val pool = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shard_tri.png",
        layer = CombatEngineLayers.BELOW_SHIPS_LAYER,
    )
    private val blue = Color(55, 140, 255, 185)
    private val blueGlow = Color(45, 115, 255, 60)
    private val ice = Color(126, 208, 255, 205)
    private val iceGlow = Color(99, 185, 255, 72)

    fun emit(engine: CombatEngineAPI, ship: ShipAPI, dashDirection: Vector2f) {
        if (!engine.viewport.isNearViewport(ship.location, ship.collisionRadius + 200f)) return
        val bounds = ship.exactBounds
        bounds.update(ship.location, ship.facing)

        val phase = MathUtils.getRandomNumberInRange(0.01f, 0.99f)
        sampleStarfallWake(bounds.segments, ship.location, dashDirection, phase) { x, y, spread ->
            spawn(engine, x, y, dashDirection, spread)
        }
    }

    private fun spawn(engine: CombatEngineAPI, x: Float, y: Float, forward: Vector2f, spread: Float): Boolean {
        val speed = MathUtils.getRandomNumberInRange(95f, 245f)
        val lateral = speed * MathUtils.getRandomNumberInRange(-spread, spread)
        val width = MathUtils.getRandomNumberInRange(6f, 20f) * 0.67f
        val height = width * MathUtils.getRandomNumberInRange(0.65f, 1.1f)
        val bright = MathUtils.getRandomNumberInRange(0f, 1f) < 0.25f
        return PooledCombatVfx.spawnSprite(
            engine, pool, x, y,
            velX = -forward.x * speed - forward.y * lateral,
            velY = -forward.y * speed + forward.x * lateral,
            facingDeg = MathUtils.getRandomNumberInRange(0f, 360f),
            turnRateDeg = MathUtils.getRandomNumberInRange(-250f, 250f),
            scaleX = width.roundToInt() * 0.5f, scaleY = height.roundToInt() * 0.5f,
            color = if (bright) ice else blue,
            emissiveColor = if (bright) iceGlow else blueGlow,
            fadeIn = 0.025f, full = MathUtils.getRandomNumberInRange(0.14f, 0.32f), fadeOut = 0.36f,
        )
    }
}

private const val BASE_SPACING = 64f
private const val BATCH_FRACTION = 0.5f
private const val MAX_LATERAL_RATIO = 0.22f

private class WakeEdge(
    val x: Float, val y: Float, val unitX: Float, val unitY: Float,
    val arcStart: Float, val spread: Float,
)

internal fun sampleStarfallWake(
    segments: List<BoundsAPI.SegmentAPI>, center: Vector2f, forward: Vector2f, phase: Float,
    emit: (x: Float, y: Float, spread: Float) -> Boolean,
) {
    var signedArea = 0f
    for (edge in segments) {
        val a = edge.p1
        val b = edge.p2
        signedArea += (a.x - center.x) * (b.y - center.y) - (b.x - center.x) * (a.y - center.y)
    }
    val winding = if (signedArea > 0f) 1f else -1f
    val rear = ArrayList<WakeEdge>()
    var perimeter = 0f
    var rearLength = 0f
    for (edge in segments) {
        val a = edge.p1
        val b = edge.p2
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = sqrt(dx * dx + dy * dy)
        perimeter += length
        val outward = winding * (dx * forward.y - dy * forward.x)
        if (outward <= 0f) continue

        val from = (a.x - center.x) * forward.x + (a.y - center.y) * forward.y
        val to = (b.x - center.x) * forward.x + (b.y - center.y) * forward.y
        if (from >= 0f && to >= 0f) continue
        val t0 = if (from > 0f) from / (from - to) else 0f
        val t1 = if (to > 0f) from / (from - to) else 1f

        // 限制近切线方向的横向速度，使散射后的速度在外法线上的投影仍为正。
        val tangent = abs(dx * forward.x + dy * forward.y)
        val spread = if (tangent == 0f) MAX_LATERAL_RATIO else min(MAX_LATERAL_RATIO, 0.95f * outward / tangent)
        rear += WakeEdge(a.x + dx * t0, a.y + dy * t0, dx / length, dy / length, rearLength, spread)
        rearLength += (t1 - t0) * length
    }
    val count = ((perimeter / BASE_SPACING).roundToInt() * BATCH_FRACTION).roundToInt()
    if (rear.isEmpty() || count == 0) return
    val spacing = rearLength / count
    var edgeIndex = 0
    repeat(count) { i ->
        val distance = (i + phase) * spacing
        while (edgeIndex < rear.lastIndex && rear[edgeIndex + 1].arcStart <= distance) edgeIndex++
        val edge = rear[edgeIndex]
        val offset = distance - edge.arcStart
        if (!emit(edge.x + edge.unitX * offset, edge.y + edge.unitY * offset, edge.spread)) return
    }
}
