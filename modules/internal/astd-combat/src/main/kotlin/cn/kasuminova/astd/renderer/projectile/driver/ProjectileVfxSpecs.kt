package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceVfx
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.impl.render.ConeImpactVfx
import cn.kasuminova.astd.impl.render.ConeImpactVfxSpec
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs.build
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs.has
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs.simpleProjectileVfx
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

// BoxUtil 会缓存 StaticTrailData 的 vRAM 池配置，拖尾参数的字面量热交换不会生效。
object ProjectileVfxSpecs {

    private val builders: Map<String, (Float?) -> ProjectileVfx> = mapOf(
        "astd_starfall_echo_shot" to { range -> starfallEchoShot("astd_starfall_echo_shot", range) },
        "astd_starfall_echo_shot_final" to { range -> starfallEchoFinalShot("astd_starfall_echo_shot_final", range) },
        "astd_starfall_wing_shot" to { range -> starfallWingShot("astd_starfall_wing_shot", range) },
        // BoxUtil 的 bolt 组件对 MissileAPI 默认禁用，导弹需要弹头时必须显式 bolt { onMissile() } 接管
        "astd_starfall_wing_mote" to {
            simpleProjectileVfx("astd_starfall_wing_mote", violet(), width = 5f, length = 260f) {
                bolt {
                    onMissile()
                    size(28f, 10f)
                }
                shardWake("wake") {
                    cadence(0.02f, 1)
                    emission(scatterRadius = 8f, speedMin = 20f, speedMax = 40f, spreadDeg = 0f)
                    shardLength(34f)
                    colors(core = 0xF0E1FFFF, fringe = 0xAA6EFFFF)
                    shard(
                        sizeMul = 0.2f, sizeMin = 3f, sizeMax = 6f,
                        spinMin = 90f, spinMax = 360f,
                        alphaLo = 120, alphaHi = 180,
                        timerFullLo = 0.15f, timerFullHi = 0.3f, timerFadeOut = 0.3f,
                    )
                }
            }
        },
        "astd_charge_needle_shot" to {
            simpleProjectileVfx(
                "astd_charge_needle_shot",
                chargeNeedleColor(),
                width = 6f,
                length = 180f,
                trailWidthScale = 0.5f,
                decorTrail = false
            )
        },
        "astd_heavy_charge_needle_shot" to {
            simpleProjectileVfx(
                "astd_heavy_charge_needle_shot",
                chargeNeedleColor(),
                width = 6f,
                length = 180f,
                trailWidthScale = 0.5f,
                decorTrail = false
            )
        },
        "astd_electric_drive_accelerator_shot" to { range ->
            simpleProjectileVfx(
                "astd_electric_drive_accelerator_shot",
                electricYellow(),
                width = 9f,
                length = 500f,
                range = range,
                rangeRatio = 0.25f,
                trailWidthScale = 0.5f,
                tailColor = emberRedTail(),
                muzzleBurst = MuzzleBurst(),
                decorTrail = false
            )
        },
        "astd_qiongjue_phase_railgun_shot" to { range ->
            simpleProjectileVfx(
                "astd_qiongjue_phase_railgun_shot",
                qiongjueBlue(),
                width = 16f,
                length = 300f,
                range = range,
                rangeRatio = 0.5f,
                trailWidthScale = 0.65f,
                trailGlow = 0.8f,
                tailColor = paleGreenTail(),
                boltFlare = 48f,
                muzzleBurst = MuzzleBurst(),
                decorTrail = false
            ) {
                machRing("ring") {
                    cadence(0.1f, 30f)
                    color(0x8CC7FFFF, alpha = 1f)
                    shape(flatten = 0.5f, growthStart = 0.5f, growthEnd = 2.5f, fadeIn = 0.05f, full = 0.05f, fadeOut = 0.5f)
                    momentum(900f)
                }
            }
        },
        "astd_positron_shockwave_shot" to {
            simpleProjectileVfx(
                "astd_positron_shockwave_shot",
                positronWhiteBlue(),
                width = 5f,
                length = 90f,
                muzzleBurst = MuzzleBurst(length = 55f, halfAngleDeg = 24f, duration = 0.25f),
            )
        },
        "astd_heavy_ion_pulse_shot" to { range ->
            simpleProjectileVfx(
                "astd_heavy_ion_pulse_shot",
                heavyIonPulseColor(),
                width = 12f,
                length = 220f,
                range = range,
                rangeRatio = 0.25f,
                trailWidthScale = 0.75f,
                trailGlow = 0.7f,
                boltFlare = 36f,
                muzzleBurst = MuzzleBurst()
            ) {
                shardWake("wake") {
                    cadence(0.02f, 1)
                    emission(scatterRadius = 8f, speedMin = 100f, speedMax = 150f, spreadDeg = 8f)
                    shardLength(34f)
                    colors(core = 0xF0F8FFFF, fringe = 0x8CC7FFFF)
                    shard(
                        sizeMul = 0.2f, sizeMin = 4f, sizeMax = 9f,
                        spinMin = 90f, spinMax = 360f,
                        alphaLo = 120, alphaHi = 180,
                        timerFullLo = 0.15f, timerFullHi = 0.3f, timerFadeOut = 0.3f,
                    )
                }
            }
        },
        "astd_stellar_mrm_launcher_shot" to { range -> stellarMrmShot("astd_stellar_mrm_launcher_shot", range) },
        "astd_stellar_mrm_pod_shot" to { range -> stellarMrmShot("astd_stellar_mrm_pod_shot", range) },
        "astd_lh_stardust_mote_arc" to {
            simpleProjectileVfx("astd_lh_stardust_mote_arc", qiongjueBlue(), width = 4f, length = 200f, decorTrail = false)
        },
        "astd_lh_stardust_mote_lens" to {
            simpleProjectileVfx("astd_lh_stardust_mote_lens", violet(), width = 4f, length = 200f, decorTrail = false)
        },
        "astd_piercing_lance_shot" to ::piercingLanceShot,
        "astd_cuifeng_torpedo_shot" to { range -> cuifengTorpedoShot("astd_cuifeng_torpedo_shot", range) },
        "astd_ice_shard_mirv_shot" to { range -> iceShardMirvShot("astd_ice_shard_mirv_shot", range) },
        "astd_gemini_dem_kinetic_msl" to {
            simpleProjectileVfx("astd_gemini_dem_kinetic_msl", geminiKineticBlue(), width = 5f, length = 400f, recede = 0f, decorTrail = false)
        },
        "astd_gemini_dem_he_msl" to {
            simpleProjectileVfx("astd_gemini_dem_he_msl", geminiHeRed(), width = 5f, length = 400f, recede = 0f, decorTrail = false)
        },
        "astd_gemini_dem_kinetic_fighter_msl" to {
            simpleProjectileVfx("astd_gemini_dem_kinetic_fighter_msl", geminiKineticBlue(), width = 5f, length = 400f, recede = 0f, decorTrail = false)
        },
        "astd_gemini_dem_he_fighter_msl" to {
            simpleProjectileVfx("astd_gemini_dem_he_fighter_msl", geminiHeRed(), width = 5f, length = 400f, recede = 0f, decorTrail = false)
        },
        "astd_ice_shard_sub_msl" to {
            simpleProjectileVfx("astd_ice_shard_sub_msl", iceBlue(), width = 6f, length = 120f, decorTrail = false, recede = -10f) {
                bolt { off() }
                spriteBody("graphics/fx/astd_ice_shard_crystal.png", width = 40f, height = 20f) {
                    glow(0.5f)
                }
            }
        },
    )

