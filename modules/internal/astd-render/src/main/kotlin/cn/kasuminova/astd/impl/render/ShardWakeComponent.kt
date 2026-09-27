package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 三角碎片航迹发射器组件（持续型，spec 见 [ShardWakeSpec]）：弹体 Active 期间按节拍向
 * 子节点 [TriShardComponent] 累积一撮碎片（发射点绕弹体位置 [ShardWakeSpec.scatterRadius]
 * 散布，速度沿飞行方向 ±[ShardWakeSpec.spreadDeg]），碎片渲染走统一粒子池。
 *
 * 节拍语义与旧 .wpn EveryFrame 实现逐帧一致（reset-to-0，满一拍即喷、余量清零）；
 * 弹体淡出/移除（phase != Active）即停喷，已喷碎片由池包络自然寿终，无需消亡移交。
 */
class ShardWakeComponent(
    id: String,
    internal val spec: ShardWakeSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    /** 碎片累积子节点（internal 供单测断言发射参数域）；advance 由基类递归驱动灌批。 */
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

    /** 节拍累积器（internal 供单测断言节拍语义）。 */
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

    /** 喷一撮碎片：弹体位置小散布，统一向飞行方向慢飞。 */
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
        /** 发射器绘制序：组件自身不绘制（子节点灌池），占位在拖尾叠层与光斑之间。 */
        const val RENDER_ORDER = StaticTrailComponent.RENDER_ORDER_BASE + 6
    }
}

/** ASTDColor（0..1 浮点）→ awt Color（0..255）。 */
private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
