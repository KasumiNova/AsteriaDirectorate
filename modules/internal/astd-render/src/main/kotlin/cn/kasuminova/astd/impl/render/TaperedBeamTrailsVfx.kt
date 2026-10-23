package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseEnvelope
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseSpec
import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

internal object TaperedBeamTrailsVfx {

    private const val CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val FRINGE_SPRITE = "graphics/fx/beamfringeb.png"

    private const val POOL_CAPACITY = 96

    data class LayerParams(
        val coreColor: Color,
        val fringeColor: Color,
        val baseAlphaMul: Float,
        val tipAlphaMul: Float,
        val baseEmissiveAlphaMul: Float,
        val tipEmissiveAlphaMul: Float,
        val mixPower: Float,
        val mirroredUMul: Float = 0f,
    )

    data class BeamParams(
        val fadeIn: Float,
        val full: Float,
        val fadeOut: Float,
        val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        val core: LayerParams,
        val glow: LayerParams,
    )

    fun spawn(
        engine: CombatEngineAPI,
        from: Vector2f,
        to: Vector2f,
        coreBaseWidth: Float,
        coreTipWidth: Float,
        glowBaseWidth: Float,
        glowTipWidth: Float,
        params: BeamParams,
    ) {
        val line = BeamLineUtil.fromPoints(from, to) ?: return
        val envelope = TrailLeaseEnvelope(
            params.fadeIn.coerceAtLeast(0f),
            params.full.coerceAtLeast(0.01f),
            params.fadeOut.coerceAtLeast(0f),
        )

        fun spawnLayer(p: LayerParams, baseW: Float, tipW: Float) {
            val key = TrailLeaseKey(params.layer, CORE_SPRITE, FRINGE_SPRITE, p.mixPower, POOL_CAPACITY)
            PooledCombatVfx.checkoutTrail(
                engine, key,
                TrailLeaseSpec(
                    location = line.from,
                    facingDeg = line.facing,
                    nodes = listOf(Vector2f(0f, 0f), Vector2f(line.length, 0f)),
                    startWidth = baseW,
                    endWidth = tipW,
                    coreColor = p.coreColor,
                    fringeColor = p.fringeColor,
                    startAlpha = p.baseAlphaMul,
                    endAlpha = p.tipAlphaMul,
                    startEmissiveAlpha = p.baseEmissiveAlphaMul,
                    endEmissiveAlpha = p.tipEmissiveAlphaMul,
                    envelope = envelope,
                ),
            )

            if (p.mirroredUMul > 0.001f) {
                PooledCombatVfx.checkoutTrail(
                    engine, key,
                    TrailLeaseSpec(
                        location = line.from,
                        facingDeg = line.facing,
                        nodes = listOf(Vector2f(line.length, 0f), Vector2f(0f, 0f)),
                        startWidth = tipW,
                        endWidth = baseW,
                        coreColor = p.coreColor,
                        fringeColor = p.fringeColor,
                        startAlpha = p.tipAlphaMul * p.mirroredUMul,
                        endAlpha = p.baseAlphaMul * p.mirroredUMul,
                        startEmissiveAlpha = p.tipEmissiveAlphaMul * p.mirroredUMul,
                        endEmissiveAlpha = p.baseEmissiveAlphaMul * p.mirroredUMul,
                        envelope = envelope,
                    ),
                )
            }
        }

        spawnLayer(params.core, coreBaseWidth, coreTipWidth)
        spawnLayer(params.glow, glowBaseWidth, glowTipWidth)
    }
}