    private val installed = AtomicBoolean(false)

    fun install() {
        if (installed.compareAndSet(false, true)) {
            builders.forEach { (id, builder) -> ProjectileVfxRegistry.register(id, builder) }
        }
    }

    fun has(projectileSpecId: String): Boolean {
        install()
        return ProjectileVfxRegistry.has(projectileSpecId)
    }

    fun build(projectileSpecId: String, weaponRangeSu: Float? = null): ProjectileVfx? {
        install()
        return ProjectileVfxRegistry.build(projectileSpecId, weaponRangeSu)
    }

    private data class MuzzleBurst(
        val length: Float = 120f,
        val halfAngleDeg: Float = 20f,
        val duration: Float = 0.35f,
    )

    private fun simpleProjectileVfx(
        id: String,
        color: ASTDColor,
        width: Float,
        length: Float,
        glowScale: Float = 2.2f,
        range: Float? = null,
        rangeRatio: Float? = null,
        trailWidthScale: Float = 1f,
        brightness: Float = 0.9f,
        trailGlow: Float = 0.7f,
        tailColor: ASTDColor? = null,
        boltFlare: Float? = null,
        muzzleBurst: MuzzleBurst? = null,
        decorTrail: Boolean = true,
        recede: Float? = null,
        extra: ProjectileVfxScope.() -> Unit = {},
    ): ProjectileVfx = projectileVfx(id) {
        fade { out(0.18f); hit(0.1f); expire(0.22f) }

        bolt { color(boltColor(color).hex()) }

        val bandLen = if (rangeRatio != null && range != null) round5(range * rangeRatio) else length
        val bandW = bandWidth(width, glowScale) * BAND_WIDTH_MULT * trailWidthScale
        staticTrail("twin", TEX_TWIN) {
            layer(1); width(bandW); length(bandLen)
            colors(bandHeadColor(color, brightness).hex(), bandTailColor(color, brightness, tailColor).hex())
            tile(mainTile(bandLen), mainScroll(bandLen))
            recede?.let { recede(it) }
            glow(trailGlow)
        }
        staticTrail("core", TEX_SMOOTH) {
            layer(2); width(round05(bandW * CORE_WIDTH_RATIO)); length(bandLen)
            colors(bandHeadColor(color, brightness).hex(), bandTailColor(color, brightness, tailColor).hex())
            tile(mainTile(bandLen), mainScroll(bandLen))
            recede?.let { recede(it) }
            glow(trailGlow)
        }
        if (decorTrail) {
            repeat(2) { count ->
                staticTrail("zappy_$count", TEX_ZAPPY) {
                    layer(3); width(arcWidth(bandW)); length(bandLen)
                    colors(bandHeadColor(color, brightness).hex(), bandTailColor(color, brightness, tailColor).hex())
                    tile(arcTile(bandLen), arcScroll(bandLen))
                    recede?.let { recede(it) }
                    // BoxUtil 仅在漂移速度非零时应用节点自旋，angularOut 必须与 velocityOut 配套。
                    angularOut()
                    velocityOut(-12f, -6f, 12f, 6f)
                    glow(trailGlow)
                }
            }
        }
        if (boltFlare != null) {
            boxFlare("boltGlow") {
                style(BoxFlareStyle.SMOOTH)
                size(boltFlare, boltFlare)
                colors(mixWhite(color, 0.85f).a(0.5f).hex(), color.a(0.15f).hex())
                glow(0.2f, 4f)
                noise(0f)
                offset(-boltFlare / 2)
            }
        }
        if (muzzleBurst != null) {
            onFire { engine, projectile ->
                ConeImpactVfx.spawn(
                    engine,
                    ConeImpactVfxSpec(
                        origin = Vector2f(projectile.location),
                        facingDeg = projectile.facing,
                        halfAngleDeg = muzzleBurst.halfAngleDeg,
                        length = muzzleBurst.length,
                        coreColor = awt(mixWhite(color, 0.7f)),
                        fringeColor = awt(color),
                        duration = muzzleBurst.duration,
                    ),
                )
            }
        }
        extra()
    }

