package cn.kasuminova.astd.combat.effect.generic.projectile

import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 弹体相关通用粒子工具。
 */
object ProjectileVfxUtil {

    /** 以 center 为圆心在 radius 圆周上均匀撒一圈 smooth 粒子（带轻微角度抖动）。 */
    fun spawnRing(
        engine: CombatEngineAPI,
        center: Vector2f,
        baseVel: Vector2f?,
        radius: Float,
        particleCount: Int,
        size: Float,
        brightness: Float,
        duration: Float,
        color: Color,
    ) {
        val n = particleCount.coerceIn(6, 64)
        val vel = baseVel?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
        for (i in 0 until n) {
            val ang = (i * (360f / n.toFloat())) + MathUtils.getRandomNumberInRange(-2.5f, 2.5f)
            val loc = MathUtils.getPointOnCircumference(center, radius, ang)
            engine.addSmoothParticle(loc, vel, size, brightness, duration, color)
        }
    }
}
