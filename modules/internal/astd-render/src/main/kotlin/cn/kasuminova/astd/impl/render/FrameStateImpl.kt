package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f

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
    override val fadeMul: Float = 1f,
    override val hitTarget: CombatEntityAPI? = null,
    override val isShieldHit: Boolean = false,
) : FrameState
