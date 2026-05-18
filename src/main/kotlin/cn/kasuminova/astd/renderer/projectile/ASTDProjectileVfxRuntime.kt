package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxFadeReason
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxDebug
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMath
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderContext
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderGraph
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderLayer
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.base.api.RenderDataAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max

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
        advanceInternal(null, Vector2f(locationX, locationY), facing, amount, projectileAlive)
    }

    internal fun historyNodesForTests(): List<ASTDProjectileHistoryNode> = history.nodes()

    internal fun renderLayerCountForTests(): Int = renderGraph.layerCountForTests()

    private fun advanceInternal(engine: CombatEngineAPI?, location: Vector2f?, facing: Float, amount: Float, projectileAlive: Boolean) {
        if (state == ASTDProjectileVfxRuntimeState.Removed) return

        elapsed += amount.coerceAtLeast(0f)
        if (state == ASTDProjectileVfxRuntimeState.Active && projectileAlive && location != null) {
            val renderFacing = computeRenderFacing(location, facing)
            history.advance(location, renderFacing, elapsed)
            val context = buildContext(location, facing, renderFacing)
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
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun buildContext(location: Vector2f, projectileFacing: Float, renderFacing: Float): ASTDProjectileVfxRenderContext {
        val duration = max(preset.lifecycle.durationSeconds, 0.0001f)
        val progress = (elapsed / duration).coerceIn(0f, 1f)
        val dissolve = ASTDProjectileVfxMath.dissolve(elapsed, duration, preset.lifecycle.dissolveStartRatio)
        val beamAlpha = ASTDProjectileVfxMath.beamAlpha(dissolve)
        val baseLength = preset.trailEntities.firstOrNull()?.layers?.firstOrNull()?.length ?: preset.samplingPolicy.distanceWindow
        return ASTDProjectileVfxRenderContext(
            location = Vector2f(location),
            velocityFacing = renderFacing,
            projectileFacing = projectileFacing,
            renderFacing = renderFacing,
            elapsed = elapsed,
            logicElapsed = quantizedLogicElapsed(),
            flightProgress = progress,
            dissolve = dissolve,
            visibleLength = ASTDProjectileVfxMath.visibleLength(baseLength, dissolve),
            beamAlpha = beamAlpha,
            historyNodes = history.nodes(),
            presetId = preset.id,
            projectileSpecId = projectile?.projectileSpecId ?: preset.id,
        )
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
}
