package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileHistoryNode
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPreset
import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentRegistry
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f

interface ASTDProjectileVfxRenderLayer {
    fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean
    fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float)
    fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float)
    fun delete()
}

data class ASTDProjectileVfxRenderContext(
    val location: Vector2f,
    val velocityFacing: Float,
    val projectileFacing: Float,
    val renderFacing: Float,
    val elapsed: Float,
    val logicElapsed: Float = elapsed,
    val flightProgress: Float,
    val dissolve: Float,
    val visibleLength: Float,
    val beamAlpha: Float,
    val historyNodes: List<ASTDProjectileHistoryNode>,
    val presetId: String,
    val projectileSpecId: String,
    val worldUnitsPerPixel: Float = 1f,
)

enum class ASTDProjectileVfxFadeReason { Hit, Expire, Removed, Dispose }

internal class ASTDProjectileVfxLayerFadeState {
    private var active = false
    private var seconds = 0f
    private var elapsed = 0f

    fun begin(seconds: Float) {
        active = true
        this.seconds = seconds.coerceAtLeast(0f)
        elapsed = 0f
    }

    fun advance(amount: Float) {
        if (active) elapsed += amount.coerceAtLeast(0f)
    }

    fun alpha(): Float {
        if (!active) return 1f
        if (seconds <= 0f) return 0f
        return (1f - elapsed / seconds).coerceIn(0f, 1f)
    }

    fun complete(): Boolean = active && alpha() <= 0f
}

class ASTDProjectileVfxRenderGraph(
    private val layers: List<ASTDProjectileVfxRenderLayer>,
) {
    private var created = false

    fun layerCountForTests(): Int = layers.size

    fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (created) return true
        var allCreated = true
        for (layer in layers) {
            allCreated = layer.create(engine, context) && allCreated
        }
        created = true
        return allCreated
    }

    fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        if (!created) create(engine, context)
        for (layer in layers) {
            layer.advance(engine, context, amount)
        }
    }

    fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        for (layer in layers) {
            layer.beginFadeOut(reason, seconds)
        }
    }

    fun delete() {
        for (layer in layers) {
            layer.delete()
        }
        created = false
    }

    companion object {
        fun layersFor(preset: ASTDProjectileVfxPreset): List<ASTDProjectileVfxRenderLayer> {
            return ASTDProjectileVfxComponentRegistry.layersFor(preset.components, preset.lifecycle)
        }
    }
}
