package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileHistoryNode
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f

/**
 * [FrameState] 的不可变实现。驱动每帧 new 一份塞进上下文；淡出期间用 [copy] 定格几何、只推进 elapsed/phase。
 */
data class FrameStateImpl(
    override val elapsed: Float,
    override val logicElapsed: Float,
    override val amountThisFrame: Float,
    override val origin: Vector2f,
    override val facing: Float,
    override val length: Float,
    override val endpoint: Vector2f?,
    override val worldUnitsPerPixel: Float,
    override val active: Boolean,
    override val intensity: Float,
    override val phase: RenderPhase,
    override val flightProgress: Float,
    override val dissolve: Float,
    override val fadeReason: FadeReason?,
    override val historyNodes: List<ASTDProjectileHistoryNode> = emptyList(),
    // 弹体恒 1（不参与）；光束停火淡出时由 BeamVfxDriver 传入。默认 1 使弹体侧无需改动。
    override val fadeMul: Float = 1f,
    // 每帧接触数据；弹体侧与无命中时为默认（null / false）。
    override val hitTarget: CombatEntityAPI? = null,
    override val isShieldHit: Boolean = false,
) : FrameState
