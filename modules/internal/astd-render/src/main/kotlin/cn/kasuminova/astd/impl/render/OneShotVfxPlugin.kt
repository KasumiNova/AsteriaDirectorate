package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f

class PointHost(
    override val hostId: String,
    val origin: Vector2f,
    val facingDeg: Float,
) : RenderHost

class OneShotVfxPlugin(
    private val engine: CombatEngineAPI,
    private val host: PointHost,
    private val tree: RenderEntity,
    private val durationSeconds: Float,
) : BaseEveryFrameCombatPlugin() {

    private var elapsed = 0f
    private var finished = false

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (finished) return
        if (engine.isPaused) return
        val step = amount.coerceAtLeast(0f)
        elapsed += step

        if (elapsed >= durationSeconds) {
            finished = true
            tree.onDetach()
            engine.removePlugin(this)
            return
        }

        val ctx = RenderContextImpl(engine = engine, host = host, frame = buildFrame(step))
        tree.onAttach(ctx)
        tree.advance(ctx, step)
    }

    private fun buildFrame(amount: Float): FrameStateImpl = FrameStateImpl(
        elapsed = elapsed,
        logicElapsed = elapsed,
        amountThisFrame = amount,
        origin = Vector2f(host.origin),
        facing = host.facingDeg,
        length = 0f,
        endpoint = null,
        worldUnitsPerPixel = 1f,
        active = true,
        intensity = 1f,
        phase = RenderPhase.Active,
        flightProgress = 0f,
        dissolve = 0f,
        fadeReason = null,
    )

    internal fun elapsedForTests(): Float = elapsed

    internal fun isFinishedForTests(): Boolean = finished
}
