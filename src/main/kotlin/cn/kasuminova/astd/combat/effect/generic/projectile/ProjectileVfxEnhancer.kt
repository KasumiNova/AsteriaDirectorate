package cn.kasuminova.astd.combat.effect.generic.projectile

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.loading.ProjectileSpawnType

/**
 * 通用弹体特效增强器。
 *
 * 目标：在现有 projectile 视觉外层叠加“烟雾消散线 + 深色尾端 + 少量装饰闪丝”的共通层，
 * 让所有已经接入 [ProjectileTracerManager] 的弹体都能共享同一套 BoxUtil 风格增强。
 *
 * 使用方式：
 * - 轨迹管理器在创建视觉后调用 [decorate]。
 * - 对已经自带完整增强层的弹体，先写入 [ProjectileVfxKeys.PROJECTILE_VFX_COMMON_FX_SKIP]。
 * - 若需要在局部场景手动叠加，也可以调用 [wrap]。
 */
internal object ProjectileVfxEnhancer {

    /**
     * 通用增强的视觉档位。
     *
     * 说明：
     * - STANDARD：默认能量弹体。
     * - BEAM：更长、更稳定，适合 beam / 长条弹体。
     * - MISSILE：稍短，减少侧线宽度，保留尾迹清晰度。
     * - MINE：更克制，优先保留烟雾带与少量细线。
     * - SINGULARITY：非常克制，只保留少量暗色消散层。
     */
    internal enum class Profile {
        STANDARD,
        BEAM,
        MISSILE,
        MINE,
        SINGULARITY,
    }

    /**
     * 给已有 [ProjectileVisual] 再叠加一层通用烟雾特效。
     *
     * @return 叠加后的 visual；当增强层创建失败或被显式跳过时，返回原 visual。
     */
    fun decorate(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        visual: ProjectileVisual,
    ): ProjectileVisual {
        if (shouldSkip(projectile)) return visual

        val profile = profileFor(projectile)
        val fx = createCommonVisual(engine, projectile, profile) ?: return visual
        return CompositeProjectileVisual(listOf(visual, fx))
    }

    /**
     * 将现有 factory 包装成“基础 visual + 通用增强层”的组合工厂。
     *
     * 适用场景：某些调用点希望显式控制是否增强，或希望根据预设档位选择不同强度。
     */
    fun wrap(
        base: ProjectileVisualFactory,
        profile: Profile = Profile.STANDARD,
    ): ProjectileVisualFactory {
        return ProjectileVisualFactory { engine, projectile ->
            val visual = base.create(engine, projectile) ?: return@ProjectileVisualFactory null
            if (shouldSkip(projectile)) return@ProjectileVisualFactory visual

            val fx = createCommonVisual(engine, projectile, profile) ?: return@ProjectileVisualFactory visual
            CompositeProjectileVisual(listOf(visual, fx))
        }
    }

    private fun shouldSkip(projectile: DamagingProjectileAPI): Boolean {
        return projectile.customData[ProjectileVfxKeys.PROJECTILE_VFX_COMMON_FX_SKIP] == true
    }

    private fun profileFor(projectile: DamagingProjectileAPI): Profile {
        val specId = projectile.projectileSpecId.lowercase()
        return when {
            "singularity" in specId || "rift" in specId -> Profile.SINGULARITY
            "mine" in specId || "grid" in specId -> Profile.MINE
            projectile.spawnType == ProjectileSpawnType.MISSILE -> Profile.MISSILE
            "beam" in specId || "pulse" in specId -> Profile.BEAM
            else -> Profile.STANDARD
        }
    }

