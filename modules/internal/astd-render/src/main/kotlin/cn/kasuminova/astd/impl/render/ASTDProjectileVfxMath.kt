package cn.kasuminova.astd.impl.render

import kotlin.math.max
import kotlin.math.min

/**
 * 弹体 VFX 共享数值函数。
 *
 * Static Trail 迁移（2026-09）后仅保留 [ASTDProjectileVfxLayout] 预览函数族仍在消费的子集；
 * 噪声/ribbon 波形/历史采样等旧渲染栈函数已随 texTrail/head 体系删除。
 */
object ASTDProjectileVfxMath {
    fun clamp(value: Float, min: Float, max: Float): Float = min(max, max(min, value))

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp((x - edge0) / max(edge1 - edge0, 0.0001f), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun hermite01(t: Float, m0: Float, m1: Float): Float {
        val u = clamp(t, 0f, 1f)
        val u2 = u * u
        val u3 = u2 * u
        return (u3 - 2f * u2 + u) * m0 + (-2f * u3 + 3f * u2) + (u3 - u2) * m1
    }

    fun beamAlpha(dissolveValue: Float): Float = clamp((1f - dissolveValue) * (1f - dissolveValue) * (1f - dissolveValue * 0.48f), 0f, 1f)

    fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t
}
