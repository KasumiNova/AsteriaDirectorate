package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionPalette
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ProximityExplosionEffect
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class GravityRiftMineExplosion : ProximityExplosionEffect {

    override fun onExplosion(explosion: DamagingProjectileAPI, originalProjectile: DamagingProjectileAPI) {
        val engine = Global.getCombatEngine()
        val sizeMult = originalProjectile.customData
            ?.get(RiftCascadeMineExplosion.SIZE_MULT_KEY) as? Float ?: 1f
        explosion.addDamagedAlready(explosion.source)

        val center = Vector2f(explosion.location)
        val radius = 15f * sizeMult

        RiftExplosionVfx.riftExplosion(
            engine, center, radius, palette = RiftExplosionPalette(
                border = Color(255, 100, 100, 100),
                underglow = Color(200, 60, 60, 100),
                windup = Color(200, 60, 60, 50),
                black = Color(255, 200, 200, 100)
            )
        )
    }

}
