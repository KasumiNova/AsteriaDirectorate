package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import java.awt.Color

/**
 * 复用组件：用 BoxUtil 的 TrailEntity 画“带 taper 的光束”（core + glow，可选 mirrored-U 叠加）。
 *
 * 说明：
 * - 适用于 ship system / combat plugin 等“没有 BeamAPI”的场景；
 * - 也可作为短寿命 beam 的构建块（调用方用 interval 周期性刷新即可）。
 */
internal object TaperedBeamTrailsVfx {

    private const val CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val FRINGE_SPRITE = "graphics/fx/beamfringeb.png"

    data class LayerParams(
        val coreColor: Color,
        val fringeColor: Color,
        val baseAlphaMul: Float,
        val tipAlphaMul: Float,
        val baseEmissiveAlphaMul: Float,
        val tipEmissiveAlphaMul: Float,
        val mixPower: Float,
        /** 若 >0，则生成一条 mirrored-U 叠加，并把 alpha/emissive 乘以该系数。 */
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
        from: org.lwjgl.util.vector.Vector2f,
        to: org.lwjgl.util.vector.Vector2f,
        coreBaseWidth: Float,
        coreTipWidth: Float,
        glowBaseWidth: Float,
        glowTipWidth: Float,
        params: BeamParams,
    ) {
        val line = BeamLineUtil.fromPoints(from, to) ?: return

        val coreSprite = try {
            Global.getSettings().getSprite(CORE_SPRITE)
        } catch (_: Throwable) {
            return
        }
        val fringeSprite = try {
            Global.getSettings().getSprite(FRINGE_SPRITE)
        } catch (_: Throwable) {
            return
        }

        // 确保 BoxUtil ready
        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (_: Throwable) {
        }

        fun spawnLayer(p: LayerParams, baseW: Float, tipW: Float) {
            val main = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine,
                location = line.from,
                facing = line.facing,
                length = line.length,
                baseWidth = baseW,
                tipWidth = tipW,
                coreColor = p.coreColor,
                fringeColor = p.fringeColor,
                coreSprite = coreSprite,
                fringeSprite = fringeSprite,
                layer = params.layer,
                full = 9999f,
                baseAlphaMul = p.baseAlphaMul,
                tipAlphaMul = p.tipAlphaMul,
                baseEmissiveAlphaMul = p.baseEmissiveAlphaMul,
                tipEmissiveAlphaMul = p.tipEmissiveAlphaMul,
                mixPower = p.mixPower,
            )

            val mirrored = if (p.mirroredUMul > 0.001f) {
                BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
                    engine = engine,
                    location = line.from,
                    facing = line.facing,
                    length = line.length,
                    baseWidth = baseW,
                    tipWidth = tipW,
                    coreColor = p.coreColor,
                    fringeColor = p.fringeColor,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = params.layer,
                    full = 9999f,
                    baseAlphaMul = p.baseAlphaMul * p.mirroredUMul,
                    tipAlphaMul = p.tipAlphaMul * p.mirroredUMul,
                    baseEmissiveAlphaMul = p.baseEmissiveAlphaMul * p.mirroredUMul,
                    tipEmissiveAlphaMul = p.tipEmissiveAlphaMul * p.mirroredUMul,
                    mixPower = p.mixPower,
                )
            } else {
                null
            }

            listOf(main, mirrored).forEach { e ->
                if (e == null) return@forEach
                try {
                    e.setGlobalTimer(
                        params.fadeIn.coerceAtLeast(0f),
                        params.full.coerceAtLeast(0.01f),
                        params.fadeOut.coerceAtLeast(0f)
                    )
                } catch (_: Throwable) {
                }
            }
        }

        spawnLayer(params.core, coreBaseWidth, coreTipWidth)
        spawnLayer(params.glow, glowBaseWidth, glowTipWidth)
    }
}
