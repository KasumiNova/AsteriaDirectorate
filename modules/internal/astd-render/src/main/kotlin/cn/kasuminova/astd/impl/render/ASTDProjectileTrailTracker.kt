package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.impl.render.ASTDProjectileTrailTracker.Companion.RECEDE_OF_LENGTH
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.base.api.resource.StaticTrailTracker
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin

class ASTDProjectileTrailTracker(
    private val projectile: DamagingProjectileAPI,
    private val spec: StaticTrailSpec,
) : StaticTrailTracker {

    private val headLead: Float = spec.headLeadWorld ?: 0f

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
        const val RECEDE_OF_LENGTH = 0.20f
    }
}

internal fun trailAnchor(center: Vector2f, facingRad: Double, forwardOffset: Float): Vector2f {
    val cosF = cos(facingRad).toFloat()
    val sinF = sin(facingRad).toFloat()
    return Vector2f(
        center.x + cosF * forwardOffset,
        center.y + sinF * forwardOffset,
    )
}