    private fun starfallEchoShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        starfallBlue(),
        width = 16f,
        length = 320f,
        range = range,
        rangeRatio = 0.5f,
        trailWidthScale = 1.25f,
        trailGlow = 0.8f,
        boltFlare = 100f,
        muzzleBurst = MuzzleBurst(),
    ) {
        shardWake("wake") {
            cadence(0.01f, 3)
            emission(scatterRadius = 8f, speedMin = 100f, speedMax = 150f, spreadDeg = 8f)
            shardLength(34f)
            colors(core = 0xF0F8FFFF, fringe = 0x78BEFFFF)
            shard(
                sizeMul = 0.2f, sizeMin = 4f, sizeMax = 9f,
                spinMin = 90f, spinMax = 360f,
                alphaLo = 120, alphaHi = 180,
                timerFullLo = 0.15f, timerFullHi = 0.3f, timerFadeOut = 0.3f,
            )
        }
        machRing("ring") {
            cadence(0.2f, 30f)
            color(0x78BEFFFF, alpha = 1f)
            shape(flatten = 0.5f, growthStart = 0.5f, growthEnd = 2.5f, fadeIn = 0.05f, full = 0.05f, fadeOut = 0.3f)
            momentum(800f, 1000f)
        }
    }

    private fun starfallEchoFinalShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        starfallFinalRed(),
        width = 40f,
        length = 320f,
        range = range,
        rangeRatio = 0.5f,
        trailWidthScale = 1f,
        trailGlow = 0.9f,
        boltFlare = 90f,
        muzzleBurst = MuzzleBurst(length = 200f, halfAngleDeg = 24f, duration = 0.4f),
    ) {
        boxFlare("pillar") {
            style(BoxFlareStyle.SMOOTH_DISC, facingOffsetDeg = 45f)
            colors(0xFFEDE6FF, 0xFF5A3CA0)
            size(250f, 10f)
            glow(1.2f, 4f)
            offset(-45f)
        }
        boxFlare("pillar_cross") {
            style(BoxFlareStyle.SMOOTH_DISC, facingOffsetDeg = 45f + 90f)
            colors(0xFFEDE6FF, 0xFF5A3CA0)
            size(250f, 10f)
            glow(1.2f, 4f)
            offset(-45f)
        }
        shardWake("wake") {
            cadence(0.01f, 3)
            emission(scatterRadius = 8f, speedMin = 100f, speedMax = 150f, spreadDeg = 8f)
            shardLength(68f)
            colors(core = 0xFFEBE1FF, fringe = 0xFF5A3CFF)
            shard(
                sizeMul = 0.2f, sizeMin = 4f, sizeMax = 9f,
                spinMin = 90f, spinMax = 360f,
                alphaLo = 120, alphaHi = 180,
                timerFullLo = 0.15f, timerFullHi = 0.3f, timerFadeOut = 0.3f,
            )
        }
        machRing("ring") {
            cadence(0.15f, 50f)
            color(0xFF5A3CFF, alpha = 1f)
            shape(flatten = 0.5f, growthStart = 0.5f, growthEnd = 2.5f, fadeIn = 0.05f, full = 0.05f, fadeOut = 0.4f)
            momentum(800f, 1000f)
        }
    }

    private fun starfallWingShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        violet(),
        width = 34f,
        length = 450f,
        range = range,
        rangeRatio = 0.5f,
        trailGlow = 0.8f,
        boltFlare = 68f,
        muzzleBurst = MuzzleBurst(length = 160f, halfAngleDeg = 30f, duration = 0.5f)
    ) {
        shardWake("wake") {
            cadence(0.01f, 2)
            emission(scatterRadius = 8f, speedMin = 20f, speedMax = 40f, spreadDeg = 0f)
            shardLength(34f)
            colors(core = 0xF0E1FFFF, fringe = 0xAA6EFFFF)
            shard(
                sizeMul = 0.2f, sizeMin = 4f, sizeMax = 9f,
                spinMin = 90f, spinMax = 360f,
                alphaLo = 120, alphaHi = 180,
                timerFullLo = 0.15f, timerFullHi = 0.3f, timerFadeOut = 0.3f,
            )
        }
        machRing("ring") {
            cadence(0.2f, 35f)
            color(0xAA6EFFFF, alpha = 1f)
            shape(flatten = 0.5f, growthStart = 0.7f, growthEnd = 3f, fadeIn = 0.05f, full = 0.05f, fadeOut = 0.5f)
            momentum(200f, 400f)
        }
    }

    private fun stellarMrmShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        violet(),
        width = 10f,
        length = 420f,
        range = range,
        rangeRatio = 0.5f,
        recede = 0f,
    ) {
        boxFlare("light") {
            style(BoxFlareStyle.SMOOTH)
            colors(0xA046F4c1, 0x9f6ed3c1)
            size(30f, 30f)
            glow(2.0f, 4f)
            flicker(0.2f)
            noise(0.1f)
        }
    }

    private fun piercingLanceShot(range: Float?): ProjectileVfx = simpleProjectileVfx(
        "astd_piercing_lance_shot",
        ASTDColor(0.55f, 0.78f, 1f, 0.95f),
        width = 36f,
        length = 260f,
        glowScale = 4.0f,
        range = range,
        rangeRatio = 0.85f,
        brightness = 1.25f,
        muzzleBurst = MuzzleBurst(length = 320f, halfAngleDeg = 28f, duration = 0.5f),
        recede = 0f,
    ) {
        boxFlare("core") {
            size(200f, 6f)
            colors(0xD0E8FFFF, 0x64B4FFBE)
            glow(1.6f, 4f)
            style(BoxFlareStyle.SMOOTH_DISC)
            fixedFacing(0f)
        }
        boxFlare("light") {
            size(40f, 40f)
            colors(0xD0E8FFFF, 0x64B4FFBE)
            glow(0.2f, 4f)
            style(BoxFlareStyle.SMOOTH)
            fixedFacing(0f)
        }
        anchorArc("arc") {
            thickness(10f)
            colors(fringe = 0x78BEFFC0L, core = 0xF0F8FFF0L)
        }
        onFire(ProjectileVfxOnFireHook { engine, projectile ->
            PiercingLanceVfx.spawnMuzzleDistortion(engine, Vector2f(projectile.location))
        })
    }

    private fun cuifengTorpedoShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        qiongjueBlue(),
        width = 10f,
        length = 420f,
        range = range,
        rangeRatio = 0.5f,
        recede = 0f,
    ) {
        boxFlare("light") {
            style(BoxFlareStyle.SMOOTH)
            colors(ASTDColor(0xD0E8FF).a(0.5f).hex(), ASTDColor(0x78BEFF).a(0.5f).hex())
            size(30f, 30f)
            glow(0.1f, 4f)
            noise(0.1f)
        }
    }

    private fun iceShardMirvShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        iceBlue(),
        width = 8f,
        length = 420f,
        range = range,
        rangeRatio = 0.5f,
        recede = 0f,
    ) {
        boxFlare("light") {
            style(BoxFlareStyle.SMOOTH)
            colors(ASTDColor(0xEAF6FFFF).a(0.5f).hex(), ASTDColor(0x9BD8FFc1).a(0.5f).hex())
            size(30f, 30f)
            glow(0.1f, 4f)
            noise(0.1f)
        }
    }

    private fun starfallBlue() = ASTDColor(0.55f, 0.78f, 1f, 1f)
    private fun starfallFinalRed() = ASTDColor(1f, 0.35f, 0.24f, 1f)

    private fun geminiKineticBlue() = ASTDColor(0.55f, 0.75f, 1f, 1f)
    private fun geminiHeRed() = ASTDColor(1f, 0.16f, 0.24f, 1f)

    private fun positronWhiteBlue() = ASTDColor(0.62f, 0.82f, 1f, 1f)

    private fun electricYellow() = ASTDColor(1f, 0.82f, 0.25f, 1f)

    private fun emberRedTail() = ASTDColor(0.35f, 0.1f, 0.04f, 0.6f)

    private fun qiongjueBlue() = ASTDColor(0.55f, 0.78f, 1f, 1f)

    private fun paleGreenTail() = ASTDColor(0.19f, 0.35f, 0.25f, 0.6f)

    private fun violet() = ASTDColor(0.66f, 0.42f, 1f, 1f)

    private fun iceBlue() = ASTDColor(0.67f, 0.88f, 1f, 1f)

    private fun heavyIonPulseColor() = ASTDColor(0.55f, 0.78f, 1f, 1f)

    private fun chargeNeedleColor() = ASTDColor(0.55f, 0.78f, 1f, 1f)

    private fun ASTDColor.a(alpha: Float): ASTDColor = copy(alpha = alpha.coerceIn(0f, 1f))

    private fun ASTDColor.hex(): Long {
        fun ch(v: Float): Long = (v.coerceIn(0f, 1f) * 255f).roundToInt().toLong()
        return (ch(red) shl 24) or (ch(green) shl 16) or (ch(blue) shl 8) or ch(alpha)
    }
}

