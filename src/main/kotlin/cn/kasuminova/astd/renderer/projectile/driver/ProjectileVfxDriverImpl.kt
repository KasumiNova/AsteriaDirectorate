package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.render.FrameStateImpl
import cn.kasuminova.astd.impl.render.RenderContextImpl
import cn.kasuminova.astd.impl.render.ASTDProjectileHistory
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxLayout
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [ProjectileVfxDriver] 实现：采样弹体历史/布局（[ASTDProjectileVfxLayout]/[ASTDProjectileHistory]）产出
 * 宿主中立的 [FrameState]，并推进一棵 [RenderEntity] 树的生命周期。
 * 拖尾溶解不按 elapsed/duration 老化（见 [buildFlightLayout]），由弹体消亡后的 fade 接管。
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
    /** 测试用尺度注入（无引擎时 referenceWorldUnitsPerPixel 的返回值）。 */
    private var testWorldUnitsPerPixel = 1f
    /** 最近一帧估算的逐节点拖尾寿命（秒）；消亡后冻结帧沿用，并作为时间加速比的分子（见 [currentTrailTimeOffset]）。 */
    private var currentTrailLifetime = 0f
    /** 存活期逐帧追踪的弹体速度（su/s）；消亡定格时快照给 [frozenVelocity] 供带头前飞。 */
    private val lastVelocity = Vector2f(0f, 0f)
    /** 消亡定格瞬间的速度快照（su/s）；几何冻结期带头沿此方向继续前飞一小段，弥合原版弹体瞬灭留下的断头缝。 */
    private val frozenVelocity = Vector2f(0f, 0f)
    /** 冻结期带头已前飞的累计距离（世界单位），上限见 [FORWARD_FLIGHT_CAP_RATIO]。 */
    private var forwardFlown = 0f
    /**
     * 拖尾锚点前移量（世界单位）：策略显式值优先，否则取弹体 spec.length/2——把带体亮头对齐到
     * 原版螺栓贴图的视觉头部（贴图中心在弹体位置），命中瞬灭后带体亮头恰在命中点，无截断观感。
     * 非弹体宿主（测试）或无 spec 时为 0，锚回弹体中心。
     */
    private val headLead: Float = policy.headLeadWorld
        ?: ((projectile?.projectileSpec?.length ?: 0f) * 0.5f)

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
            trackVelocity(location, amount)
            accumulateTravelDistance(location)
            // 拖尾/弹头树锚在弹体视觉头部（中心沿朝向提前 headLead），历史路径同样记录头部轨迹，
            // 带体与原版螺栓头对齐；速度/行程估算仍用弹体中心（位移增量相同，无需改）。
            val anchor = headAnchor(location, renderFacing)
            val worldUnitsPerPixel = referenceWorldUnitsPerPixel(engine)
            val flight = buildFlightLayout()
            history.advance(
                anchor,
                renderFacing,
                elapsed,
                retainDistance = historyRetainDistance(flight.visibleLength),
                retainNodeCount = historyRetainNodeCount(flight.visibleLength),
            )
            // 逐节点寿命 = 预期带长（世界单位，取 cap 而非仍在生长的 visibleLength）/ 实测速度；
            // 在 history.advance 之后估算，吃到最新节点
            if (policy.primaryTrailStartWidth > 0f) {
                currentTrailLifetime = estimateTrailLifetime(maxTrailLengthWorld())
            }
            val phase = if (fading) RenderPhase.FadingOut else RenderPhase.Active
            val frame = buildFrame(anchor, renderFacing, flight, worldUnitsPerPixel, amount, phase, if (fading) currentFadeReason else null)
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
            // 弹体已彻底移除（无实时位置）：几何冻结在最后一帧，但带头沿消亡前速度继续前飞一小段
            // （上限 primaryTrailLength × [FORWARD_FLIGHT_CAP_RATIO]），让带体亮端流进命中点，
            // 弥合原版弹体命中瞬灭留下的断头缝；同时按加速时间偏移继续淡出直至结束。
            lastFrame?.let { frozen ->
                val origin = forwardFlightOrigin(frozen.origin, amount)
                val faded = FrameStateImpl(
                    elapsed = elapsed,
                    logicElapsed = quantizedLogicElapsed(),
                    amountThisFrame = amount,
                    origin = origin,
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
                    trailLifetimeSeconds = frozen.trailLifetimeSeconds,
                    trailTimeOffsetSeconds = currentTrailTimeOffset(),
                )
                driveTree(engine, faded, amount)
            }
        }

        // 拖尾消亡后按加速时间偏移在死亡消散窗口内老完（尾先消、头后消），不再等满整条逐节点寿命；
        // 弹头 bloom 层仍按 fadeSeconds 淡完。dispose 截止取两者较大者。
        if (fading && fadeElapsed >= max(currentFadeSeconds, trailDeathFadeSeconds(currentFadeReason))) {
            dispose()
        }
    }

    /**
     * 拖尾死亡消散窗口（秒）：消亡/命中后整带在本窗口内按尾先头后序加速老完。
     * 取值小于自然寿命（带长 ×4 后数秒级），避免命中后带体满亮驻留的「停滞感」与过长尾迹。
     */
    private fun trailDeathFadeSeconds(reason: FadeReason): Float = when (reason) {
        FadeReason.Hit -> TRAIL_DEATH_FADE_HIT
        FadeReason.Expire -> TRAIL_DEATH_FADE_EXPIRE
        else -> TRAIL_DEATH_FADE_REMOVED
    }

    /**
     * 拖尾时间加速偏移（秒）：fadeElapsed × 寿命/死亡消散窗口。texTrail 节点年龄加上本值后，
     * 最年轻节点恰好于窗口结束时老完，年长节点同比提前——保持尾先头后序且死亡瞬间无跳变（偏移从 0 累加）。
     */
    private fun currentTrailTimeOffset(): Float {
        if (state != ProjectileVfxDriverState.Fading || currentTrailLifetime <= 0f) return 0f
        return fadeElapsed * (currentTrailLifetime / trailDeathFadeSeconds(currentFadeReason))
    }

    /** 冻结期带头前飞：沿消亡前速度平移 origin，累计距离钳到上限；零速快照（如首帧即消亡）不前飞。 */
    private fun forwardFlightOrigin(base: Vector2f, amount: Float): Vector2f {
        val speed = sqrt(frozenVelocity.x * frozenVelocity.x + frozenVelocity.y * frozenVelocity.y)
        if (speed <= 0.0001f) return Vector2f(base)
        val cap = policy.primaryTrailLength.coerceAtLeast(0f) * FORWARD_FLIGHT_CAP_RATIO
        forwardFlown = (forwardFlown + speed * amount.coerceAtLeast(0f)).coerceAtMost(cap)
        return Vector2f(
            base.x + frozenVelocity.x / speed * forwardFlown,
            base.y + frozenVelocity.y / speed * forwardFlown,
        )
    }

    private fun markGone(reason: FadeReason) {
        if (state != ProjectileVfxDriverState.Active) return
        state = ProjectileVfxDriverState.Fading
        fadeElapsed = 0f
        currentFadeReason = reason
        currentFadeSeconds = fadeSeconds(reason)
        frozenVelocity.set(lastVelocity.x, lastVelocity.y)
        forwardFlown = 0f
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
            origin = Vector2f(anchor),
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
            trailLifetimeSeconds = currentTrailLifetime,
            trailTimeOffsetSeconds = currentTrailTimeOffset(),
        )
    }

    /** 逐帧追踪弹体速度（su/s）：供消亡定格时快照。首帧/零步长帧不更新。 */
    private fun trackVelocity(location: Vector2f, amount: Float) {
        val previous = lastLocation ?: return
        if (amount <= 0.0001f) return
        lastVelocity.set(
            (location.x - previous.x) / amount,
            (location.y - previous.y) / amount,
        )
    }

    /**
     * 逐节点拖尾寿命估算：预期带长（世界单位）/ 实测速度。
     * 速度取历史首尾节点的弧长/时间跨度（弯道按折线累计）；历史不足 2 点回退全程平均速度，
     * 再不足按下限速度。结果 clamp 到 [MIN_TRAIL_LIFETIME, MAX_TRAIL_LIFETIME]，防极端速度下
     * 带体瞬灭或永驻。
     */
    private fun estimateTrailLifetime(expectedLengthWorld: Float): Float {
        val speed = estimateSpeedSuPerSec()
        return (expectedLengthWorld.coerceAtLeast(0f) / speed)
            .coerceIn(MIN_TRAIL_LIFETIME, MAX_TRAIL_LIFETIME)
    }

    private fun estimateSpeedSuPerSec(): Float {
        val nodes = history.nodes()
        if (nodes.size >= 2) {
            val first = nodes.first()
            val last = nodes.last()
            val span = last.elapsed - first.elapsed
            if (span > 0.0001f) {
                var arc = 0f
                for (i in 1 until nodes.size) {
                    val dx = nodes[i].location.x - nodes[i - 1].location.x
                    val dy = nodes[i].location.y - nodes[i - 1].location.y
                    arc += sqrt(dx * dx + dy * dy)
                }
                if (arc > 0.0001f) return (arc / span).coerceAtLeast(MIN_SPEED_SU_PER_SEC)
            }
        }
        if (elapsed > 0.0001f && traveledDistance > 0.0001f) {
            return (traveledDistance / elapsed).coerceAtLeast(MIN_SPEED_SU_PER_SEC)
        }
        return MIN_SPEED_SU_PER_SEC
    }

    /** 拖尾锚点：弹体中心沿渲染朝向提前 [headLead]；headLead ≤ 0 时原样返回中心。 */
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
        // 归一化到 [0,360)：BoxUtil setStateVanilla 对负角渲染异常（向下开火时 atan2 为负会导致拖尾镜像/歪斜），
        // 与旧 BoxUtilProjectileTrails 用 VectorUtils.getFacing（同为 [0,360)）保持一致。
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }

    private fun buildFlightLayout(): ASTDProjectileVfxLayout.FlightLayout {
        val hasTrail = policy.primaryTrailStartWidth > 0f
        // 弹体存活期间不做时间驱动的“老化溶解”：拖尾随弹体存续，长度随实际行程增长（受 maxTrailLengthWorld 约束）。
        // 溶解/淡出由逐节点寿命机制接管（见 TexTrailComponent），弹体消亡事件不再对带体施加全局 alpha。
        // visibleLength 恒为世界单位：cap 由参考像素域直接 ×TRAIL_MAX_LENGTH_MULT 折为世界单位（分辨率无关），
        // 历史路径本来就是世界坐标，带长与世界坐标路径直接可比，带尾不会越出历史弧长。
        val visibleLength = if (hasTrail) {
            traveledDistance.coerceIn(0f, maxTrailLengthWorld())
        } else {
            policy.primaryTrailLength
        }
        return ASTDProjectileVfxLayout.FlightLayout(dissolve = 0f, beamAlpha = 1f, visibleLength = visibleLength)
    }

    /** 最大带长（世界单位）：viewportTailCap 参考像素域结果 ×[TRAIL_MAX_LENGTH_MULT]，分辨率/缩放无关。 */
    private fun maxTrailLengthWorld(): Float {
        return ASTDProjectileVfxLayout.viewportTailCap(
            policy.primaryTrailStartWidth, policy.layoutReferenceWidth,
        ) * TRAIL_MAX_LENGTH_MULT
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

    private fun historyRetainDistance(visibleLength: Float): Float {
        // visibleLength 已是世界单位（见 buildFlightLayout），直接与历史路径弧长（世界坐标）同域。
        val samplingMargin = policy.minDistancePerNode.coerceAtLeast(0.5f) * 4f
        return max(policy.distanceWindow, visibleLength.coerceAtLeast(0f) + samplingMargin)
    }

    private fun historyRetainNodeCount(visibleLength: Float): Int {
        val retainDistance = historyRetainDistance(visibleLength)
        val minDistance = policy.minDistancePerNode.coerceAtLeast(0.5f)
        val byDistance = ceil(retainDistance / minDistance).toInt() + 4
        return max(policy.maxHistoryNodes, byDistance).coerceAtMost(MAX_RUNTIME_HISTORY_NODES)
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

    private companion object {
        private const val MAX_RUNTIME_HISTORY_NODES = 512
        /** 速度估算下限（su/s）：防止零速/极低速弹体算出无穷寿命。 */
        private const val MIN_SPEED_SU_PER_SEC = 1f
        /** 逐节点拖尾寿命区间（秒）：下限防瞬灭，上限防永驻。 */
        private const val MIN_TRAIL_LIFETIME = 0.1f
        private const val MAX_TRAIL_LIFETIME = 10f

        /**
         * 最大带长倍率：viewportTailCap 的参考像素域结果直接折为世界单位（不乘 worldUnitsPerPixel、
         * 分辨率无关）后再 ×4。历史包袱：单位修复前 cap 被误当世界单位使用（1440p 下视觉带长 ≈
         * 修复后 2.4 倍），修复后带长骤短被目检否定——美术裁定按「修复前的 4 倍」定最大带长。
         */
        private const val TRAIL_MAX_LENGTH_MULT = 4f

        /**
         * 拖尾死亡消散窗口（秒）：命中 0.45 / 其他移除 0.6 / 超射程 0.75。
         * 目检裁定：在初版 0.3/0.4/0.5（命中后整带立即开始消散不瞬灭）基础上 +50%——
         * 命中/消亡后带体消散观感偏急促，放慢至重构前等效时长的 75%。
         */
        private const val TRAIL_DEATH_FADE_HIT = 0.45f
        private const val TRAIL_DEATH_FADE_EXPIRE = 0.75f
        private const val TRAIL_DEATH_FADE_REMOVED = 0.6f

        /** 冻结期带头前飞距离上限 = primaryTrailLength × 本值（略大于带体退距 recede≈0.08×L，恰好弥合断头缝不 overshoot）。 */
        private const val FORWARD_FLIGHT_CAP_RATIO = 0.15f
    }
}
