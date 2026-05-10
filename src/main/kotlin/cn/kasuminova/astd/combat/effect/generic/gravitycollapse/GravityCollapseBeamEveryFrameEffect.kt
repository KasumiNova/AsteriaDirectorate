package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import cn.kasuminova.astd.combat.effect.arc.signature.stasisfield.GravityCollapseBeamVfx
import cn.kasuminova.astd.combat.effect.arc.signature.stellarjet.StellarJetChargeUpVfx
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
    }

    private var initedForWeaponId: String? = null
    private var spec: GravityCollapseWeaponSpec? = null
    private var beamVfx: GravityCollapseBeamVfx? = null
    private var onHit: GravityCollapseOnHitHandler? = null
    private var chargeUpVfx: StellarJetChargeUpVfx? = null

    private var beamStarted = false
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
            beamVfx = null
            onHit = null
            chargeUpVfx = null
            return
        }

        beamVfx = GravityCollapseBeamVfx(
            scale = s.beamScale,
            beamWidthMul = s.beamWidthMul,
        )
        onHit = GravityCollapseOnHitHandler(
            config = GravityCollapseOnHitConfig(
                tickInterval = 0.5f,
                aoeRadiusBase = s.aoeRadiusBase,
                requireDamageTarget = s.aoeRequireDamageTarget,
                affectAlliesAndNeutral = s.aoeAffectAlliesAndNeutral,
                affectNonShips = s.aoeAffectNonShips,
                affectHulks = s.aoeAffectHulks,
                vfxScale = s.beamScale,
            )
        )

        // 复用 StellarJet 的 charge-up 粒子“吸入”观感：仅用于充能阶段。
        chargeUpVfx = StellarJetChargeUpVfx(
            coreColor = Color(255, 70, 70, 255),
            glowColor = Color(255, 25, 25, 255),
            scale = s.beamScale,
        )

        beamStarted = false
        fadeStartedAt = null
        lastLine = null
        lastChargeLevel = 0f
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        if (amount <= 0f) return

        val weaponId = try {
            weapon.spec?.weaponId
        } catch (_: Throwable) {
            null
        } ?: return

        ensureInit(weaponId)
        val vfx = beamVfx ?: return
        val hit = onHit ?: return
        val cu = chargeUpVfx

        val now = try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }

        val beam = try {
            weapon.beams?.firstOrNull()
        } catch (_: Throwable) {
            null
        }

        val chargeLevel = try {
            weapon.chargeLevel
        } catch (_: Throwable) {
            0f
        }.coerceIn(0f, 1f)

        // ====== 充能阶段：只做充能动画，不自绘束体、不触发 AOE ======
        // 说明：burst beam 的 charge-up 期间，beam 对象可能已经存在但亮度接近 0；
        // 这里用 chargeLevel + beam.brightness 双重门控，确保“充能时不发射光束”。
        val beamBrightness = try {
            beam?.brightness ?: 0f
        } catch (_: Throwable) {
            0f
        }.coerceIn(0f, 1f)

        val cooldownRemaining = try {
            weapon.cooldownRemaining
        } catch (_: Throwable) {
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
                    // 启动时清一下旧状态（热重载/重复开火更稳定）
                    try {
                        vfx.reset(engine)
                    } catch (_: Throwable) {
                    }
                    try {
                        cu?.reset()
                    } catch (_: Throwable) {
                    }
                    hit.reset()

                    // 起手 VFX：爆发光锥只应在“开火瞬间”出现。
                    try {
                        vfx.onStart(engine, line.from, line.to, 1f)
                    } catch (_: Throwable) {
                    }
                }

                // 视觉：统一用 level=1（武器面板差异由 weapon_data 的 DPS/射程体现）。
                vfx.advance(engine, amount, line.from, line.to, 1f, 1f)

                // 命中机制：用 weapon.damage.damage 作为“面板 DPS”（已含加成）。
                val panelDps = try {
                    weapon.damage?.damage ?: 0f
                } catch (_: Throwable) {
                    0f
                }
                hit.advance(engine, amount, weapon, beam, 1f, panelDps)
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
                vfx.advance(engine, amount, line.from, line.to, 1f, fade)
            }

            if (fade <= 0f) {
                beamStarted = false
                fadeStartedAt = null
                lastLine = null
                try {
                    vfx.reset(engine)
                } catch (_: Throwable) {
                }
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
            } catch (_: Throwable) {
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
            } catch (_: Throwable) {
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
        } catch (_: Throwable) {
        }

        try {
            val bd = beam?.damage
            if (bd != null) {
                bd.damage = 0f
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreVanillaDamageIfNeeded(weapon: WeaponAPI) {
        val saved = suppressedWeaponDamage ?: return
        try {
            val d = weapon.damage
            if (d != null) {
                d.damage = saved
            }
        } catch (_: Throwable) {
        }
        suppressedWeaponDamage = null
    }
}