internal const val TEX_TWIN = "graphics/fx/astd_trails_twin.png"
internal const val TEX_SMOOTH = "graphics/fx/astd_trails_smooth.png"
internal const val TEX_ZAPPY = "graphics/fx/astd_trails_zappy.png"

internal const val BAND_WIDTH_MULT = 2f

internal const val CORE_WIDTH_RATIO = 0.5f

internal fun round05(v: Float): Float = (v * 2f).roundToInt() / 2f

internal fun round5(v: Float): Float = (v / 5f).roundToInt() * 5f

internal fun bandWidth(width: Float, glowScale: Float): Float = round05(max(0.35f * width, 3.15f * glowScale))

internal fun arcWidth(bandW: Float): Float = round05(bandW * 0.6f)

internal fun bandHeadColor(color: ASTDColor, alphaScale: Float = 1f): ASTDColor =
    mixWhite(color, 0.45f).copy(alpha = (color.alpha * 0.78f * alphaScale).coerceIn(0f, 1f))

internal fun bandTailColor(color: ASTDColor, alphaScale: Float = 1f, tail: ASTDColor? = null): ASTDColor {
    if (tail != null) {
        return ASTDColor(tail.red, tail.green, tail.blue, (tail.alpha * alphaScale).coerceIn(0f, 1f))
    }
    return ASTDColor(
        color.red * 0.16f, color.green * 0.16f, color.blue * 0.16f, (0.07f * alphaScale).coerceIn(0f, 1f),
    )
}

internal fun mainTile(length: Float): Float = round5(length / 2.4f)

internal fun mainScroll(length: Float): Float = round5(length / 6f)

internal fun arcTile(length: Float): Float = round5(length / 2f)

internal fun arcScroll(length: Float): Float = round5(length / 4.5f)

internal fun boltColor(color: ASTDColor): ASTDColor = mixWhite(color, 0.7f).copy(alpha = 0.78f)

private fun mixWhite(color: ASTDColor, t: Float): ASTDColor = ASTDColor(
    color.red + (1f - color.red) * t,
    color.green + (1f - color.green) * t,
    color.blue + (1f - color.blue) * t,
    color.alpha,
)

private fun awt(color: ASTDColor): Color = Color(
    color.red.coerceIn(0f, 1f),
    color.green.coerceIn(0f, 1f),
    color.blue.coerceIn(0f, 1f),
    color.alpha.coerceIn(0f, 1f),
)
