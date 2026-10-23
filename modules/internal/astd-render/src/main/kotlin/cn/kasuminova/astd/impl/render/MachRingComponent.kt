package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class MachRingComponent(
    id: String,
    internal val spec: MachRingSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    private val log = Global.getLogger(MachRingComponent::class.java)

    private val poolKey = PooledCombatVfx.SpritePoolKey(
        spec.texturePath,
        CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        glowPower = 1f,
        capacity = 256,
    )

    internal var accumulator = 0f
        private set

    private var poolFailureWarned = false

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (ctx.frame.phase != RenderPhase.Active) return
        accumulator += amount
        if (accumulator < spec.interval) return
        accumulator = 0f
        emit(ctx)
    }

    private fun emit(ctx: RenderContext) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        val lifetime = spec.lifetime
        val scaleX = spec.halfSize * spec.growthStart
        val speed = MathUtils.getRandomNumberInRange(spec.speedMin, spec.speedMax)
        val vel = MathUtils.getPointOnCircumference(Vector2f(), speed, frame.facing)
        val accepted = PooledCombatVfx.spawnSprite(
            engine = engine,
            key = poolKey,
            x = frame.origin.x,
            y = frame.origin.y,
            velX = vel.x,
            velY = vel.y,
            facingDeg = BoxUtilCombatVfx.normalizeFacingDeg(frame.facing + 90f),
            scaleX = scaleX,
            scaleY = scaleX * spec.flatten,
            scaleRateX = spec.halfSize * (spec.growthEnd - spec.growthStart) / lifetime,
            scaleRateY = spec.halfSize * (spec.growthEnd - spec.growthStart) / lifetime * spec.flatten,
            color = spec.color.toAwt(spec.alpha),
            emissiveColor = spec.color.toAwt(spec.alpha * 0.5f),
            fadeIn = spec.fadeIn,
            full = spec.full,
            fadeOut = spec.fadeOut,
        )
        if (!accepted && !poolFailureWarned) {
            poolFailureWarned = true
            log.warn("马赫环喷入粒子池失败（id=$id），后续同类失败不再重复告警")
        }
    }

    companion object {
        const val RENDER_ORDER = StaticTrailComponent.RENDER_ORDER_BASE + 7
    }
}

private fun ASTDColor.toAwt(alpha: Float): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
