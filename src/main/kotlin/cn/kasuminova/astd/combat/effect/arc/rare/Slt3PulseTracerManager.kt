package cn.kasuminova.astd.combat.effect.arc.rare

import cn.kasuminova.astd.renderer.boxutil.BoxUtilProjectileTrails
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileTracerManager
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import java.awt.Color

/**
 * SLT-3：给脉冲弹体加一条更“干净”的能量拖尾（比 DRV-11 更短、更柔和）。
 *
 * 说明：这里保持弹体原贴图不变，仅额外挂 trail，避免把 SLT-3 也改成“纯代码弹头”。
 */
internal object Slt3PulseTracerManager {

    private val tracerOptions = ProjectileTracerManager.Options(
        // `.proj` fadeTime=0.2；稍加余量，避免浮点/帧率导致“快一帧消失”的观感。
        fadeOutOnProjectileFadingSeconds = 0.22f,
        fadeOutOnProjectileRemovedSeconds = 0.08f,
        pendingTimeoutSeconds = 0.75f,
    )

    private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
        // 更偏蓝白、偏“脉冲墙”
        coreColor = Color(235, 252, 255, 255),
        fringeColor = Color(150, 225, 255, 255),

        joinWidth = 9.0f,

        tracerEnabled = true,
        tracerLength = 110f,
        tracerTailWidth = 2.3f,
        tracerHeadWidth = 9.0f,
        tracerTailAlphaMul = 0.22f,
        tracerHeadAlphaMul = 0.95f,
        tracerTailEmissiveAlphaMul = 0.85f,
        tracerHeadEmissiveAlphaMul = 1.85f,
        tracerMixPower = 2.45f,

        // SLT-3 自带脉冲弹头贴图，这里不额外画锥形
        coneEnabled = false,
    )

    private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

    fun onFire(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
        ProjectileTracerManager.track(
            engine = engine,
            projectile = projectile,
            options = tracerOptions,
            factory = factory,
        )
    }
}
