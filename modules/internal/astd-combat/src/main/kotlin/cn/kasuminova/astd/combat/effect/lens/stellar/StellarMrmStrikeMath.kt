package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmStrikeMath.leadPoint
import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmStrikeMath.lineCrossThreshold
import com.fs.starfarer.api.Global
import org.lwjgl.util.vector.Vector2f

object StellarMrmStrikeMath {
    private val log = Global.getLogger(StellarMrmStrikeMath::class.java)

    @Volatile
    private var warnedZeroMaxSpeed = false

    fun lineCrossThreshold(projMaxHitpoints: Float, h: Float): Float =
        if (projMaxHitpoints <= 0f || projMaxHitpoints.isNaN()) 0f else projMaxHitpoints * h

    fun shouldCross(targetHitpoints: Float, threshold: Float): Boolean = targetHitpoints < threshold

    fun fighterBonusDamage(panel: Float, fBonus: Float): Float = panel * fBonus

    fun weaponEmpDamage(panel: Float, wEmp: Float): Float = panel * wEmp

    fun explosionDamage(panel: Float, expMult: Float): Float = panel * expMult

    fun leadPoint(targetLoc: Vector2f, targetVel: Vector2f, dist: Float, missileMaxSpeed: Float): Vector2f {
        if (missileMaxSpeed <= 0f || missileMaxSpeed.isNaN()) {
            if (!warnedZeroMaxSpeed) {
                warnedZeroMaxSpeed = true
                log.warn("辉星导弹 maxSpeed 异常（$missileMaxSpeed），领先瞄准退回直瞄（后续同类异常不再重复告警）")
            }
            return Vector2f(targetLoc)
        }
        val t = dist / missileMaxSpeed
        return Vector2f(targetLoc.x + targetVel.x * t, targetLoc.y + targetVel.y * t)
    }
}
