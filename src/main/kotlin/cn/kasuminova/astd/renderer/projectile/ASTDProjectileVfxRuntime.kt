package cn.kasuminova.astd.renderer.projectile

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.base.api.RenderDataAPI
import org.lwjgl.util.vector.Vector2f

enum class ASTDProjectileVfxRuntimeState { Active, Fading, Removed }

class ASTDProjectileVfxRuntime(
    private val projectile: DamagingProjectileAPI?,
    private val preset: ASTDProjectileVfxPreset,
) {
    private val history = ASTDProjectileHistory(
        minDistancePerNode = preset.samplingPolicy.minDistancePerNode,
        maxHistoryNodes = preset.samplingPolicy.maxHistoryNodes,
        distanceWindow = preset.samplingPolicy.distanceWindow,
    )
    private val handles = ArrayList<RenderDataAPI>()
    private var elapsed = 0f
    private var fadeElapsed = 0f

    var state: ASTDProjectileVfxRuntimeState = ASTDProjectileVfxRuntimeState.Active
        private set

    fun advance(engine: CombatEngineAPI, amount: Float) {
        val activeProjectile = projectile
        val projectileAlive = activeProjectile != null && engine.isEntityInPlay(activeProjectile)
        if (activeProjectile != null) {
            advanceInternal(activeProjectile.location, activeProjectile.facing, amount, projectileAlive)
        } else if (!projectileAlive) {
            advanceInternal(null, 0f, amount, false)
        }
    }

    fun markProjectileGone() {
        if (state == ASTDProjectileVfxRuntimeState.Active) {
            state = ASTDProjectileVfxRuntimeState.Fading
            fadeElapsed = 0f
        }
    }

    fun dispose() {
        handles.forEach { it.delete() }
        handles.clear()
        state = ASTDProjectileVfxRuntimeState.Removed
    }

    internal fun registerHandleForTests(handle: RenderDataAPI) {
        handles += handle
    }

    internal fun advanceForTests(locationX: Float, locationY: Float, facing: Float, amount: Float, projectileAlive: Boolean) {
        advanceInternal(Vector2f(locationX, locationY), facing, amount, projectileAlive)
    }

    internal fun historyNodesForTests(): List<ASTDProjectileHistoryNode> = history.nodes()

    private fun advanceInternal(location: Vector2f?, facing: Float, amount: Float, projectileAlive: Boolean) {
        if (state == ASTDProjectileVfxRuntimeState.Removed) return

        elapsed += amount.coerceAtLeast(0f)
        if (state == ASTDProjectileVfxRuntimeState.Active && projectileAlive && location != null) {
            history.advance(location, facing, elapsed)
            return
        }

        if (state == ASTDProjectileVfxRuntimeState.Active) {
            markProjectileGone()
        }

        if (state == ASTDProjectileVfxRuntimeState.Fading) {
            fadeElapsed += amount.coerceAtLeast(0f)
            if (fadeElapsed >= preset.fadePolicy.fadeOutSeconds.coerceAtLeast(0f)) {
                dispose()
            }
        }
    }

    companion object {
        fun forTests(preset: ASTDProjectileVfxPreset): ASTDProjectileVfxRuntime {
            return ASTDProjectileVfxRuntime(projectile = null, preset = preset)
        }
    }
}
