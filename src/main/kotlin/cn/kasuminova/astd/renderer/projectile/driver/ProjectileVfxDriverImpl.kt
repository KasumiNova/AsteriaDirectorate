package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.render.FrameStateImpl
import cn.kasuminova.astd.impl.render.RenderContextImpl
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileHistory
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * [ProjectileVfxDriver] 实现。编排逻辑照旧 `ASTDProjectileVfxRuntime` 忠实移植（采样/布局仍复用
 * [ASTDProjectileVfxLayout]/[ASTDProjectileHistory]），差别是：产出宿主中立的 [FrameState] 而非旧
 * `ASTDProjectileVfxRenderContext`，并推进一棵 [RenderEntity] 树的生命周期而非 RenderGraph。
 * 另：拖尾溶解不再按 elapsed/duration 老化（见 [buildFlightLayout]），改由弹体消亡后的 fade 接管。
 */
class ProjectileVfxDriverImpl(
    private val host: RenderHost,
    private val tree: RenderEntity,
    private val policy: ProjectileVfxDriverPolicy,
) : ProjectileVfxDriver {

    /** 弹体宿主时的原生弹体；非弹体宿主（如测试）为 null，存活判定改由调用方给出。 */
    private val projectile = (host as? ProjectileHost)?.projectile

    private val history = ASTDProjectileHistory(
        minDistancePerNode = policy.minDistancePerNode,
        maxHistoryNodes = policy.maxHistoryNodes,
        distanceWindow = policy.distanceWindow,
    )

    private var elapsed = 0f
    private var fadeElapsed = 0f
    private var traveledDistance = 0f
    private var lastLocation: Vector2f? = null
    private var lastFrame: FrameState? = null
    private var currentFadeReason: FadeReason = FadeReason.Removed
    private var currentFadeSeconds: Float = policy.removedFadeOutSeconds

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
        // isEntityInPlay=true，若只看 isEntityInPlay，拖尾会在越过射程环后满亮驻留一段才消失（无淡出观感）。
        // 命中（didDamage）走 isEntityInPlay 的移除路径即可，不在此纳入，避免误伤"击中后继续飞行"的穿透弹。
        val alive = inPlay && !active.isExpired && !active.isFading
        val location = if (inPlay) active.location else null
        val facing = if (inPlay) active.facing else 0f
        advanceInternal(engine, location, facing, amount, alive)
    }

    override fun dispose() {
        tree.onDetach()
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
            // 存活，或淡出期弹体仍在场滑行：跟随实时位置推进几何——与原版弹体在 fadeTime 窗口内继续移动一致，
            // 淡出（由树各层的 fade alpha 施加）叠加在跟随之上，而非把特效钉死在死亡前一帧。
            val renderFacing = computeRenderFacing(location, facing)
            accumulateTravelDistance(location)
            val worldUnitsPerPixel = referenceWorldUnitsPerPixel(engine)
            val flight = buildFlightLayout(worldUnitsPerPixel)
            history.advance(
                location,
                renderFacing,
                elapsed,
                retainDistance = historyRetainDistance(flight.visibleLength, worldUnitsPerPixel),
                retainNodeCount = historyRetainNodeCount(flight.visibleLength, worldUnitsPerPixel),
            )
            val phase = if (fading) RenderPhase.FadingOut else RenderPhase.Active
            val frame = buildFrame(location, renderFacing, flight, worldUnitsPerPixel, amount, phase, if (fading) currentFadeReason else null)
            driveTree(engine, frame, amount)
            telemetry = ProjectileVfxDriverTelemetry(
                elapsed = elapsed,
                visibleLength = frame.length,
                beamAlpha = frame.intensity,
                worldUnitsPerPixel = frame.worldUnitsPerPixel,
            )
            lastLocation = Vector2f(location)
            lastFrame = frame
        } else if (fading) {
            // 弹体已彻底移除（无实时位置）：冻结在最后一帧继续淡出直至结束。
            lastFrame?.let { frozen ->
                val faded = FrameStateImpl(
                    elapsed = elapsed,
                    logicElapsed = quantizedLogicElapsed(),
                    amountThisFrame = amount,
                    origin = frozen.origin,
                    facing = frozen.facing,
                    length = frozen.length,
                    endpoint = frozen.endpoint,
                    worldUnitsPerPixel = frozen.worldUnitsPerPixel,
                    active = false,
                    intensity = frozen.intensity,
                    phase = RenderPhase.FadingOut,
                    flightProgress = frozen.flightProgress,
                    dissolve = frozen.dissolve,
                    fadeReason = currentFadeReason,
                    historyNodes = frozen.historyNodes,
                )
                driveTree(engine, faded, amount)
            }
        }

        if (fading && fadeElapsed >= currentFadeSeconds.coerceAtLeast(0f)) {
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
        location: Vector2f,
        renderFacing: Float,
        flight: ASTDProjectileVfxLayout.FlightLayout,
        worldUnitsPerPixel: Float,
        amount: Float,
        phase: RenderPhase,
        fadeReason: FadeReason?,
    ): FrameState {
        val duration = max(policy.durationSeconds, 0.0001f)
        val progress = (elapsed / duration).coerceIn(0f, 1f)
        val scale = worldUnitsPerPixel.coerceAtLeast(0.0001f)
        return FrameStateImpl(
            elapsed = elapsed,
            logicElapsed = quantizedLogicElapsed(),
            amountThisFrame = amount,
            origin = Vector2f(location),
            facing = renderFacing,
            length = flight.visibleLength,
            endpoint = null,
            worldUnitsPerPixel = scale,
            active = phase == RenderPhase.Active,
            intensity = flight.beamAlpha,
            phase = phase,
            flightProgress = progress,
            dissolve = flight.dissolve,
            fadeReason = fadeReason,
            historyNodes = history.nodes(),
        )
    }

    private fun computeRenderFacing(location: Vector2f, projectileFacing: Float): Float {
        val previous = lastLocation ?: return projectileFacing
        val dx = location.x - previous.x
        val dy = location.y - previous.y
        if (dx * dx + dy * dy <= 0.0001f) return projectileFacing
        // 归一化到 [0,360)：BoxUtil setStateVanilla 对负角渲染异常（向下开火时 atan2 为负会导致拖尾镜像/歪斜），
        // 与旧 BoxUtilProjectileTrails 用 VectorUtils.getFacing（同为 [0,360)）保持一致。
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }

    private fun buildFlightLayout(worldUnitsPerPixel: Float): ASTDProjectileVfxLayout.FlightLayout {
        val scale = worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val hasTrail = policy.primaryTrailStartWidth > 0f
        // 弹体存活期间不做时间驱动的“老化溶解”：拖尾随弹体存续，长度随实际行程增长（受 viewportTailCap 约束）。
        // 旧公式用 elapsed/duration 算 dissolve，会让长寿命弹体（制导导弹飞行时间远超 durationSeconds）的拖尾在途中提前淡尽；
        // 溶解/淡出统一改由弹体消亡后的 Fading 分支（树 fade over fadeSeconds）接管。
        val visibleLength = if (hasTrail) {
            (traveledDistance / scale).coerceIn(
                0f,
                ASTDProjectileVfxLayout.viewportTailCap(policy.primaryTrailStartWidth, policy.layoutReferenceWidth),
            )
        } else {
            policy.primaryTrailLength
        }
        return ASTDProjectileVfxLayout.FlightLayout(dissolve = 0f, beamAlpha = 1f, visibleLength = visibleLength)
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

    private fun quantizedLogicElapsed(): Float {
        val fps = policy.historyFps.coerceAtLeast(1f)
        return floor(elapsed * fps) / fps
    }

    private fun accumulateTravelDistance(location: Vector2f) {
        val previous = lastLocation ?: return
        val dx = location.x - previous.x
        val dy = location.y - previous.y
        val distance = sqrt(dx * dx + dy * dy)
        if (distance > 0.0001f) traveledDistance += distance
    }

    private fun historyRetainDistance(visibleLength: Float, worldUnitsPerPixel: Float): Float {
        val scale = worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val visibleWorldDistance = visibleLength.coerceAtLeast(0f) * scale
        val samplingMargin = policy.minDistancePerNode.coerceAtLeast(0.5f) * 4f
        return max(policy.distanceWindow, visibleWorldDistance + samplingMargin)
    }

    private fun historyRetainNodeCount(visibleLength: Float, worldUnitsPerPixel: Float): Int {
        val retainDistance = historyRetainDistance(visibleLength, worldUnitsPerPixel)
        val minDistance = policy.minDistancePerNode.coerceAtLeast(0.5f)
        val byDistance = ceil(retainDistance / minDistance).toInt() + 4
        return max(policy.maxHistoryNodes, byDistance).coerceAtMost(MAX_RUNTIME_HISTORY_NODES)
    }

    private fun referenceWorldUnitsPerPixel(engine: CombatEngineAPI?): Float {
        // engine != null 即战斗内，LWJGL Display 必然可用；Layout 内部对高度做 coerceAtLeast(1f)。
        if (engine == null) return 1f
        return ASTDProjectileVfxLayout.referenceWorldUnitsPerPixel(Display.getHeight().toFloat())
    }

    /** 测试入口：给定位置/存活推进一帧，绕过引擎（worldUnitsPerPixel=1）。 */
    internal fun advanceForTests(locationX: Float, locationY: Float, facing: Float, amount: Float, alive: Boolean) {
        advanceInternal(null, Vector2f(locationX, locationY), facing, amount, alive)
    }

    /** 测试入口：弹体已彻底移除（无实时位置），验证淡出期几何冻结。 */
    internal fun advanceRemovedForTests(amount: Float) {
        advanceInternal(null, null, 0f, amount, false)
    }

    internal fun lastFrameForTests(): FrameState? = lastFrame

    private companion object {
        private const val MAX_RUNTIME_HISTORY_NODES = 512
    }
}
