package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class ShardWakeComponent(
    id: String,
    internal val spec: ShardWakeSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    internal val shards = TriShardComponent(
        "${id}_shards",
        spec.shardLength,
        spec.coreColor.toAwt(),
        spec.fringeColor.toAwt(),
        TriShardSpec(
            batchCount = 1,
            sizeMul = spec.sizeMul,
            sizeMin = spec.sizeMin,
            sizeMax = spec.sizeMax,
            spinMin = spec.spinMin,
            spinMax = spec.spinMax,
            alphaLo = spec.alphaLo,
            alphaHi = spec.alphaHi,
            timerFullLo = spec.timerFullLo,
            timerFullHi = spec.timerFullHi,
            timerFadeOut = spec.timerFadeOut,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        ),
    )

    internal var accumulator = 0f
        private set

    init {
        addChild(shards)
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (ctx.frame.phase != RenderPhase.Active) return
        accumulator += amount
        if (accumulator < spec.interval) return
        accumulator = 0f
        emit(ctx)
    }

    private fun emit(ctx: RenderContext) {
        val frame = ctx.frame
        repeat(spec.perTick) {
            val pos = MathUtils.getRandomPointInCircle(frame.origin, spec.scatterRadius)
            val speed = MathUtils.getRandomNumberInRange(spec.speedMin, spec.speedMax)
            val vel = MathUtils.getPointOnCircumference(
                Vector2f(), speed, frame.facing + MathUtils.getRandomNumberInRange(-spec.spreadDeg, spec.spreadDeg),
            )
            shards.addShard(0, pos, vel)
        }
    }

    companion object {
        const val RENDER_ORDER = StaticTrailComponent.RENDER_ORDER_BASE + 6
    }
}

private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
