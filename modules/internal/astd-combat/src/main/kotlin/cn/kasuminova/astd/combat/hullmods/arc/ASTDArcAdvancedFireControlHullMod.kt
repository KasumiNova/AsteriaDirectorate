package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.hullmods.base.signedPercentOf
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 先进火控集成（烽燧 XC-102 内置船插）：以固定射程劣势换取持续开火下的进攻性增益。
 *
 * 固定效果（装配期写入，不随难度）：
 * - 舰船武器射程 -20%（弹道/能量/光束三条射程乘区）；
 * - 武器辐能产出 +20%（弹道/能量/光束三条辐耗乘区）。
 *
 * 持续性加成（2026-10 裁定，随难度系数）：大型非点防武器持续开火时经 [RAMP_DURATION] 秒
 * 线性爬坡至满额，停火后同时长衰减——
 * - 武器辐能产出 -30%/-40%/-70%（在固定 +20% 基础上向满额乘区插值）；
 * - 非导弹武器射速 +10%/+20%/+50%；
 * - 非导弹武器伤害 +10%/+20%/+50%（弹道/能量/光束）；
 * - 射弹飞行速度 +15%/+20%/+35%。
 *
 * 难度取值：advanceInCombat 逐帧按 owner 结算（玩家侧我方档位，默认砺刃 v2；
 * 敌方/友军 AI 轨一 k_s 映射），tooltip 无舰上下文按我方档位展示。
 */
class ASTDArcAdvancedFireControlHullMod : BaseHullMod() {

    companion object {
        private const val BASELINE_RANGE_PERCENT = -20f
        private const val BASELINE_WEAPON_FLUX_MULT = 1.20f
        private const val RAMP_DURATION = 6f

        /** 满 ramp 武器辐耗总乘区（三锚点 -30%/-40%/-70%）。 */
        val FULL_RAMP_WEAPON_FLUX = ScalingEntry(0.70f, 0.60f, 0.30f)

        /** 满 ramp 非导弹武器射速加成（三锚点 +10%/+20%/+50%，小数口径）。 */
        val RAMP_ROF_BOOST = ScalingEntry(0.10f, 0.20f, 0.50f)

        /** 满 ramp 非导弹武器伤害加成（三锚点 +10%/+20%/+50%，小数口径）。 */
        val RAMP_DAMAGE_BOOST = ScalingEntry(0.10f, 0.20f, 0.50f)

        /** 满 ramp 射弹飞行速度加成（三锚点 +15%/+20%/+35%，小数口径）。 */
        val RAMP_PROJ_SPEED_BOOST = ScalingEntry(0.15f, 0.20f, 0.35f)

        private const val BASELINE_STAT_ID = "astd_arc_advanced_fire_control_baseline"
        private const val RAMP_KEY = "astd_arc_advanced_fire_control_ramp:"

        private val THEME = HullmodThemes.ARC
    }

