package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.impl.render.ASTDProjectileTrailTracker.Companion.RECEDE_OF_LENGTH
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.base.api.resource.StaticTrailTracker
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin

/**
 * 弹体 Static Trail 跟踪器：BoxUtil Static Trail 系统每帧回调，向系统上报弹体锚点位置/朝向。
 *
 * 职责：
 * - 锚点 = 弹体前端（projectile.location = 原版螺栓贴图视觉头部）沿朝向提前（headLead − recede）；
 * - headLead 缺省 0，即锚点默认压在螺栓头部；
 * - recede 缺省取弹体 spec.length × [RECEDE_OF_LENGTH]（带体亮端略退入螺栓覆盖区，弹头尖露出）；
 * - 弹体消亡（wasRemoved/isExpired）→ [StaticTrailTracker.Result.destroy]，带体按三段时长
 *   自然播完（尾先头后）。**不含 isFading**：超射程/命中后的淡出期弹体仍在飞行，带体继续跟随，
 *   直到弹体真正移出引擎才开始消散（2026-09 实机裁定）。
 *
 * 几何数学为文件内纯函数（[trailAnchor]），供单测直接调用。
 */
class ASTDProjectileTrailTracker(
    private val projectile: DamagingProjectileAPI,
    private val spec: StaticTrailSpec,
) : StaticTrailTracker {

    /** 锚点前移量（世界单位）：DSL 显式值优先，否则为 0（弹体前端即螺栓头部，无需再前移）。 */
    private val headLead: Float = spec.headLeadWorld ?: 0f

    /** 带体退距（世界单位）：DSL 显式值优先，否则取弹体长度 × [RECDE_OF_LENGTH]。 */
    private val recede: Float = spec.recede
        ?: ((projectile.projectileSpec?.length ?: 0f) * RECEDE_OF_LENGTH)

    override fun advance(amount: Float, elapsedTime: Float, callback: StaticTrailTracker.Result) {
        if (callback.isExpired) return
        if (projectile.wasRemoved() || projectile.isExpired) {
            callback.destroy()
            return
        }
        val facingRad = Math.toRadians(projectile.facing.toDouble())
        val anchor = trailAnchor(
            center = projectile.location,
            facingRad = facingRad,
            forwardOffset = headLead - recede,
        )
        if (callback.isNotRecommendedRecordsCurrent(anchor)) {
            callback.pauseOnce()
            return
        }
        callback.currentLocation = anchor
        callback.setCurrentFacing(cos(facingRad).toFloat(), sin(facingRad).toFloat())
    }

    companion object {
        /** recede 缺省比例：弹体长度 ×0.2——退距过大时新段与旧亮端拼接处易出现视觉跳变。 */
        const val RECEDE_OF_LENGTH = 0.20f
    }
}

/** 拖尾锚点：弹体前端（location）沿朝向提前 [forwardOffset]。 */
internal fun trailAnchor(center: Vector2f, facingRad: Double, forwardOffset: Float): Vector2f {
    val cosF = cos(facingRad).toFloat()
    val sinF = sin(facingRad).toFloat()
    return Vector2f(
        center.x + cosF * forwardOffset,
        center.y + sinF * forwardOffset,
    )
}
