package cn.kasuminova.astd.renderer.projectile

import com.fs.starfarer.api.combat.DamagingProjectileAPI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dev-only projectile VFX telemetry surface used by in-game automation.
 */
object ASTDProjectileVfxRuntimeTelemetry {
    private val trackedCount = AtomicInteger(0)

    @Volatile
    private var lastProjectileSpecId: String? = null

    @Volatile
    private var lastPresetId: String? = null

    @Volatile
    private var lastElapsed: Float = 0f

    @Volatile
    private var lastVisibleLength: Float = 0f

    @Volatile
    private var lastBeamAlpha: Float = 0f

    @Volatile
    private var lastWorldUnitsPerPixel: Float = 1f

    fun recordTracked(projectile: DamagingProjectileAPI, preset: ASTDProjectileVfxPreset) {
        trackedCount.incrementAndGet()
        lastProjectileSpecId = projectile.projectileSpecId
        lastPresetId = preset.id
    }

    fun snapshot(): Snapshot = Snapshot(
        trackedCount = trackedCount.get(),
        lastProjectileSpecId = lastProjectileSpecId,
        lastPresetId = lastPresetId,
        lastElapsed = lastElapsed,
        lastVisibleLength = lastVisibleLength,
        lastBeamAlpha = lastBeamAlpha,
        lastWorldUnitsPerPixel = lastWorldUnitsPerPixel,
    )

    fun recordContext(context: cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderContext) {
        lastElapsed = context.elapsed
        lastVisibleLength = context.visibleLength
        lastBeamAlpha = context.beamAlpha
        lastWorldUnitsPerPixel = context.worldUnitsPerPixel
    }

    fun clear() {
        trackedCount.set(0)
        lastProjectileSpecId = null
        lastPresetId = null
        lastElapsed = 0f
        lastVisibleLength = 0f
        lastBeamAlpha = 0f
        lastWorldUnitsPerPixel = 1f
    }

    data class Snapshot(
        val trackedCount: Int,
        val lastProjectileSpecId: String?,
        val lastPresetId: String?,
        val lastElapsed: Float,
        val lastVisibleLength: Float,
        val lastBeamAlpha: Float,
        val lastWorldUnitsPerPixel: Float,
    )
}
