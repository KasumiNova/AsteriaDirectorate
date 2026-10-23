package cn.kasuminova.astd.renderer.beam.driver

import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.render.FrameStateImpl
import cn.kasuminova.astd.impl.render.RenderContextImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f

class BeamVfxDriverImpl(
    private val host: RenderHost,
    private val tree: RenderEntity,
) : BeamVfxDriver {

    private var elapsed = 0f
    private var logicElapsed = 0f
    private var lastFrame: FrameState? = null

    override var state: BeamVfxDriverState = BeamVfxDriverState.Active
        private set

    override fun advance(engine: CombatEngineAPI, frame: BeamFrame, amount: Float) {
        advanceInternal(engine, frame, amount)
    }

    override fun dispose() {
        tree.onDetach()
        state = BeamVfxDriverState.Removed
    }

    private fun advanceInternal(engine: CombatEngineAPI?, frame: BeamFrame, amount: Float) {
        if (state == BeamVfxDriverState.Removed) return
        val step = amount.coerceAtLeast(0f)
        elapsed += step
        logicElapsed += step

        val fs = buildFrame(frame, amount)
        val ctx = RenderContextImpl(engine = engine, host = host, frame = fs)
        tree.onAttach(ctx)
        tree.advance(ctx, amount)
        lastFrame = fs
    }

    private fun buildFrame(frame: BeamFrame, amount: Float): FrameState = FrameStateImpl(
        elapsed = elapsed,
        logicElapsed = logicElapsed,
        amountThisFrame = amount,
        origin = Vector2f(frame.start),
        facing = frame.facing,
        length = frame.length,
        endpoint = frame.endpoint?.let { Vector2f(it) },
        worldUnitsPerPixel = 1f,
        active = frame.firing,
        intensity = frame.strength.coerceIn(0f, 1f),
        fadeMul = frame.fadeMul.coerceIn(0f, 1f),
        phase = if (frame.firing) RenderPhase.Active else RenderPhase.FadingOut,
        flightProgress = 0f,
        dissolve = 0f,
        fadeReason = null,
        hitTarget = frame.hitTarget,
        isShieldHit = frame.isShieldHit,
    )

    internal fun advanceForTests(frame: BeamFrame, amount: Float) {
        advanceInternal(null, frame, amount)
    }

    internal fun lastFrameForTests(): FrameState? = lastFrame
}
