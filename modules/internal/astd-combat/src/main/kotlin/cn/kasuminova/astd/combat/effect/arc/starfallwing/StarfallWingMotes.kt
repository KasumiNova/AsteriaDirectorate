package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/** 星羽追踪光矢的统一发射口：翼装弹幕、折跃齐射、断熵激发共用。 */
object StarfallWingMotes {

    private val log = AstdLog.logger

    /**
     * 从 [origin] 沿 [launchAngleDeg] 发射一枚光矢，初速沿外扩方向，0.25s 后加速追踪 [target]。
     * 伤害按难度系数结算（迟暮 120 / 砺刃 150 / 破晓 250 + 100 EMP）。
     */
    fun spawn(
        engine: CombatEngineAPI,
        source: ShipAPI,
        origin: Vector2f,
        launchAngleDeg: Float,
        target: ShipAPI?,
    ) {
        val launchVel = MathUtils.getPointOnCircumference(
            Vector2f(source.velocity), ImaginaryWingsTuning.MOTE_LAUNCH_SPEED, launchAngleDeg,
        )
        val spawned = engine.spawnProjectile(
            source, null, StarfallWingTuning.MOTE_WEAPON_ID, Vector2f(origin), launchAngleDeg, launchVel,
        ) as? MissileAPI
        if (spawned == null) {
            log.warn("[ASTD] 星羽光矢生成失败：spawnProjectile 未产出 MissileAPI: weapon=${StarfallWingTuning.MOTE_WEAPON_ID}")
            return
        }
        spawned.source = source
        spawned.missileAI = StarfallWingMoteAi(spawned, target)
        spawned.empResistance = 10000
        spawned.armingTime = 0f
        spawned.damageAmount = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.MOTE_DAMAGE, source.owner == 0)
        spawned.maxFlightTime = ImaginaryWingsTuning.MOTE_FLIGHT_TIME
        spawned.setNoGlowTime(999f)
        spawned.isNoFlameoutOnFizzling = true
        spawned.interruptContrail()
        spawned.spriteAlphaOverride = 0f
        spawned.glowRadius = 0f
        // 脚本 spawn 的弹体不触发 onFireEffect，拖尾必须显式登记。
        ProjectileVfxDriverPlugin.track(engine, spawned, StarfallWingTuning.MOTE_SPEC_ID)
        StarfallWingVfx.spawnMoteSplitFlare(engine, Vector2f(spawned.location))
    }
}