    private fun createCommonVisual(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        profile: Profile,
    ): ProjectileVisual? {
        val spec = projectile.projectileSpec
        val core = spec?.coreColor ?: ProjectileVfxPalette.defaultCore
        val fringe = spec?.fringeColor ?: ProjectileVfxPalette.defaultFringe
        val w = (spec?.width ?: 8f).coerceAtLeast(1f)
        val speed = projectile.moveSpeed.coerceAtLeast(0f)
        val spawnType = spec?.spawnType ?: projectile.spawnType

        val lengthScale = when (profile) {
            Profile.STANDARD -> 1f
            Profile.BEAM -> 1.15f
            Profile.MISSILE -> 0.82f
            Profile.MINE -> 0.58f
            Profile.SINGULARITY -> 0.48f
        } * when (spawnType) {
            ProjectileSpawnType.MISSILE -> 0.92f
            else -> 1f
        }

        val sideEnabled = when (profile) {
            Profile.SINGULARITY -> speed >= 28f
            Profile.MINE -> speed >= 14f
            else -> true
        }
        val smokeEnabled = true
        val decorEnabled = profile == Profile.STANDARD || profile == Profile.MISSILE || profile == Profile.BEAM

        val sideLen = (speed * 0.31f * lengthScale).coerceIn(
            when (profile) {
                Profile.SINGULARITY -> 70f
                Profile.MINE -> 60f
                else -> 120f
            },
            when (profile) {
                Profile.SINGULARITY -> 220f
                Profile.MINE -> 200f
                Profile.BEAM -> 380f
                else -> 300f
            },
        )
        val smokeLen = (sideLen * when (profile) {
            Profile.SINGULARITY -> 1.08f
            Profile.MINE -> 1.20f
            Profile.BEAM -> 1.42f
            else -> 1.34f
        }).coerceIn(120f, when (profile) {
            Profile.SINGULARITY -> 260f
            Profile.MINE -> 320f
            else -> 430f
        })

        val sideOffset = (w * when (profile) {
            Profile.BEAM -> 0.74f
            Profile.SINGULARITY -> 0.58f
            Profile.MINE -> 0.68f
            else -> 0.86f
        }).coerceIn(3.6f, 13f)

        val sideHeadW = (w * when (profile) {
            Profile.BEAM -> 0.30f
            Profile.SINGULARITY -> 0.22f
            Profile.MINE -> 0.26f
            else -> 0.34f
        }).coerceIn(1.2f, 4.2f)
        val smokeHeadW = (w * when (profile) {
            Profile.BEAM -> 1.10f
            Profile.SINGULARITY -> 0.95f
            Profile.MINE -> 1.00f
            else -> 1.28f
        }).coerceIn(5.2f, 22f)

        val sideHeadAlpha = when (profile) {
            Profile.BEAM -> 0.30f
            Profile.SINGULARITY -> 0.18f
            Profile.MINE -> 0.24f
            else -> 0.38f
        }
        val smokeHeadAlpha = when (profile) {
            Profile.SINGULARITY -> 0.08f
            Profile.MINE -> 0.12f
            else -> 0.18f
        }
        val decorChance = when (profile) {
            Profile.BEAM -> 3.0f
            Profile.MISSILE -> 5.0f
            Profile.MINE -> 0.8f
            Profile.SINGULARITY -> 0.0f
            else -> 5.5f
        }

        return BoxUtilSmokySideTrailProjectileVisual.create(
            engine = engine,
            projectile = projectile,
            style = BoxUtilSmokySideTrailProjectileVisual.Style(
                enabled = true,
                sideLinesEnabled = sideEnabled,
                smokeRibbonEnabled = smokeEnabled,
                decorEnabled = decorEnabled,
                nodeCount = when (profile) {
                    Profile.BEAM -> 18
                    Profile.SINGULARITY -> 10
                    Profile.MINE -> 10
                    else -> 14
                },
                sideLength = sideLen,
                smokeLength = smokeLen,
                sideOffset = sideOffset,
                smokeOffset = 0f,
                sideHeadWidth = sideHeadW,
                sideTailWidth = (sideHeadW * 0.28f).coerceAtLeast(0.45f),
                smokeHeadWidth = smokeHeadW * 1.25f,
                smokeTailWidth = (smokeHeadW * 0.44f).coerceAtLeast(2.2f),
                headCoreColor = ProjectileVfxPalette.headCoreColor(core),
                headFringeColor = ProjectileVfxPalette.headFringeColor(fringe),
                smokeCoreColor = ProjectileVfxPalette.smokeCoreColor(core),
                smokeFringeColor = ProjectileVfxPalette.smokeFringeColor(fringe),
                tailCoreColor = ProjectileVfxPalette.tailCoreColor(core),
                tailFringeColor = ProjectileVfxPalette.tailFringeColor(fringe),
                sideHeadAlpha = sideHeadAlpha,
                sideTailAlpha = when (profile) {
                    Profile.SINGULARITY -> 0.030f
                    Profile.MINE -> 0.050f
                    else -> 0.060f
                },
                sideHeadEmissive = when (profile) {
                    Profile.BEAM -> 0.34f
                    Profile.SINGULARITY -> 0.18f
                    else -> 0.42f
                },
                sideTailEmissive = when (profile) {
                    Profile.SINGULARITY -> 0.008f
                    Profile.MINE -> 0.012f
                    else -> 0.018f
                },
                smokeHeadAlpha = when (profile) {
                    Profile.SINGULARITY -> 0.10f
                    Profile.MINE -> 0.16f
                    Profile.BEAM -> 0.24f
                    Profile.MISSILE -> 0.28f
                    else -> 0.34f
                },
                smokeTailAlpha = when (profile) {
                    Profile.SINGULARITY -> 0.030f
                    Profile.MINE -> 0.045f
                    else -> 0.085f
                },
                smokeHeadEmissive = when (profile) {
                    Profile.SINGULARITY -> 0.06f
                    Profile.MINE -> 0.08f
                    else -> 0.12f
                },
                smokeTailEmissive = 0.0f,
                noiseAmplitude = when (profile) {
                    Profile.BEAM -> (w * 0.72f).coerceIn(4.2f, 10.0f)
                    Profile.SINGULARITY -> (w * 0.34f).coerceIn(2.2f, 5.0f)
                    Profile.MINE -> (w * 0.40f).coerceIn(2.8f, 6.0f)
                    else -> (w * 0.88f).coerceIn(5.2f, 12.5f)
                },
                noiseWavelength = sideLen * when (profile) {
                    Profile.BEAM -> 0.46f
                    Profile.SINGULARITY -> 0.62f
                    else -> 0.38f
                },
                noiseScrollSpeed = (speed * 0.055f).coerceIn(28f, when (profile) {
                    Profile.BEAM -> 84f
                    Profile.SINGULARITY -> 66f
                    else -> 108f
                }),
                textureSpeed = when (profile) {
                    Profile.BEAM -> -95f
                    Profile.SINGULARITY -> -68f
                    Profile.MINE -> -82f
                    else -> -120f
                },
                texturePixels = (sideLen * when (profile) {
                    Profile.BEAM -> 0.80f
                    Profile.SINGULARITY -> 0.66f
                    Profile.MINE -> 0.58f
                    else -> 0.70f
                }).coerceIn(60f, 220f),
                jitterPower = when (profile) {
                    Profile.SINGULARITY -> 0.016f
                    Profile.MINE -> 0.020f
                    else -> 0.050f
                },
                decorChancePerSecond = when (profile) {
                    Profile.SINGULARITY -> 0.0f
                    Profile.MINE -> 1.0f
                    Profile.BEAM -> 4.0f
                    Profile.MISSILE -> 6.5f
                    else -> 8.0f
                },
                decorLengthMin = (sideLen * 0.16f).coerceIn(20f, 46f),
                decorLengthMax = (sideLen * 0.40f).coerceIn(48f, 120f),
                decorWidth = (w * 0.22f).coerceIn(1.0f, 2.4f),
                decorLife = when (profile) {
                    Profile.SINGULARITY -> 0.08f
                    Profile.MINE -> 0.10f
                    else -> 0.14f
                },
            ),
        )
    }
}

