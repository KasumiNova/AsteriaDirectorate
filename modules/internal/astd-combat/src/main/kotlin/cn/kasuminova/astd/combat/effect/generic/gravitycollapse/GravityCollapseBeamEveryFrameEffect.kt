package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.impl.render.BeamHostImpl
import cn.kasuminova.astd.renderer.beam.driver.BeamFrame
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriver
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriverImpl
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxSpecs
import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 可装配版“引力坍缩炮” everyFrameEffect。
 *
 * 约定：
 * - 原版 beam 负责基础命中/伤害结算；
 * - beamEffect 负责隐藏原版渲染；
 * - 本 effect 负责自绘束体 VFX，以及命中点的“坍缩 tick + AOE + 引力撕裂”。
 */
class GravityCollapseBeamEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private const val END_FADE_TIME = 0.65f

        // 渲染端“快速延伸”：前 BEAM_GROW_TIME 秒内把束长按比例拉长（仅视觉，不影响命中）。
        private const val BEAM_GROW_TIME = 0.08f

        private val log = AstdLog.logger

        /** 每帧回调里的异常告警按调用点只记一次，避免刷屏。 */
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

    // 修复：某些情况下 charge-up 期间原版 beam 仍会造成伤害（即便亮度很低/渲染被隐藏）。
    // 这里在 charge-up 阶段临时把 weapon/beam 的 damage 置 0，离开 charge-up 后恢复。
    private var suppressedWeaponDamage: Float? = null

    private fun ensureInit(weaponId: String) {
        if (initedForWeaponId == weaponId) return
        initedForWeaponId = weaponId

        val s = GravityCollapseWeaponSpecs.forWeaponId(weaponId)
        spec = s

        // 若找不到配置：不做任何事（避免误挂到其他武器时产生意外效果）。
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
                aoeRadiusBase = s.aoeRadiusBase,
                requireDamageTarget = s.aoeRequireDamageTarget,
                affectAlliesAndNeutral = s.aoeAffectAlliesAndNeutral,
                affectNonShips = s.aoeAffectNonShips,
                affectHulks = s.aoeAffectHulks,
                vfxScale = s.beamScale,
                aoeDamageRatio = s.aoeDamageRatio,
                mobilityReduction = s.mobilityReduction,
                mobilityDuration = s.mobilityDuration,
                armorReductionIgnore = s.armorReductionIgnore,
            )
        )

        // charge-up 粒子“吸入”观感：仅用于充能阶段。
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

    /** 构建引力坍缩炮光束树的驱动（起手 burst 由 muzzle 节点 onAttach 自绘，故 startupBurst=true）。 */
    private fun buildDriver(): BeamVfxDriver? {
        val s = spec ?: return null
        val sc = s.beamScale.coerceIn(0.35f, 2.25f)
        val wMul = s.beamWidthMul.coerceIn(0.35f, 1.25f)
        val tree = BeamVfxSpecs.gravityCollapse(scale = s.beamScale, beamWidthMul = s.beamWidthMul, startupBurst = true)
        return BeamVfxDriverImpl(BeamHostImpl("gcbeam@" + System.identityHashCode(this), baseWidth = sc * wMul), tree)
    }

    /** 把（可能被 reach 截断的）束几何折成 [BeamFrame] 喂驱动：strength=1（level 恒 1），fadeMul 控淡出收束。 */
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

        // ====== 充能阶段：只做充能动画，不自绘束体、不触发 AOE ======
        // 说明：burst beam 的 charge-up 期间，beam 对象可能已经存在但亮度接近 0；
        // 这里用 chargeLevel + beam.brightness 双重门控，确保“充能时不发射光束”。
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

        // ====== 1) 正在发射：绘制束体 + AOE ======
        if (beam != null && chargeLevel >= 0.999f && beamBrightness > 0.05f) {
            restoreVanillaDamageIfNeeded(weapon)
            val line = BeamLineUtil.fromBeamOrWeapon(weapon, beam)
            if (line != null) {
                lastLine = line

                if (!beamStarted) {
                    beamStarted = true
                    fadeStartedAt = null
                    beamStartedAt = now
                    cu?.reset()
                    hit.reset()
                    // 新一轮开火：弃旧树建新树；起手 burst 由 muzzle 节点首帧 onAttach 自绘。
                    beamDriver?.dispose()
                    beamDriver = buildDriver()
                }

                // 视觉：统一用 level=1（武器面板差异由 weapon_data 的 DPS/射程体现）；前 BEAM_GROW_TIME 秒束长渐长。
                val reach = beamStartedAt?.let { ((now - it) / BEAM_GROW_TIME).coerceIn(0f, 1f) } ?: 1f
                driveBeam(engine, line, amount, reach = reach, fadeMul = 1f)

                // 命中机制：折算基准为「面板总伤害」（burstDamage 口径，已含加成），而非面板每秒伤害。
                // 原版 burstDamage = dps × ((chargeup + chargedown) × 0.333 + burstDuration)
                // （WeaponSpreadsheetLoader 公式，与装配面板「伤害」列一致）；非爆发光束回退为 dps。
                val panelDamage = try {
                    val dps = weapon.damage?.damage ?: 0f
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
                hit.advance(engine, amount, weapon, beam, 1f, panelDamage)
            }
            lastChargeLevel = chargeLevel
            return
        }

        // ====== 2) 发射结束后的淡出：优先于 charge-up 判断 ======
        // 修复：cooldown 期间 chargeLevel 可能也处于 (0,1)，不能把它当成“充能”，否则淡出会被短路。
        if (beamStarted) {
            restoreVanillaDamageIfNeeded(weapon)
            val line = lastLine
            val fs = fadeStartedAt ?: run {
                fadeStartedAt = now
                now
            }
            val t = ((now - fs) / END_FADE_TIME).coerceIn(0f, 1f)
            val fade = (1f - t).coerceIn(0f, 1f)

            if (line != null && fade > 0f) {
                // 淡出：束体已渐长完（reach=1），整体由 fadeMul 收束到消失。
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
            }

            lastChargeLevel = chargeLevel
            return
        }

        // ====== 3) 充能阶段：仅播放充能动画，不自绘束体、不触发 AOE ======
        // 使用 cooldownRemaining 门控，避免“冷却被视作充能”。
        val isCharging = cooldownRemaining <= 0.01f && chargeLevel > 0.001f && chargeLevel < 0.999f
        if (isCharging) {
            suppressVanillaDamageDuringCharge(weapon, beam)
            try {
                cu?.advance(engine, amount, weapon, chargeLevel)
            } catch (t: Throwable) {
                warnOnce("chargeUpVfx.advance", t)
            }
            lastChargeLevel = chargeLevel
            return
        }

        // 其它状态：确保恢复
        restoreVanillaDamageIfNeeded(weapon)

        // 非 charging，且 beam 不存在：重置 charge 累积器，避免下一次充能“续上一次的 acc”。
        if (chargeLevel <= 0.001f && lastChargeLevel > 0.001f) {
            try {
                cu?.reset()
            } catch (t: Throwable) {
                warnOnce("chargeUpVfx.reset", t)
            }
        }
        lastChargeLevel = chargeLevel
    }

    private fun suppressVanillaDamageDuringCharge(weapon: WeaponAPI, beam: com.fs.starfarer.api.combat.BeamAPI?) {
        // weapon.damage.damage 作为“每秒伤害”，置 0 即可阻断引擎侧结算。
        // 同时尽量把 beam.damage 也置 0（有的实现取 beam.damage 而非 weapon.damage）。
        try {
            val d = weapon.damage
            if (d != null) {
                if (suppressedWeaponDamage == null) {
                    suppressedWeaponDamage = d.damage
                }
                d.damage = 0f
            }
        } catch (t: Throwable) {
            warnOnce("suppress.weaponDamage", t)
        }

        try {
            val bd = beam?.damage
            if (bd != null) {
                bd.damage = 0f
            }
        } catch (t: Throwable) {
            warnOnce("suppress.beamDamage", t)
        }
    }

    private fun restoreVanillaDamageIfNeeded(weapon: WeaponAPI) {
        val saved = suppressedWeaponDamage ?: return
        try {
            val d = weapon.damage
            if (d != null) {
                d.damage = saved
            }
        } catch (t: Throwable) {
            warnOnce("restore.weaponDamage", t)
        }
        suppressedWeaponDamage = null
    }
}
