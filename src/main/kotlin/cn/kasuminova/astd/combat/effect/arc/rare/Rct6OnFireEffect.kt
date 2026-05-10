package cn.kasuminova.astd.combat.effect.arc.rare

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.MathUtils
import java.awt.Color

/** RCT-6：发射时挂载“末端修正”导弹 AI，并加一点发射火光。 */
class Rct6OnFireEffect : OnFireEffectPlugin {

    private val flash = Color(220, 245, 255, 110)

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        val ship = weapon.ship ?: return

        // 末端修正：只对导弹生效
        val missile = projectile as? MissileAPI
        if (missile != null) {
            missile.missileAI = Rct6TerminalCorrectionAI(missile)
        }

        // 发射火光（蓝白偏离子）
        val loc = projectile.location
        val vel = ship.velocity
        engine.spawnExplosion(loc, vel, flash, 30f, 0.18f)
        engine.addHitParticle(loc, vel, 60f, 1f, 0.12f, flash)

        // 少量喷焰粒子
        for (i in 0 until 6) {
            val ang = projectile.facing + 180f + MathUtils.getRandomNumberInRange(-12f, 12f)
            val v = MathUtils.getPointOnCircumference(vel, MathUtils.getRandomNumberInRange(30f, 140f), ang)
            engine.addSmoothParticle(
                MathUtils.getRandomPointInCircle(loc, 4f),
                v,
                MathUtils.getRandomNumberInRange(6f, 10f),
                1.2f,
                MathUtils.getRandomNumberInRange(0.2f, 0.35f),
                Color(200, 230, 255, 150)
            )
        }
    }
}
