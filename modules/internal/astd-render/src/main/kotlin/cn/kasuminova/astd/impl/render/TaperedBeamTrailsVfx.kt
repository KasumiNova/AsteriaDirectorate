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

/**
 * 复用组件：用池化 TrailEntity 画“带 taper 的光束”（core + glow，可选 mirrored-U 叠加）。
 *
 * 说明：
 * - 适用于 ship system / combat plugin 等“没有 BeamAPI”的场景；
 * - 也可作为短寿命 beam 的构建块（调用方用 interval 周期性刷新即可）；
 * - 实体走 PooledCombatVfx 池化租约的一次性包络检出（防 renderEntityMap 滞留泄漏：
 *   引力坍缩微束 12 条/0.03s 是历史最大泄漏源）；几何/颜色/alpha/包络与旧逐次新建实体逐字一致，
 *   包络到期自动泊车归还。池不可用/池满拒发时本段视觉缺席（池侧已记 WARN）。
 */
internal object TaperedBeamTrailsVfx {

    private const val CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val FRINGE_SPRITE = "graphics/fx/beamfringeb.png"

    /** 单 mixPower 层的池初始容量（微束典型峰值：6 条/层/0.03s × 寿命 0.1s ≈ 20 条/束，并发 4 束取 96；池满按需扩容，硬上限默认 8×）。 */
    private const val POOL_CAPACITY = 96

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
            // 节点序对齐 FromCenter：node[0]=基部，START_* 作用于基部
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
                // U 镜像片：节点反转（node[0]=尖端），宽度/渐变参数随端反转（对齐 ReversedU 逐字）
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
