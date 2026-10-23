package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxDispatchState
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxLayout
import cn.kasuminova.astd.impl.render.FrameStateImpl
import cn.kasuminova.astd.impl.render.RenderContextImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ProjectileVfxDriverImpl(
    private val host: RenderHost,
    private val tree: RenderEntity,
    private val policy: ProjectileVfxDriverPolicy,
) : ProjectileVfxDriver {

    private val projectile = (host as? ProjectileHost)?.projectile

    private var elapsed = 0f
    private var fadeElapsed = 0f
    private var lastLocation: Vector2f? = null
    private var lastFrame: FrameState? = null
    private var currentFadeReason: FadeReason = FadeReason.Removed
    private var currentFadeSeconds: Float = policy.removedFadeOutSeconds
    private var testWorldUnitsPerPixel = 1f
    private val headLead: Float = policy.headLeadWorld ?: 0f

    override var state: ProjectileVfxDriverState = ProjectileVfxDriverState.Active
        private set

    override var telemetry: ProjectileVfxDriverTelemetry? = null
        private set

    override fun advance(engine: CombatEngineAPI, amount: Float) {
        val active = projectile
        if (active == null) {
            advanceInternal(engine, null, 0f, amount, false)
            return
        }
        val inPlay = engine.isEntityInPlay(active)
        val alive = inPlay && !active.isExpired && !active.isFading
        val location = if (inPlay) active.location else null
        val facing = if (inPlay) active.facing else 0f
        advanceInternal(engine, location, facing, amount, alive)
    }

    override fun dispose() {
        tree.onDetach()
        // 清理 onFire 分发标记：同场战斗弹体实例复用时旧标记会跳过新弹体分发
        projectile?.let { ProjectileVfxDispatchState.clearAll(it) }
        state = ProjectileVfxDriverState.Removed
    }

    private fun advanceInternal(
        engine: CombatEngineAPI?,
        location: Vector2f?,
        facing: Float,
        amount: Float,
        alive: Boolean,
    ) {
        if (state == ProjectileVfxDriverState.Removed) return
        elapsed += amount.coerceAtLeast(0f)

        if (state == ProjectileVfxDriverState.Active && !alive) {
            markGone(goneReason())
        }

        val fading = state == ProjectileVfxDriverState.Fading
        if (fading) fadeElapsed += amount.coerceAtLeast(0f)

        if (location != null) {
            val renderFacing = computeRenderFacing(location, facing)
            val anchor = headAnchor(location, renderFacing)
            val phase = if (fading) RenderPhase.FadingOut else RenderPhase.Active
            val frame = buildFrame(anchor, renderFacing, engine, amount, phase, if (fading) currentFadeReason else null)
            driveTree(engine, frame, amount)
            telemetry = ProjectileVfxDriverTelemetry(elapsed = elapsed)
            lastLocation = Vector2f(location)
            lastFrame = frame
        } else if (fading) {
            lastFrame?.let { frozen ->
                val faded = (frozen as? FrameStateImpl)?.copy(
                    elapsed = elapsed,
                    logicElapsed = elapsed,
                    amountThisFrame = amount,
                    active = false,
                    phase = RenderPhase.FadingOut,
                    fadeReason = currentFadeReason,
                ) ?: frozen
                driveTree(engine, faded, amount)
            }
        }

        if (fading && fadeElapsed >= currentFadeSeconds) {
            dispose()
        }
    }

    private fun markGone(reason: FadeReason) {
        if (state != ProjectileVfxDriverState.Active) return
        state = ProjectileVfxDriverState.Fading
        fadeElapsed = 0f
        currentFadeReason = reason
        currentFadeSeconds = fadeSeconds(reason)
        tree.beginFadeOut(reason, currentFadeSeconds)
    }

    private fun driveTree(engine: CombatEngineAPI?, frame: FrameState, amount: Float) {
        val ctx = RenderContextImpl(engine = engine, host = host, frame = frame)
        tree.onAttach(ctx)
        tree.advance(ctx, amount)
    }

    private fun buildFrame(
        anchor: Vector2f,
        renderFacing: Float,
        engine: CombatEngineAPI?,
        amount: Float,
        phase: RenderPhase,
        fadeReason: FadeReason?,
    ): FrameState = FrameStateImpl(
        elapsed = elapsed,
        logicElapsed = elapsed,
        amountThisFrame = amount,
        origin = Vector2f(anchor),
        facing = renderFacing,
        length = 0f,
        endpoint = null,
        worldUnitsPerPixel = referenceWorldUnitsPerPixel(engine),
        active = phase == RenderPhase.Active,
        intensity = 1f,
        phase = phase,
        flightProgress = 0f,
        dissolve = 0f,
        fadeReason = fadeReason,
    )

    private fun headAnchor(location: Vector2f, facingDeg: Float): Vector2f {
        if (headLead <= 0f) return Vector2f(location)
        val rad = Math.toRadians(facingDeg.toDouble())
        return Vector2f(
            location.x + (cos(rad) * headLead).toFloat(),
            location.y + (sin(rad) * headLead).toFloat(),
        )
    }

    private fun computeRenderFacing(location: Vector2f, projectileFacing: Float): Float {
        val previous = lastLocation ?: return projectileFacing
        val dx = location.x - previous.x
        val dy = location.y - previous.y
        if (dx * dx + dy * dy <= 0.0001f) return projectileFacing
        // 归一化到 [0,360)：BoxUtil setStateVanilla 对负角渲染异常（向下开火时 atan2 为负会导致镜像/歪斜）。
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }

    private fun goneReason(): FadeReason {
        val p = projectile ?: return FadeReason.Removed
        if (p.didDamage()) return FadeReason.Hit
        if (p.isExpired || p.isFading) return FadeReason.Expire
        return FadeReason.Removed
    }

    private fun fadeSeconds(reason: FadeReason): Float = when (reason) {
        FadeReason.Hit -> policy.hitFadeOutSeconds
        FadeReason.Expire -> policy.expireFadeOutSeconds
        FadeReason.Removed -> policy.removedFadeOutSeconds
        FadeReason.Dispose -> 0f
    }

    private fun referenceWorldUnitsPerPixel(engine: CombatEngineAPI?): Float {
        // engine != null 即战斗内，LWJGL Display 必然可用
        if (engine == null) return testWorldUnitsPerPixel
        return ASTDProjectileVfxLayout.referenceWorldUnitsPerPixel(Display.getHeight().toFloat())
    }

    internal fun advanceForTests(
        locationX: Float,
        locationY: Float,
        facing: Float,
        amount: Float,
        alive: Boolean,
        worldUnitsPerPixel: Float = 1f,
    ) {
        testWorldUnitsPerPixel = worldUnitsPerPixel
        advanceInternal(null, Vector2f(locationX, locationY), facing, amount, alive)
    }

    internal fun advanceRemovedForTests(amount: Float) {
        advanceInternal(null, null, 0f, amount, false)
    }

    internal fun lastFrameForTests(): FrameState? = lastFrame
}
