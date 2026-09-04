package cn.kasuminova.astd.combat.effect.arc.signature.aod7

import cn.kasuminova.astd.renderer.effect.system.ArcFlareOverdriveVisualState
import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * AOD-7 弧光过驱炮：开火 VFX。
 *
 * 每次开火触发：
 * - 扭曲效果（DistortionEntity）
 * - 爆炸烟雾扩散粒子
 * - 锥形爆发椭圆环（参考 GCP）
 * - 光环闪光
 *
 * 所有颜色跟随 ArcFlareOverdriveVisualState。
 */
class Aod7OnFireEffect : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return
        val ship = weapon.ship ?: return
        val loc = Vector2f(weapon.location)
        val facing = weapon.currAngle
        val vel = projectile.velocity ?: Vector2f()

        val overdriveLevel = try {
            ArcFlareOverdriveVisualState.getLevel(ship, engine)
        } catch (_: Throwable) { 0f }

        val core = ArcFlareOverdriveVisualState.lerpColor(
            COLD_CORE, ArcFlareOverdriveVisualState.hotCore, overdriveLevel, 235,
        )
        val fringe = ArcFlareOverdriveVisualState.lerpColor(
            COLD_FRINGE, ArcFlareOverdriveVisualState.hotFringe, overdriveLevel, 210,
        )
        val smoke = Color(fringe.red, fringe.green, fringe.blue, 75)

        spawnDistortion(engine, loc)
        spawnSmoke(engine, loc, vel, facing, smoke)
        spawnConeBurst(engine, loc, facing, fringe)
        spawnHalo(engine, loc, core, fringe)
    }

    companion object {
        private val COLD_CORE = Color(210, 235, 255)
        private val COLD_FRINGE = Color(130, 200, 255)

        private fun spawnDistortion(engine: CombatEngineAPI, loc: Vector2f) {
            try {
                BoxUtilCombatVfx.ensureReady(engine)
                val e = DistortionEntity()
                e.setGlobalTimer(0.03f, 0.04f, 0.16f)
                e.setInnerFull(0.30f, 0.30f)
                e.setInnerHardness(0.75f)
                e.setRingHardness(0.50f)
                e.setSizeIn(12f, 12f)
                e.setSizeFull(38f, 38f)
                e.setSizeOut(70f, 70f)
                e.setPowerIn(0f)
                e.setPowerFull(0.32f)
                e.setPowerOut(0f)
                e.setLocation(Vector2f(loc))
                CombatRenderingManager.addEntity(e)
            } catch (_: Throwable) {}
        }

        private fun spawnSmoke(engine: CombatEngineAPI, loc: Vector2f, vel: Vector2f, facing: Float, smoke: Color) {
            val backDir = facing + 180f
            repeat(6) {
                val ang = backDir + MathUtils.getRandomNumberInRange(-35f, 35f)
                val spd = MathUtils.getRandomNumberInRange(40f, 110f)
                val v = MathUtils.getPointOnCircumference(vel, spd, ang)
                val p = MathUtils.getRandomPointInCircle(loc, 10f)
                try {
                    engine.addNebulaParticle(
                        p, v,
                        MathUtils.getRandomNumberInRange(16f, 32f),
                        1.3f, 0.08f, 0.22f, 0.7f,
                        smoke, true,
                    )
                } catch (_: Throwable) {}
            }
        }

        private fun spawnConeBurst(engine: CombatEngineAPI, loc: Vector2f, facing: Float, fringe: Color) {
            try {
                OglEllipseRingRenderer.spawn(
                    engine,
                    OglEllipseRingRenderer.RingSpec(
                        center = loc,
                        facing = facing,
                        aSideHalf = 32f,
                        bAlongHalf = 16f,
                        duration = 0.22f,
                        color = Color(fringe.red, fringe.green, fringe.blue, 140),
                        lineWidthPx = 1.35f,
                        segments = 72,
                        expandSpeed = 280f,
                        tangentialSpeed = 0f,
                    ),
                )
            } catch (_: Throwable) {}
            try {
                OglEllipseRingRenderer.spawn(
                    engine,
                    OglEllipseRingRenderer.RingSpec(
                        center = loc,
                        facing = facing + 40f,
                        aSideHalf = 22f,
                        bAlongHalf = 11f,
                        duration = 0.18f,
                        color = Color(fringe.red, fringe.green, fringe.blue, 90),
                        lineWidthPx = 1.15f,
                        segments = 64,
                        expandSpeed = 350f,
                        tangentialSpeed = -2f,
                    ),
                )
            } catch (_: Throwable) {}
        }

        private fun spawnHalo(engine: CombatEngineAPI, loc: Vector2f, core: Color, fringe: Color) {
            try {
                engine.addHitParticle(loc, Vector2f(), 90f, 1.4f, 0.10f, core)
            } catch (_: Throwable) {}
            try {
                engine.addSmoothParticle(loc, Vector2f(), 160f, 1.0f, 0.20f, fringe)
            } catch (_: Throwable) {}
        }
    }
}
