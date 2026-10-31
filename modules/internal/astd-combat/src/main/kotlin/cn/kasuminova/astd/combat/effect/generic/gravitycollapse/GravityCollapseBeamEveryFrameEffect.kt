package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.impl.render.BeamHostImpl
import cn.kasuminova.astd.renderer.beam.driver.BeamFrame
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriver
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriverImpl
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxSpecs
import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamVisualClipper
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class GravityCollapseBeamEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private const val END_FADE_TIME = 0.65f

        private const val BEAM_GROW_TIME = 0.08f

        private val log = AstdLog.logger

        private val warnedSites = HashSet<String>()

        private fun warnOnce(site: String, t: Throwable) {
            if (warnedSites.add(site)) {
                log.warn("[ASTD] 引力坍缩炮光束效果：$site 调用异常，取保守默认（后续同类异常不再重复记录）", t)
            }
        }
    }

    private var initedForWeaponId: String? = null
    private var spec: GravityCollapseWeaponSpec? = null
    private var beamDriver: BeamVfxDriver? = null
    private var onHit: GravityCollapseOnHitHandler? = null
    private var chargeUpVfx: BeamChargeUpVfx? = null

    private var beamStarted = false
    private var beamStartedAt: Float? = null
    private var fadeStartedAt: Float? = null
    private var lastLine: BeamLineUtil.BeamLine? = null

    private var lastChargeLevel: Float = 0f

    // 原版 beam 伤害在整个充能-发射-淡出周期内全部抑制（坍缩 AOE 为唯一伤害来源），仅在完全静默时恢复。
    // BeamWeaponRay 的伤害按帧结算，advance 先于武器推进与碰撞执行，此处置 0 即覆盖本帧。
    private val damageSuppressor = GravityCollapseDamageSuppressor()

    private fun ensureInit(weaponId: String) {
        if (initedForWeaponId == weaponId) return
        initedForWeaponId = weaponId

        val s = GravityCollapseWeaponSpecs.forWeaponId(weaponId)
        spec = s

        if (s == null) {
            beamDriver?.dispose()
            beamDriver = null
            onHit = null
            chargeUpVfx = null
            return
        }

        onHit = GravityCollapseOnHitHandler(
            config = GravityCollapseOnHitConfig(
                tickInterval = 0.5f,
                aoeRadius = s.aoeRadius,
                requireDamageTarget = s.aoeRequireDamageTarget,
                affectAlliesAndNeutral = s.aoeAffectAlliesAndNeutral,
                affectNonShips = s.aoeAffectNonShips,
                affectHulks = s.aoeAffectHulks,
                vfxScale = s.beamScale,
                armorPierceDamageMult = s.armorPierceDamageMult,
                aoeDamageRatio = s.aoeDamageRatio,
                hullDamageRatio = s.hullDamageRatio,
                mobilityReduction = s.mobilityReduction,
                mobilityDuration = s.mobilityDuration,
                phasedDamageRatio = s.phasedDamageRatio,
            )
        )

        chargeUpVfx = BeamChargeUpVfx(
            coreColor = Color(255, 70, 70, 255),
            glowColor = Color(255, 25, 25, 255),
            scale = s.beamScale,
        )

        beamStarted = false
        beamStartedAt = null
        fadeStartedAt = null
        lastLine = null
        lastChargeLevel = 0f
        beamDriver?.dispose()
        beamDriver = null
    }

    private fun buildDriver(): BeamVfxDriver? {
        val s = spec ?: return null
        val sc = s.beamScale.coerceIn(0.35f, 2.25f)
        val wMul = s.beamWidthMul.coerceIn(0.35f, 1.25f)
        val tree = BeamVfxSpecs.gravityCollapse(scale = s.beamScale, beamWidthMul = s.beamWidthMul, startupBurst = true)
        return BeamVfxDriverImpl(BeamHostImpl("gcbeam@" + System.identityHashCode(this), baseWidth = sc * wMul), tree)
    }

    private fun driveBeam(engine: CombatEngineAPI, line: BeamLineUtil.BeamLine, amount: Float, reach: Float, fadeMul: Float) {
        val driver = beamDriver ?: return
        val visLen = line.length * reach.coerceIn(0f, 1f)
        val visTo = Vector2f(line.from.x + line.dirUnit.x * visLen, line.from.y + line.dirUnit.y * visLen)
        driver.advance(
            engine,
            BeamFrame(start = line.from, facing = line.facing, length = visLen, endpoint = visTo, firing = true, strength = 1f, fadeMul = fadeMul),
            amount,
        )
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        if (amount <= 0f) return

        val weaponId = try {
            weapon.spec?.weaponId
        } catch (t: Throwable) {
            warnOnce("weapon.spec", t)
            null
        } ?: return

        ensureInit(weaponId)
        val hit = onHit ?: return
        val cu = chargeUpVfx

        val now = try {
            engine.getTotalElapsedTime(false)
        } catch (t: Throwable) {
            warnOnce("engine.totalElapsedTime", t)
            0f
        }

        val beam = try {
            weapon.beams?.firstOrNull()
        } catch (t: Throwable) {
            warnOnce("weapon.beams", t)
            null
        }

        val chargeLevel = try {
            weapon.chargeLevel
        } catch (t: Throwable) {
            warnOnce("weapon.chargeLevel", t)
            0f
        }.coerceIn(0f, 1f)

        // burst beam 充能期间 beam 对象已存在但亮度接近 0，必须用 chargeLevel + brightness 双重门控。
        val beamBrightness = try {
            beam?.brightness ?: 0f
        } catch (t: Throwable) {
            warnOnce("beam.brightness", t)
            0f
        }.coerceIn(0f, 1f)

        val cooldownRemaining = try {
            weapon.cooldownRemaining
        } catch (t: Throwable) {
            warnOnce("weapon.cooldownRemaining", t)
            0f
        }.coerceAtLeast(0f)

        if (beam != null && chargeLevel >= 0.999f && beamBrightness > 0.05f) {
            suppressVanillaDamage(weapon, beam)
            // 原版光束无视相位舰；自绘束体与坍缩 AOE 以相位舰为遮挡。原版 beam 本体伤害已全周期抑制。
            // 碰撞类别随原版 beam（战机挂载为 RAY_FIGHTER，仅与敌方碰撞）：此时友军不遮挡、不受 AOE。
            val effectiveLine = try {
                BeamVisualClipper.resolve(engine, weapon, phasedShipsBlock = true, beamCollisionClass = weapon.projectileCollisionClass)
            } catch (t: Throwable) {
                warnOnce("BeamVisualClipper.resolve", t)
                null
            }
            if (effectiveLine != null) {
                lastLine = effectiveLine

                if (!beamStarted) {
                    beamStarted = true
                    fadeStartedAt = null
                    beamStartedAt = now
                    cu?.reset()
                    hit.reset()
                    beamDriver?.dispose()
                    beamDriver = buildDriver()
                }

                val reach = beamStartedAt?.let { ((now - it) / BEAM_GROW_TIME).coerceIn(0f, 1f) } ?: 1f
                driveBeam(engine, effectiveLine, amount, reach = reach, fadeMul = 1f)

                // 原版 burstDamage = dps × ((chargeup + chargedown) × 0.333 + burstDuration)（WeaponSpreadsheetLoader 公式）；非爆发光束回退 dps。
                // 面板伤害读压制前的原始值（压制后 weapon.damage.damage 恒为 0）。
                val panelDamage = try {
                    val dps = damageSuppressor.originalDamageOf(weapon) ?: (weapon.damage?.damage ?: 0f)
                    val ws = weapon.spec
                    if (ws != null && ws.burstDuration > 0f) {
                        dps * ((ws.beamChargeupTime + ws.beamChargedownTime) * 0.333f + ws.burstDuration)
                    } else {
                        dps
                    }
                } catch (t: Throwable) {
                    warnOnce("weapon.damage", t)
                    0f
                }
                hit.advance(engine, amount, weapon, beam, effectiveLine.to, panelDamage)
            }
            lastChargeLevel = chargeLevel
            return
        }

        // cooldown 期间 chargeLevel 也可能处于 (0,1)，淡出判断必须先于充能判断。
        if (beamStarted) {
            suppressVanillaDamage(weapon, beam)
            val line = lastLine
            val fs = fadeStartedAt ?: run {
                fadeStartedAt = now
                now
            }
            val t = ((now - fs) / END_FADE_TIME).coerceIn(0f, 1f)
            val fade = (1f - t).coerceIn(0f, 1f)

            if (line != null && fade > 0f) {
                driveBeam(engine, line, amount, reach = 1f, fadeMul = fade)
            }

            if (fade <= 0f) {
                beamStarted = false
                beamStartedAt = null
                fadeStartedAt = null
                lastLine = null
                beamDriver?.dispose()
                beamDriver = null
                hit.reset()
                restoreVanillaDamageIfNeeded(weapon)
            }

            lastChargeLevel = chargeLevel
            return
        }

        val isCharging = cooldownRemaining <= 0.01f && chargeLevel > 0.001f && chargeLevel < 0.999f
        if (isCharging) {
            suppressVanillaDamage(weapon, beam)
            try {
                cu?.advance(engine, amount, weapon, chargeLevel)
            } catch (t: Throwable) {
                warnOnce("chargeUpVfx.advance", t)
            }
            lastChargeLevel = chargeLevel
            return
        }

        restoreVanillaDamageIfNeeded(weapon)

        if (chargeLevel <= 0.001f && lastChargeLevel > 0.001f) {
            try {
                cu?.reset()
            } catch (t: Throwable) {
                warnOnce("chargeUpVfx.reset", t)
            }
        }
        lastChargeLevel = chargeLevel
    }

    private fun suppressVanillaDamage(weapon: WeaponAPI, beam: BeamAPI?) {
        try {
            damageSuppressor.suppress(weapon, beam)
        } catch (t: Throwable) {
            warnOnce("suppress.damage", t)
        }
    }

    private fun restoreVanillaDamageIfNeeded(weapon: WeaponAPI) {
        try {
            damageSuppressor.restore(weapon)
        } catch (t: Throwable) {
            warnOnce("restore.damage", t)
        }
    }
}
