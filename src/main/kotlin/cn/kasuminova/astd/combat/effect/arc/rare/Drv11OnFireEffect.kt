package cn.kasuminova.astd.combat.effect.arc.rare

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/** DRV-11：炮口/开火粒子（曳光本体由 projectileSpecId 分发的模板负责）。 */
class Drv11OnFireEffect : OnFireEffectPlugin {

    private val flash = Color(210, 245, 255, 120)
    private val sparks = Color(170, 225, 255, 170)
    private val smoke = Color(80, 110, 130, 70)

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        val ship = weapon.ship ?: return
        // 用弹体速度做基准，避免“效果整体黏在炮口/船体速度上”的观感。
        val vel = projectile.velocity

        val pLoc = projectile.location
        engine.spawnExplosion(pLoc, vel, flash, 28f, 0.12f)
        engine.addHitParticle(pLoc, vel, 60f, 1f, 0.08f, flash)

        // 速度线：沿弹道方向撒少量亮粒子，模拟高速曳光
        val dir = projectile.facing
        for (i in 0 until 10) {
            val ang = dir + MathUtils.getRandomNumberInRange(-2f, 2f)
            val spd = MathUtils.getRandomNumberInRange(120f, 320f)
            val v = MathUtils.getPointOnCircumference(vel, spd, ang)
            engine.addSmoothParticle(
                MathUtils.getRandomPointInCircle(pLoc, 6f),
                v,
                MathUtils.getRandomNumberInRange(3f, 6f),
                1.6f,
                MathUtils.getRandomNumberInRange(0.15f, 0.25f),
                sparks,
            )
        }

        // 真空/散热的“蒸汽感”——非常克制（只做一点点）
        val back = MathUtils.getPointOnCircumference(null, 60f, dir + 180f)
        for (i in 0 until 4) {
            val loc = Vector2f(pLoc.x + back.x, pLoc.y + back.y)
            val v = MathUtils.getPointOnCircumference(vel, MathUtils.getRandomNumberInRange(10f, 40f), dir + 180f)
            engine.addNebulaParticle(loc, v, MathUtils.getRandomNumberInRange(10f, 18f), 1.3f, 0.1f, 0.25f, 0.8f, smoke, true)
        }
    }
}