    /** 难度结算值（三锚点映射后，小数口径）。 */
    internal data class RampValues(
        val fullRampFluxMult: Float,
        val rofBoost: Float,
        val damageBoost: Float,
        val projSpeedBoost: Float,
    ) {
        companion object {
            fun resolve(isPlayer: Boolean): RampValues = RampValues(
                fullRampFluxMult = DifficultyTuningImpl.valueFor(FULL_RAMP_WEAPON_FLUX, isPlayer),
                rofBoost = DifficultyTuningImpl.valueFor(RAMP_ROF_BOOST, isPlayer),
                damageBoost = DifficultyTuningImpl.valueFor(RAMP_DAMAGE_BOOST, isPlayer),
                projSpeedBoost = DifficultyTuningImpl.valueFor(RAMP_PROJ_SPEED_BOOST, isPlayer),
            )
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.ballisticWeaponRangeBonus.modifyPercent(id, BASELINE_RANGE_PERCENT)
        stats.energyWeaponRangeBonus.modifyPercent(id, BASELINE_RANGE_PERCENT)
        stats.beamWeaponRangeBonus.modifyPercent(id, BASELINE_RANGE_PERCENT)
        stats.ballisticWeaponFluxCostMod.modifyMult(BASELINE_STAT_ID, BASELINE_WEAPON_FLUX_MULT)
        stats.energyWeaponFluxCostMod.modifyMult(BASELINE_STAT_ID, BASELINE_WEAPON_FLUX_MULT)
        stats.beamWeaponFluxCostMult.modifyMult(BASELINE_STAT_ID, BASELINE_WEAPON_FLUX_MULT)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk || ship.hitpoints <= 0f) return

        val key = "$RAMP_KEY${System.identityHashCode(ship)}"
        val current = (engine.customData[key] as? Float ?: 0f).coerceIn(0f, 1f)
        val target = if (hasSustainedLargeFire(ship)) 1f else 0f
        val delta = amount / RAMP_DURATION
        val ramp = if (target > current) {
            (current + delta).coerceAtMost(1f)
        } else {
            (current - delta).coerceAtLeast(0f)
        }

        if (ramp <= 0.001f) {
            engine.customData.remove(key)
            unapplyCombatRamp(ship.mutableStats)
            return
        }

        engine.customData[key] = ramp
        applyCombatRamp(ship.mutableStats, ramp, RampValues.resolve(ship.owner == 0))
    }

    private fun applyCombatRamp(stats: MutableShipStatsAPI, ramp: Float, values: RampValues) {
        val id = ASTDArcProductionShipIds.STAT_ARC_ADVANCED_FIRE_CONTROL
        val fluxMult = 1f + ((values.fullRampFluxMult / BASELINE_WEAPON_FLUX_MULT) - 1f) * ramp
        val rofMult = 1f + values.rofBoost * ramp
        val damageMult = 1f + values.damageBoost * ramp
        val projSpeedMult = 1f + values.projSpeedBoost * ramp

        stats.ballisticWeaponFluxCostMod.modifyMult(id, fluxMult)
        stats.energyWeaponFluxCostMod.modifyMult(id, fluxMult)
        stats.beamWeaponFluxCostMult.modifyMult(id, fluxMult)

        stats.ballisticRoFMult.modifyMult(id, rofMult)
        stats.energyRoFMult.modifyMult(id, rofMult)
        stats.ballisticProjectileSpeedMult.modifyMult(id, projSpeedMult)
        stats.energyProjectileSpeedMult.modifyMult(id, projSpeedMult)
        stats.ballisticWeaponDamageMult.modifyMult(id, damageMult)
        stats.energyWeaponDamageMult.modifyMult(id, damageMult)
        stats.beamWeaponDamageMult.modifyMult(id, damageMult)
    }

    private fun unapplyCombatRamp(stats: MutableShipStatsAPI) {
        val id = ASTDArcProductionShipIds.STAT_ARC_ADVANCED_FIRE_CONTROL
        stats.ballisticWeaponFluxCostMod.unmodify(id)
        stats.energyWeaponFluxCostMod.unmodify(id)
        stats.beamWeaponFluxCostMult.unmodify(id)
        stats.ballisticRoFMult.unmodify(id)
        stats.energyRoFMult.unmodify(id)
        stats.ballisticProjectileSpeedMult.unmodify(id)
        stats.energyProjectileSpeedMult.unmodify(id)
        stats.ballisticWeaponDamageMult.unmodify(id)
        stats.energyWeaponDamageMult.unmodify(id)
        stats.beamWeaponDamageMult.unmodify(id)
    }

    private fun hasSustainedLargeFire(ship: ShipAPI): Boolean {
        val weapons = try {
            ship.allWeapons
        } catch (_: Throwable) {
            null
        } ?: return false
        return weapons.any { weapon ->
            weapon.isEligibleLargeMainWeapon() &&
                    (safeIsFiring(weapon) || safeChargeLevel(weapon) > 0.2f || safeBurstFireTime(weapon) > 0f)
        }
    }

