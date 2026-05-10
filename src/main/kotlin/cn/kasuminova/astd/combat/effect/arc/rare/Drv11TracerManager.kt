package cn.kasuminova.astd.combat.effect.arc.rare

import cn.kasuminova.astd.renderer.boxutil.BoxUtilProjectileTrails
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileTracerManager
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import java.awt.Color

/** DRV-11：给弹体挂一个 BoxUtil TrailEntity，让曳光随弹体飞行。 */
internal object Drv11TracerManager {

    // `.proj` 里 astd_drv11_slug 的 fadeTime=0.2；这里略加一点余量，避免浮点/帧率导致“快一帧消失”的观感。
    private val tracerOptions = ProjectileTracerManager.Options(
        fadeOutOnProjectileFadingSeconds = 0.22f,
        fadeOutOnProjectileRemovedSeconds = 0.08f,
        pendingTimeoutSeconds = 0.75f,
    )

    // DRV-11 的视觉样式（可按需要继续调参）。
    private val style
        get() = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 连接处（弹体位置）尽量“无缝”：主曳光头宽 = 弹头锥形根部宽。
            joinWidth = 10.0f,

            // 主曳光：亮度整体下调约 15%
            tracerLength = 240f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.187f,
            tracerTailEmissiveAlphaMul = 0.6375f,
            tracerHeadAlphaMul = 1.0625f,
            tracerHeadEmissiveAlphaMul = 2.04f,
            tracerMixPower = 2.6f,

            // 弹头锥：尖端更淡、根部更亮，并只柔化尖端
            coneEnabled = true,
            coneLength = 14f,
            coneTipWidth = 1.0f,
            coneTipAlphaMul = 0.32f,
            coneTipEmissiveAlphaMul = 1.05f,
            coneMixPower = 3.0f,
            coneFillStartAlpha = 0f,
            coneFillStartFactor = 0.72f,
            coneFillEndAlpha = 1f,
            coneFillEndFactor = 1f,

            // 飞行粒子散发：用作拖尾补偿（稳定，不依赖多节点 Trail）。
            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                // 先调到明显可见，确认效果后再往下压。
                particlesPerSecond = 60f,
                // 超射程淡出后停止生成粒子（避免尾焰在弹体消失时还在喷）。
                emitWhileFading = false,
                // 如果仍看不到，把这个改成 true：会喷非常亮的紫色大粒子用于定位问题。
                debugForceVisible = false,
                // 不要继承太多弹速，否则粒子会和弹体一起向前飞、被曳光淹没。
                inheritVelocityMul = 0.03f,
                // 颜色范围：冷蓝 -> 蓝白（每个通道分别随机）
                colorMin = Color(110, 175, 255, 130),
                colorMax = Color(190, 240, 255, 220),
                // 大小范围
                sizeMin = 10f,
                sizeMax = 20f,
                // 亮度/寿命：略拉长一点，避免“存在但太短看不见”。
                brightnessMin = 1.25f,
                brightnessMax = 2.1f,
                durationMin = 0.22f,
                durationMax = 0.42f,
                // 速度/散射
                // 这里的 speed 是围绕“弹体后方方向”的额外速度；要足够大才能形成明显尾迹。
                speedMin = 60f,
                speedMax = 160f,
                spreadArc = 34f,
                spawnJitterRadius = 7f,
                behindDistance = 10f,
            ),
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
