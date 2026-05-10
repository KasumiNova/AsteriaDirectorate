package cn.kasuminova.astd.combat.effect.arc.rare

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.MathUtils
import java.awt.Color

/** SLT-3：连射窗口的“火力墙”——更密、更亮的蓝白脉冲粒子。 */
class Slt3OnFireEffect : OnFireEffectPlugin {

    private val flash = Color(225, 250, 255, 135)
    private val core = Color(200, 240, 255, 170)

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        val ship = weapon.ship ?: return
        val vel = ship.velocity
        val loc = projectile.location

        engine.addHitParticle(loc, vel, 45f, 0.9f, 0.07f, flash)

        // “脉冲墙”的节奏：每发做短促的亮粒子喷射
        val dir = projectile.facing
        for (i in 0 until 6) {
            val ang = dir + MathUtils.getRandomNumberInRange(-6f, 6f)
            val spd = MathUtils.getRandomNumberInRange(80f, 220f)
            val v = MathUtils.getPointOnCircumference(vel, spd, ang)
            engine.addSmoothParticle(
                MathUtils.getRandomPointInCircle(loc, 5f),
                v,
                MathUtils.getRandomNumberInRange(5f, 9f),
                1.4f,
                MathUtils.getRandomNumberInRange(0.12f, 0.2f),
                core,
            )
        }
    }
}
