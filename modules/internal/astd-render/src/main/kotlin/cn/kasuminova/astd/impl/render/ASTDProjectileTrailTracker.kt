package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.base.api.resource.StaticTrailTracker
import org.lwjgl.util.vector.Vector2f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 弹体 Static Trail 跟踪器：BoxUtil Static Trail 系统每帧回调，向系统上报弹体锚点位置/朝向。
 *
 * 职责（旧自研驱动的残骸语义全部收在这里）：
 * - 锚点 = 弹体中心沿朝向提前（headLead − recede），再叠加 wobble 横向偏移（蛇行观感）；
 * - headLead 缺省取弹体 spec.length/2，对齐原版螺栓贴图视觉头部；
 * - 弹体消亡（wasRemoved/isExpired/isFading）→ [StaticTrailTracker.Result.destroy]，带体按三段时长
 *   自然播完（尾先头后），不再有加速消散窗口与前飞补偿（2026-09 迁移裁定）。
 *
 * 几何数学全部为文件内纯函数（[trailAnchor]/[wobbleOffset]），供单测直接调用。
 */
class ASTDProjectileTrailTracker(
    private val projectile: DamagingProjectileAPI,
    private val spec: StaticTrailSpec,
) : StaticTrailTracker {

    /** 锚点前移量（世界单位）：DSL 显式值优先，否则取弹体 spec.length/2（对齐原版螺栓视觉头部）。 */
    private val headLead: Float = spec.headLeadWorld
        ?: ((projectile.projectileSpec?.length ?: 0f) * 0.5f)

    override fun advance(amount: Float, elapsedTime: Float, callback: StaticTrailTracker.Result) {
        if (callback.isExpired) return
        if (projectile.wasRemoved() || projectile.isExpired || projectile.isFading) {
            callback.destroy()
            return
        }
        val facingRad = Math.toRadians(projectile.facing.toDouble())
        val anchor = trailAnchor(
            center = projectile.location,
            facingRad = facingRad,
            forwardOffset = headLead - spec.recede,
            lateralOffset = wobbleOffset(spec, elapsedTime),
        )
        if (callback.isNotRecommendedRecordsCurrent(anchor)) {
            callback.pauseOnce()
            return
        }
        callback.setCurrentLocation(anchor)
        callback.setCurrentFacing(cos(facingRad).toFloat(), sin(facingRad).toFloat())
    }
}

/** 拖尾锚点：弹体中心沿朝向提前 [forwardOffset]，再沿法向偏移 [lateralOffset]（wobble）。 */
internal fun trailAnchor(center: Vector2f, facingRad: Double, forwardOffset: Float, lateralOffset: Float): Vector2f {
    val cosF = cos(facingRad).toFloat()
    val sinF = sin(facingRad).toFloat()
    return Vector2f(
        center.x + cosF * forwardOffset - sinF * lateralOffset,
        center.y + sinF * forwardOffset + cosF * lateralOffset,
    )
}

/**
 * wobble 横向偏移（世界单位）：振幅 × sin(2π × 逻辑秒 × 爬行频率 + 相位)，爬行频率 = scroll/波长。
 * 振幅 ≤ 0 或爬行静止且相位为 0 时恒 0（不扰动）。记录时刻生效——已落节点不回溯，带体呈蛇行而非整体摆动。
 */
internal fun wobbleOffset(spec: StaticTrailSpec, elapsedSeconds: Float): Float {
    if (spec.wobbleAmplitude <= 0f) return 0f
    val frequency = if (spec.wobbleWavelength > 0f) spec.wobbleScroll / spec.wobbleWavelength else 0f
    return spec.wobbleAmplitude * sin(2f * PI.toFloat() * elapsedSeconds * frequency + spec.wobblePhase)
}