/** 通用弹体色彩基线。 */
internal object ProjectileVfxPalette {
    val defaultCore: java.awt.Color = java.awt.Color(220, 245, 255, 255)
    val defaultFringe: java.awt.Color = java.awt.Color(120, 200, 255, 255)

    fun headCoreColor(base: java.awt.Color): java.awt.Color {
        return java.awt.Color(base.red, base.green, base.blue, 152)
    }

    fun headFringeColor(base: java.awt.Color): java.awt.Color {
        return java.awt.Color(base.red, base.green, base.blue, 128)
    }

    fun tailCoreColor(base: java.awt.Color): java.awt.Color {
        return darken(base, 88)
    }

    fun tailFringeColor(base: java.awt.Color): java.awt.Color {
        return darken(base, 66)
    }

    fun smokeCoreColor(base: java.awt.Color): java.awt.Color {
        return darken(base, 120)
    }

    fun smokeFringeColor(base: java.awt.Color): java.awt.Color {
        return darken(base, 96)
    }

    private fun darken(color: java.awt.Color, alpha: Int): java.awt.Color {
        val maxChannel = maxOf(color.red, maxOf(color.green, color.blue)).coerceAtLeast(1)
        val preserveHue = 0.18f
        val floor = 6
        return java.awt.Color(
            (floor + color.red.toFloat() / maxChannel.toFloat() * 54f * preserveHue).toInt().coerceIn(0, 255),
            (floor + color.green.toFloat() / maxChannel.toFloat() * 54f * preserveHue).toInt().coerceIn(0, 255),
            (floor + color.blue.toFloat() / maxChannel.toFloat() * 54f * preserveHue).toInt().coerceIn(0, 255),
            alpha.coerceIn(0, 255),
        )
    }
}