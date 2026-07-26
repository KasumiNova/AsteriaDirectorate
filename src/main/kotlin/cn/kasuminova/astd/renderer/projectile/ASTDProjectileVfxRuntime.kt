package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxFadeReason
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxDebug
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMath
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderContext
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderGraph
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderLayer
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.base.api.RenderDataAPI
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

enum class ASTDProjectileVfxRuntimeState { Active, Fading, Removed }

class ASTDProjectileVfxRuntime(
    private val projectile: DamagingProjectileAPI?,
    private val preset: ASTDProjectileVfxPreset,
    renderLayers: List<ASTDProjectileVfxRenderLayer> = ASTDProjectileVfxRenderGraph.layersFor(preset),
) {
    private val history = ASTDProjectileHistory(
        minDistancePerNode = preset.samplingPolicy.minDistancePerNode,
        maxHistoryNodes = preset.samplingPolicy.maxHistoryNodes,
        distanceWindow = preset.samplingPolicy.distanceWindow,
    )
    private val renderGraph = ASTDProjectileVfxRenderGraph(renderLayers)
    private val handles = ArrayList<RenderDataAPI>()
    private var elapsed = 0f
    private var fadeElapsed = 0f
    private var traveledDistance = 0f
    private var lastLocation: Vector2f? = null
    private var lastContext: ASTDProjectileVfxRenderContext? = null
    private var currentFadeSeconds: Float = preset.fadePolicy.fadeOutSeconds

    var state: ASTDProjectileVfxRuntimeState = ASTDProjectileVfxRuntimeState.Active
        private set

    fun advance(engine: CombatEngineAPI, amount: Float) {
        val activeProjectile = projectile
        val projectileAlive = activeProjectile != null && engine.isEntityInPlay(activeProjectile)
        if (activeProjectile != null) {
            advanceInternal(engine, activeProjectile.location, activeProjectile.facing, amount, projectileAlive)
        } else if (!projectileAlive) {
            advanceInternal(engine, null, 0f, amount, false)
        }
    }

    fun markProjectileGone(reason: ASTDProjectileVfxFadeReason = ASTDProjectileVfxFadeReason.Removed) {
        if (state == ASTDProjectileVfxRuntimeState.Active) {
            state = ASTDProjectileVfxRuntimeState.Fading
            fadeElapsed = 0f
            currentFadeSeconds = fadeSeconds(reason)
            renderGraph.beginFadeOut(reason, currentFadeSeconds)
        }
    }

    fun dispose() {
        renderGraph.beginFadeOut(ASTDProjectileVfxFadeReason.Dispose, 0f)
        renderGraph.delete()
        handles.forEach { it.delete() }
        handles.clear()
        state = ASTDProjectileVfxRuntimeState.Removed
    }

    internal fun registerHandleForTests(handle: RenderDataAPI) {
        handles += handle
    }

    internal fun advanceForTests(locationX: Float, locationY: Float, facing: Float, amount: Float, projectileAlive: Boolean) {
        advanceForTests(locationX, locationY, facing, amount, projectileAlive, null)
    }

    internal fun advanceForTests(
        locationX: Float,
        locationY: Float,
        facing: Float,
        amount: Float,
        projectileAlive: Boolean,
        viewportVisibleWidth: Float?,
    ) {
        advanceInternal(null, Vector2f(locationX, locationY), facing, amount, projectileAlive, viewportVisibleWidth)
    }

    internal fun advanceForTests(
        locationX: Float,
        locationY: Float,
        facing: Float,
        amount: Float,
        projectileAlive: Boolean,
        viewportVisibleWidth: Float?,
        viewportPixelWidth: Float?,
        @Suppress("UNUSED_PARAMETER")
        viewportViewMult: Float? = null,
    ) {
        advanceInternal(null, Vector2f(locationX, locationY), facing, amount, projectileAlive, viewportVisibleWidth, viewportPixelWidth)
    }

    internal fun historyNodesForTests(): List<ASTDProjectileHistoryNode> = history.nodes()

    /** golden 对比用：读取旧管线每帧产出的 RenderContext，与新驱动的 FrameState 逐字段比对。 */
    internal fun lastContextForTests(): ASTDProjectileVfxRenderContext? = lastContext

    internal fun renderLayerCountForTests(): Int = renderGraph.layerCountForTests()

    private fun advanceInternal(
        engine: CombatEngineAPI?,
        location: Vector2f?,
        facing: Float,
        amount: Float,
        projectileAlive: Boolean,
        @Suppress("UNUSED_PARAMETER")
        viewportVisibleWidthOverride: Float? = null,
        @Suppress("UNUSED_PARAMETER")
        viewportPixelWidthOverride: Float? = null,
        @Suppress("UNUSED_PARAMETER")
        viewportViewMultOverride: Float? = null,
    ) {
        if (state == ASTDProjectileVfxRuntimeState.Removed) return

        elapsed += amount.coerceAtLeast(0f)
        if (state == ASTDProjectileVfxRuntimeState.Active && projectileAlive && location != null) {
            val renderFacing = computeRenderFacing(location, facing)
            accumulateTravelDistance(location)
            val worldUnitsPerPixel = referenceWorldUnitsPerPixel(engine, viewportPixelWidthOverride)
            val flight = buildFlightLayout(worldUnitsPerPixel)
            history.advance(
                location,
                renderFacing,
                elapsed,
                retainDistance = historyRetainDistance(flight.visibleLength, worldUnitsPerPixel),
                retainNodeCount = historyRetainNodeCount(flight.visibleLength, worldUnitsPerPixel),
            )
            val context = buildContext(
                location,
                facing,
                renderFacing,
                flight,
                worldUnitsPerPixel,
            )
            ASTDProjectileVfxRuntimeTelemetry.recordContext(context)
            ASTDProjectileVfxDebug.logLayoutOnce(preset, context)
            renderGraph.advance(engine, context, amount)
            lastLocation = Vector2f(location)
            lastContext = context
            return
        }

        if (state == ASTDProjectileVfxRuntimeState.Active) {
            markProjectileGone(projectileGoneReason(projectile))
        }

        if (state == ASTDProjectileVfxRuntimeState.Fading) {
            fadeElapsed += amount.coerceAtLeast(0f)
            lastContext?.let { context ->
                renderGraph.advance(engine, context.copy(elapsed = elapsed), amount)
            }
            if (fadeElapsed >= currentFadeSeconds.coerceAtLeast(0f)) {
                dispose()
            }
        }
    }

    companion object {
        private const val MAX_RUNTIME_HISTORY_NODES = 512

        fun forTests(preset: ASTDProjectileVfxPreset, renderLayers: List<ASTDProjectileVfxRenderLayer>? = null): ASTDProjectileVfxRuntime {
            return ASTDProjectileVfxRuntime(
                projectile = null,
                preset = preset,
                renderLayers = renderLayers ?: ASTDProjectileVfxRenderGraph.layersFor(preset),
            )
        }
    }

    private fun computeRenderFacing(location: Vector2f, projectileFacing: Float): Float {
        val previous = lastLocation ?: return projectileFacing
        val dx = location.x - previous.x
        val dy = location.y - previous.y
        val speedSq = dx * dx + dy * dy
        if (speedSq <= 0.0001f) return projectileFacing
        // 归一化到 [0,360)：BoxUtil setStateVanilla 对负角渲染异常（向下开火时拖尾镜像/歪斜）。
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }

    private fun buildContext(
        location: Vector2f,
        projectileFacing: Float,
        renderFacing: Float,
        flight: ASTDProjectileVfxLayout.FlightLayout,
        worldUnitsPerPixel: Float,
    ): ASTDProjectileVfxRenderContext {
        val duration = max(preset.lifecycle.durationSeconds, 0.0001f)
        val progress = (elapsed / duration).coerceIn(0f, 1f)
        val scale = worldUnitsPerPixel.coerceAtLeast(0.0001f)
        return ASTDProjectileVfxRenderContext(
            location = Vector2f(location),
            velocityFacing = renderFacing,
            projectileFacing = projectileFacing,
            renderFacing = renderFacing,
            elapsed = elapsed,
            logicElapsed = quantizedLogicElapsed(),
            flightProgress = progress,
            dissolve = flight.dissolve,
            visibleLength = flight.visibleLength,
            beamAlpha = flight.beamAlpha,
            historyNodes = history.nodes(),
            presetId = preset.id,
            projectileSpecId = projectile?.projectileSpecId ?: preset.id,
            worldUnitsPerPixel = scale,
        )
    }

    private fun buildFlightLayout(
        worldUnitsPerPixel: Float,
    ): ASTDProjectileVfxLayout.FlightLayout {
        val duration = max(preset.lifecycle.durationSeconds, 0.0001f)
        val baseLength = preset.primaryTrailLayer()?.length ?: preset.samplingPolicy.distanceWindow
        val baseLayer = preset.primaryTrailLayer()
        val scale = worldUnitsPerPixel.coerceAtLeast(0.0001f)
        return if (baseLayer != null) {
            ASTDProjectileVfxLayout.distanceFlightLayout(
                maxVisibleLength = maxVisibleLength(baseLayer, scale),
                traveledDistance = traveledDistance / scale,
                elapsed = elapsed,
                durationSeconds = duration,
                dissolveStartRatio = preset.lifecycle.dissolveStartRatio,
            )
        } else {
            val dissolve = ASTDProjectileVfxMath.dissolve(elapsed, duration, preset.lifecycle.dissolveStartRatio)
            ASTDProjectileVfxLayout.FlightLayout(
                dissolve = dissolve,
                beamAlpha = ASTDProjectileVfxMath.beamAlpha(dissolve),
                visibleLength = ASTDProjectileVfxMath.visibleLength(baseLength, dissolve),
            )
        }
    }

    private fun projectileGoneReason(projectile: DamagingProjectileAPI?): ASTDProjectileVfxFadeReason {
        if (projectile == null) return ASTDProjectileVfxFadeReason.Removed
        if (projectile.didDamage()) return ASTDProjectileVfxFadeReason.Hit
        if (projectile.isExpired || projectile.isFading) return ASTDProjectileVfxFadeReason.Expire
        return ASTDProjectileVfxFadeReason.Removed
    }

    private fun fadeSeconds(reason: ASTDProjectileVfxFadeReason): Float = when (reason) {
        ASTDProjectileVfxFadeReason.Hit -> preset.fadePolicy.hitFadeOutSeconds
        ASTDProjectileVfxFadeReason.Expire -> preset.fadePolicy.expireFadeOutSeconds
        ASTDProjectileVfxFadeReason.Removed -> preset.fadePolicy.fadeOutSeconds
        ASTDProjectileVfxFadeReason.Dispose -> 0f
    }

    private fun quantizedLogicElapsed(): Float {
        val fps = preset.samplingPolicy.historyFps.coerceAtLeast(1f)
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
        val samplingMargin = preset.samplingPolicy.minDistancePerNode.coerceAtLeast(0.5f) * 4f
        return max(preset.samplingPolicy.distanceWindow, visibleWorldDistance + samplingMargin)
    }

    private fun historyRetainNodeCount(visibleLength: Float, worldUnitsPerPixel: Float): Int {
        val retainDistance = historyRetainDistance(visibleLength, worldUnitsPerPixel)
        val minDistance = preset.samplingPolicy.minDistancePerNode.coerceAtLeast(0.5f)
        val byDistance = ceil(retainDistance / minDistance).toInt() + 4
        return max(preset.samplingPolicy.maxHistoryNodes, byDistance).coerceAtMost(MAX_RUNTIME_HISTORY_NODES)
    }

    private fun maxVisibleLength(baseLayer: ASTDTrailLayerSpec, worldUnitsPerPixel: Float): Float {
        val authoredCap = ASTDProjectileVfxLayout.viewportTailCap(baseLayer.startWidth, preset.lifecycle.layoutReferenceWidth)
        return authoredCap
    }

    private fun referenceWorldUnitsPerPixel(engine: CombatEngineAPI?, viewportPixelWidthOverride: Float?): Float {
        if (engine == null && viewportPixelWidthOverride == null) return 1f
        val pixelHeight = viewportPixelWidthOverride
            ?.takeIf { it > 0f }
            ?.let { it / (16f / 9f) }
            ?: displayPixelHeight()
        return ASTDProjectileVfxLayout.referenceWorldUnitsPerPixel(pixelHeight)
    }

    private fun displayPixelHeight(): Float {
        return try {
            Display.getHeight().takeIf { it > 0 }?.toFloat() ?: 1f
        } catch (_: Throwable) {
            1f
        }
    }
}
