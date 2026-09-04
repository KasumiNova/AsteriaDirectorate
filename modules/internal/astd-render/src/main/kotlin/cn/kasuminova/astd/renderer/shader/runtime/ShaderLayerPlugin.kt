package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformValue
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.util.EnumSet

/**
 * Starsector layered rendering adapter for queued shader submissions.
 *
 * The plugin owns no effect-specific state. It reads layer snapshots from the
 * queue and delegates program/state work to dedicated runtime helpers.
 */
class ShaderLayerPlugin(private val queue: ShaderRenderQueue) : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_PARTICLES) {
    private var expired = false
    private val programCache = ShaderProgramCache()

    override fun getActiveLayers(): EnumSet<CombatEngineLayers> {
        return EnumSet.of(
            CombatEngineLayers.BELOW_SHIPS_LAYER,
            CombatEngineLayers.ABOVE_PARTICLES,
            CombatEngineLayers.ABOVE_SHIPS_LAYER,
            CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        )
    }

    override fun getRenderRadius(): Float = Float.MAX_VALUE

    override fun isExpired(): Boolean = expired

    override fun cleanup() {
        expired = true
        programCache.deleteAll()
    }

    override fun advance(amount: Float) {
        if (expired) return
        queue.advance(amount)
    }

    override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
        if (expired) return
        val shaderLayer = shaderLayer(layer)
        val snapshots = queue.snapshotsForLayer(shaderLayer)
        if (snapshots.isEmpty()) return

        ShaderStateGuard.withSavedState {
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glEnable(GL11.GL_BLEND)
            for (submission in snapshots) {
                applyBlend(submission.spec.material.blendMode)
                val program = programCache.programFor(submission.spec.program)
                GL20.glUseProgram(program)
                uploadUniforms(program, submission)
                renderGeometry(submission)
            }
        }
    }

    private fun applyBlend(blendMode: ShaderBlendMode) {
        when (blendMode) {
            ShaderBlendMode.Additive -> GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
            ShaderBlendMode.Alpha -> GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    private fun uploadUniforms(program: Int, submission: ShaderSubmission) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_time"), submission.ageSeconds)
        for ((key, value) in submission.uniforms.values) {
            val location = GL20.glGetUniformLocation(program, "u_$key")
            when (value) {
                is ShaderUniformValue.FloatValue -> GL20.glUniform1f(location, value.value)
                is ShaderUniformValue.Vec2 -> GL20.glUniform2f(location, value.x, value.y)
                is ShaderUniformValue.Vec3 -> GL20.glUniform3f(location, value.x, value.y, value.z)
                is ShaderUniformValue.Vec4 -> GL20.glUniform4f(location, value.x, value.y, value.z, value.w)
                is ShaderUniformValue.IntValue -> GL20.glUniform1i(location, value.value)
                is ShaderUniformValue.BooleanValue -> GL20.glUniform1i(location, if (value.value) 1 else 0)
            }
        }
    }

    private fun renderGeometry(submission: ShaderSubmission) {
        when (val geometry = submission.spec.geometry) {
            is ShaderGeometrySpec.WorldQuad -> renderWorldQuad(submission, geometry)
        }
    }

    private fun renderWorldQuad(submission: ShaderSubmission, geometry: ShaderGeometrySpec.WorldQuad) {
        val center = submission.center
        val extent = geometry.halfExtentWorld
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f)
        GL11.glVertex2f(center.x - extent, center.y - extent)
        GL11.glTexCoord2f(1f, 0f)
        GL11.glVertex2f(center.x + extent, center.y - extent)
        GL11.glTexCoord2f(1f, 1f)
        GL11.glVertex2f(center.x + extent, center.y + extent)
        GL11.glTexCoord2f(0f, 1f)
        GL11.glVertex2f(center.x - extent, center.y + extent)
        GL11.glEnd()
    }

    companion object {
        fun combatLayer(layer: ShaderEffectLayer): CombatEngineLayers = when (layer) {
            ShaderEffectLayer.BelowParticles -> CombatEngineLayers.BELOW_SHIPS_LAYER
            ShaderEffectLayer.AboveParticles -> CombatEngineLayers.ABOVE_PARTICLES
            ShaderEffectLayer.AboveShips -> CombatEngineLayers.ABOVE_SHIPS_LAYER
            ShaderEffectLayer.ScreenSpace -> CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER
        }

        private fun shaderLayer(layer: CombatEngineLayers): ShaderEffectLayer = when (layer) {
            CombatEngineLayers.BELOW_SHIPS_LAYER -> ShaderEffectLayer.BelowParticles
            CombatEngineLayers.ABOVE_SHIPS_LAYER -> ShaderEffectLayer.AboveShips
            CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER -> ShaderEffectLayer.ScreenSpace
            else -> ShaderEffectLayer.AboveParticles
        }
    }
}