    private fun WeaponAPI.isEligibleLargeMainWeapon(): Boolean {
        if (safeIsDecorative(this)) return false
        if (safeType(this) == WeaponAPI.WeaponType.MISSILE) return false
        if (safeSize(this) != WeaponAPI.WeaponSize.LARGE) return false
        return !safeHasHint(this, WeaponAPI.AIHints.PD) &&
                !safeHasHint(this, WeaponAPI.AIHints.PD_ONLY) &&
                !safeHasHint(this, WeaponAPI.AIHints.PD_ALSO) &&
                !safeHasHint(this, WeaponAPI.AIHints.ANTI_FTR)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        // tooltip 展示口径：无舰上下文（装配面板）按我方档位展示（默认砺刃 v2）
        val values = RampValues.resolve(ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.arc_advanced_fire_control.desc")
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.arc_advanced_fire_control.table.base.row_0.label",
                    "ui.hullmod.arc_advanced_fire_control.table.base.row_0.value",
                    HullmodTone.DEFAULT, HullmodTone.RED,
                )
                row(
                    "ui.hullmod.arc_advanced_fire_control.table.base.row_1.label",
                    "ui.hullmod.arc_advanced_fire_control.table.base.row_1.value",
                    HullmodTone.DEFAULT, HullmodTone.RED,
                )
            }
            heading("ui.hullmod.arc_advanced_fire_control.section.ramp")
            para(
                "ui.hullmod.arc_advanced_fire_control.line.ramp_intro",
                hl("大型非点防御武器", HullmodTone.DEFAULT), hl("6", HullmodTone.DEFAULT)
            )
            table {
                row(
                    "ui.hullmod.arc_advanced_fire_control.table.ramp.row_0.label",
                    "ui.hullmod.arc_advanced_fire_control.table.ramp.row_0.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.fullRampFluxMult - 1f)),
                )
                row(
                    "ui.hullmod.arc_advanced_fire_control.table.ramp.row_1.label",
                    "ui.hullmod.arc_advanced_fire_control.table.ramp.row_1.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.rofBoost)),
                )
                row(
                    "ui.hullmod.arc_advanced_fire_control.table.ramp.row_2.label",
                    "ui.hullmod.arc_advanced_fire_control.table.ramp.row_2.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.damageBoost)),
                )
                row(
                    "ui.hullmod.arc_advanced_fire_control.table.ramp.row_3.label",
                    "ui.hullmod.arc_advanced_fire_control.table.ramp.row_3.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.projSpeedBoost)),
                )
            }
            para(
                "ui.hullmod.arc_advanced_fire_control.line.ramp_decay",
                hl("大型非点防御武器", HullmodTone.DEFAULT), hl("6", HullmodTone.DEFAULT)
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun safeIsFiring(weapon: WeaponAPI): Boolean = try {
        weapon.isFiring
    } catch (_: Throwable) {
        false
    }

    private fun safeChargeLevel(weapon: WeaponAPI): Float = try {
        weapon.chargeLevel
    } catch (_: Throwable) {
        0f
    }

    private fun safeBurstFireTime(weapon: WeaponAPI): Float = try {
        weapon.burstFireTimeRemaining
    } catch (_: Throwable) {
        0f
    }

    private fun safeIsDecorative(weapon: WeaponAPI): Boolean = try {
        weapon.isDecorative
    } catch (_: Throwable) {
        false
    }

    private fun safeType(weapon: WeaponAPI): WeaponAPI.WeaponType? = try {
        weapon.type
    } catch (_: Throwable) {
        null
    }

    private fun safeSize(weapon: WeaponAPI): WeaponAPI.WeaponSize? = try {
        weapon.size
    } catch (_: Throwable) {
        null
    }

    private fun safeHasHint(weapon: WeaponAPI, hint: WeaponAPI.AIHints): Boolean = try {
        weapon.hasAIHint(hint)
    } catch (_: Throwable) {
        false
    }
}
