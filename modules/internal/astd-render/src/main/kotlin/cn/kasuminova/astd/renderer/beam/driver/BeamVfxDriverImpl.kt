package cn.kasuminova.astd.renderer.beam.driver

import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.render.FrameStateImpl
import cn.kasuminova.astd.impl.render.RenderContextImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f

/**
 * [BeamVfxDriver] 实现。极薄：每帧把 [BeamFrame] 折成宿主中立的 [FrameState]（origin=start / endpoint /
 * active=firing / intensity=strength），再 onAttach + advance 一棵 [RenderEntity] 树。几何不自算（宿主喂入），
 * 淡出不由驱动承担（束体节点按 active 走心跳自淡/复火重建），故无飞行历史、无飞行布局、无单向 fade 状态机。
 */
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
        // 光束节点直接工作在世界单位（束几何本就是世界坐标），无弹体那套像素→世界缩放，恒 1。
        worldUnitsPerPixel = 1f,
        active = frame.firing,
        intensity = frame.strength.coerceIn(0f, 1f),
        fadeMul = frame.fadeMul.coerceIn(0f, 1f),
        phase = if (frame.firing) RenderPhase.Active else RenderPhase.FadingOut,
        flightProgress = 0f,
        dissolve = 0f,
        // 淡出由束体节点按 active 自管（心跳），非驱动单向 fade，故不带 fadeReason。
        fadeReason = null,
        historyNodes = emptyList(),
        hitTarget = frame.hitTarget,
        isShieldHit = frame.isShieldHit,
    )

    /** 测试入口：无引擎推进一帧（束体节点 onAttach 返回 false、不建 BoxUtil），验证 BeamFrame→FrameState 映射与生命周期。 */
    internal fun advanceForTests(frame: BeamFrame, amount: Float) {
        advanceInternal(null, frame, amount)
    }

    internal fun lastFrameForTests(): FrameState? = lastFrame
}
