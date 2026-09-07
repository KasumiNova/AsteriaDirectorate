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

/**
 * [ProjectileVfxDriver] 实现：每帧产出宿主中立的 [FrameState] 并推进一棵 [RenderEntity] 树的生命周期。
 *
 * Static Trail 迁移（2026-09）后，拖尾的采样/寿命/几何/消亡播完由 BoxUtil Static Trail 系统托管
 * （见 StaticTrailComponent/ASTDProjectileTrailTracker）；本驱动只保留：存活/消亡状态机、树淡出
 * （boxFlare 等附加层的淡出包络）、headLead 锚点与渲染朝向。
 */
class ProjectileVfxDriverImpl(
    private val host: RenderHost,
    private val tree: RenderEntity,
    private val policy: ProjectileVfxDriverPolicy,
) : ProjectileVfxDriver {

    /** 弹体宿主时的原生弹体；非弹体宿主（如测试）为 null，存活判定改由调用方给出。 */
    private val projectile = (host as? ProjectileHost)?.projectile

    private var elapsed = 0f
    private var fadeElapsed = 0f
    private var lastLocation: Vector2f? = null
    private var lastFrame: FrameState? = null
    private var currentFadeReason: FadeReason = FadeReason.Removed
    private var currentFadeSeconds: Float = policy.removedFadeOutSeconds
    /** 测试用尺度注入（无引擎时 referenceWorldUnitsPerPixel 的返回值）。 */
    private var testWorldUnitsPerPixel = 1f
    /**
     * 树锚点前移量（世界单位）：策略显式值优先，否则 0——弹体 location 即螺栓视觉头部，
     * 附加层（光斑）锚点默认压在螺栓头部。
     */
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
        // 弹体仍在场（含超射程后的 fadeTime 滑行）→ 取实时位置；彻底移除后传 null → 几何冻结。
        val inPlay = engine.isEntityInPlay(active)
        // 一旦进入消亡（超射程 → isExpired/isFading）即视为不再存活，立刻转入淡出。原版在 fadeTime 窗口内仍
        // isEntityInPlay=true，若只看 isEntityInPlay，附加层会在越过射程环后满亮驻留一段才消失（无淡出观感）。
        // 命中（didDamage）走 isEntityInPlay 的移除路径即可，不在此纳入，避免误伤"击中后继续飞行"的穿透弹。
        val alive = inPlay && !active.isExpired && !active.isFading
        val location = if (inPlay) active.location else null
        val facing = if (inPlay) active.facing else 0f
        advanceInternal(engine, location, facing, amount, alive)
    }

    override fun dispose() {
        tree.onDetach()
        // 生命周期结束：清理 onFire 分发标记，避免同一场战斗内弹体实例复用时新弹体被旧标记跳过分发。
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
            // 存活，或淡出期弹体仍在场滑行：跟随实时位置——与原版弹体在 fadeTime 窗口内继续移动一致，
            // 淡出叠加在跟随之上，而非把特效钉死在死亡前一帧。
            val renderFacing = computeRenderFacing(location, facing)
            // 附加层树锚在弹体前端（location = 螺栓视觉头部）沿朝向提前 headLead。
            val anchor = headAnchor(location, renderFacing)
            val phase = if (fading) RenderPhase.FadingOut else RenderPhase.Active
            val frame = buildFrame(anchor, renderFacing, engine, amount, phase, if (fading) currentFadeReason else null)
            driveTree(engine, frame, amount)
            telemetry = ProjectileVfxDriverTelemetry(elapsed = elapsed)
            lastLocation = Vector2f(location)
            lastFrame = frame
        } else if (fading) {
            // 弹体已彻底移除（无实时位置）：几何冻结在最后一帧，附加层按自身淡出包络收完。
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

    /** 树锚点：弹体前端（location）沿渲染朝向提前 [headLead]；headLead ≤ 0 时原样返回 location。 */
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
        // engine != null 即战斗内，LWJGL Display 必然可用；Layout 内部对高度做 coerceAtLeast(1f)。
        if (engine == null) return testWorldUnitsPerPixel
        return ASTDProjectileVfxLayout.referenceWorldUnitsPerPixel(Display.getHeight().toFloat())
    }

    /** 测试入口：给定位置/存活推进一帧，绕过引擎（worldUnitsPerPixel 由参数指定，默认 1）。 */
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

    /** 测试入口：弹体已彻底移除（无实时位置），验证淡出期几何冻结。 */
    internal fun advanceRemovedForTests(amount: Float) {
        advanceInternal(null, null, 0f, amount, false)
    }

    internal fun lastFrameForTests(): FrameState? = lastFrame
}
