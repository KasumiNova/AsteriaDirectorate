package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

/**
 * 精英狼群（astd_sc_elite_wolfpack）——影响所有护卫舰/驱逐舰/巡洋舰，
 * 主力舰与战机不生效（[SicTuning.wolfpackBonusEntry] 对不适用舰级返回 null）
 * （docs/design/skills/10-sic-skills.md 阶段三；数值声明见 [SicTuning]）。
 *
 * 机制落点（applyEffectsBeforeShipCreation）：
 * - 部署点提升（负面平衡，固定值无缩放）：`dynamic.getMod(Stats.DEPLOYMENT_POINTS_MOD).modifyMult(1 + x)`；
 * - 全面属性加成（按舰级选 entry 后统一乘区）：装甲/结构/辐能容量与耗散/航速/加减速/
 *   转向/三类武器射程、伤害、射速/三类备弹恢复/舰船系统射程与充能。
 *   两个语义特判：
 *   - `systemCooldownBonus` 是冷却时间乘区（值越低冷却越短；原版 SystemsExpertise 以
 *     modifyMult(id, 1 - x) 实现冷却减免），故按 1 - x 写入而非 1 + x；
 *   - `zeroFluxMinimumFluxLevel` 是 flat 阈值语义（零辐能加速的辐能水平门槛），
 *     不套乘区，跳过。
 */
class EliteWolfpackSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.elite_wolfpack.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val dpFrigate = SicShared.formatPercent(SicTuning.WOLFPACK_DP_FRIGATE)
        val dpDestroyer = SicShared.formatPercent(SicTuning.WOLFPACK_DP_DESTROYER)
        val dpCruiser = SicShared.formatPercent(SicTuning.WOLFPACK_DP_CRUISER)
        SicShared.negativeLine(
            tooltip,
            I18n.t(
                I18n.Categories.MOD, "ui.sic.elite_wolfpack.line.dp",
                "frigate" to dpFrigate, "destroyer" to dpDestroyer, "cruiser" to dpCruiser,
            ),
            "+$dpFrigate", "+$dpDestroyer", "+$dpCruiser",
        )

        val bonusFrigate = SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.WOLFPACK_BONUS_FRIGATE, true))
        val bonusDestroyer = SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.WOLFPACK_BONUS_DESTROYER, true))
        val bonusCruiser = SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.WOLFPACK_BONUS_CRUISER, true))
        SicShared.highlightLine(
            tooltip,
            I18n.t(
                I18n.Categories.MOD, "ui.sic.elite_wolfpack.line.bonus",
                "frigate" to bonusFrigate, "destroyer" to bonusDestroyer, "cruiser" to bonusCruiser,
            ),
        )
        SicShared.subLine(
            tooltip,
            I18n[I18n.Categories.MOD, "ui.sic.elite_wolfpack.sub.bonus"],
        )
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        val entry = SicTuning.wolfpackBonusEntry(hullSize) ?: return

        stats.dynamic.getMod(Stats.DEPLOYMENT_POINTS_MOD)
            .modifyMult(id, 1f + SicTuning.wolfpackDeploymentBonus(hullSize))

        val bonus = DifficultyTuningImpl.valueFor(entry, data.isPlayer)
        val mult = 1f + bonus

        stats.armorBonus.modifyMult(id, mult)
        stats.hullBonus.modifyMult(id, mult)
        stats.fluxCapacity.modifyMult(id, mult)
        stats.fluxDissipation.modifyMult(id, mult)
        stats.maxSpeed.modifyMult(id, mult)
        stats.acceleration.modifyMult(id, mult)
        stats.deceleration.modifyMult(id, mult)
        stats.maxTurnRate.modifyMult(id, mult)
        stats.turnAcceleration.modifyMult(id, mult)
        stats.ballisticWeaponRangeBonus.modifyMult(id, mult)
        stats.energyWeaponRangeBonus.modifyMult(id, mult)
        stats.missileWeaponRangeBonus.modifyMult(id, mult)
        stats.ballisticWeaponDamageMult.modifyMult(id, mult)
        stats.energyWeaponDamageMult.modifyMult(id, mult)
        stats.missileWeaponDamageMult.modifyMult(id, mult)
        stats.ballisticRoFMult.modifyMult(id, mult)
        stats.energyRoFMult.modifyMult(id, mult)
        stats.missileRoFMult.modifyMult(id, mult)
        stats.ballisticAmmoRegenMult.modifyMult(id, mult)
        stats.energyAmmoRegenMult.modifyMult(id, mult)
        stats.missileAmmoRegenMult.modifyMult(id, mult)
        // 冷却时间是反向语义（值越低冷却越短，原版 SystemsExpertise 口径）
        stats.systemCooldownBonus.modifyMult(id, 1f - bonus)
        stats.systemRangeBonus.modifyMult(id, mult)
        stats.systemRegenBonus.modifyMult(id, mult)
        // zeroFluxMinimumFluxLevel 为 flat 阈值语义（零辐能加速门槛），不套乘区，跳过
    }
}
