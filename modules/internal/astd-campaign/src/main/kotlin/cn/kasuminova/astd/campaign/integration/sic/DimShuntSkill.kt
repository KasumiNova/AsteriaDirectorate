package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import cn.kasuminova.astd.combat.hullmods.base.directionalArmorFraction
import cn.kasuminova.astd.combat.skills.ASTDCombatIntelTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

/**
 * 维度分流（astd_sc_dim_shunt）——影响所有相位舰船
 * （docs/design/skills/10-sic-skills.md 阶段三；数值声明见 [SicTuning]）。
 *
 * 相位判定：variant 基础/改造舰体 spec 的 isPhase（与引力调谐同一口径）。
 *
 * 机制落点（applyEffectsAfterShipCreation 幂等挂 [DimShuntListener]，SIC
 * `if (!ship.hasListenerOfClass(X)) ship.addListener(X)` 范式），同一监听器内两个独立效果：
 * - 装甲减伤计算值：按舰级三锚点（[SicTuning.dimArmorEntry]），强制排辐/过载中翻倍，
 *   上限为最大装甲值（armorRating）× 25%（翻倍状态下上限同样翻倍），见 [SicTuning.dimArmorBonus]；
 *   伤害乘区口径复用菀星战斗智能相位上浮减伤（[ASTDCombatIntelTuning.bonusArmorDamageRatio] +
 *   [ASTDArmorDamageReduction.hitStrength] + [directionalArmorFraction]）——按「装甲 + 减伤值」结算，
 *   不与原版装甲重复计数。所有伤害类型生效；护盾命中（shieldHit）不生效；
 * - 能量伤害吸收：仅 dmg.type == ENERGY——伤害减免 [SicTuning.DIM_ENERGY_ABSORB] 比例，
 *   被减免的额度等额转化为硬辐能耗散（[SicTuning.dimEnergyAbsorbed]）。
 *   FluxTrackerAPI 无 decreaseHardFlux（starfarer.api.jar 核实），以 setHardFlux/setCurrFlux
 *   组合实现等额硬辐能耗散（currFlux 同步扣减且不低于新的 hardFlux）。
 *
 * 难度数值在监听器内逐命中实时解析（LunaLib 设置变更即时生效）。
 */
class DimShuntSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.dim_shunt.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val frigate = SicShared.formatNumber(DifficultyTuningImpl.valueFor(SicTuning.DIM_ARMOR_FRIGATE, true))
        val destroyer = SicShared.formatNumber(DifficultyTuningImpl.valueFor(SicTuning.DIM_ARMOR_DESTROYER, true))
        val cruiser = SicShared.formatNumber(DifficultyTuningImpl.valueFor(SicTuning.DIM_ARMOR_CRUISER, true))
        val capital = SicShared.formatNumber(DifficultyTuningImpl.valueFor(SicTuning.DIM_ARMOR_CAPITAL, true))
        tooltip.hullmodCard(SicShared.SKILL_TOOLTIP_WIDTH, SicShared.CARD_THEME, null, topGapFill = 0f) {
            para(
                "ui.sic.dim_shunt.line.armor",
                Misc.getHighlightColor(),
                8f,
                v("frigate", frigate), v("destroyer", destroyer), v("cruiser", cruiser), v("capital", capital),
            )
            custom {
                SicShared.subLine(
                    it,
                    I18n.t(
                        I18n.Categories.MOD, "ui.sic.dim_shunt.sub.armor",
                        "cap" to SicShared.formatPercent(SicTuning.DIM_ARMOR_CAP_FRACTION),
                    ),
                    2f,
                    SicShared.formatPercent(SicTuning.DIM_ARMOR_CAP_FRACTION),
                )
            }
            para(
                "ui.sic.dim_shunt.line.absorb",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.DIM_ENERGY_ABSORB, true))),
            )
        }
    }

    override fun applyEffectsAfterShipCreation(data: SCData, ship: ShipAPI, variant: ShipVariantAPI, id: String) {
        if (!GravTuningSkill.isPhaseVariant(variant)) return
        if (!ship.hasListenerOfClass(DimShuntListener::class.java)) {
            ship.addListener(DimShuntListener(ship, data.isPlayer))
        }
    }

    /**
     * 维度分流承伤监听器（机制依据见外层类 KDoc）：装甲减伤计算值与能量伤害吸收两个独立效果，
     * 护盾命中两者均不生效；非能量伤害不吸收但装甲减伤照常。
     */
    private class DimShuntListener(
        private val ship: ShipAPI,
        private val isPlayer: Boolean,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (ship.isHulk || !ship.isAlive) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(
            param: Any?,
            target: CombatEntityAPI?,
            damage: DamageAPI?,
            point: Vector2f?,
            shieldHit: Boolean,
        ): String? {
            val dmg = damage ?: return null
            if (target !== ship || shieldHit || ship.isHulk || !ship.isAlive) return null

            applyArmorBonus(param, dmg, point)
            applyEnergyAbsorb(dmg)
            return null
        }

        /** 装甲减伤计算值（与相位上浮减伤同一伤害乘区口径；命中点缺失时无法定向结算，跳过本效果）。 */
        private fun applyArmorBonus(param: Any?, dmg: DamageAPI, point: Vector2f?) {
            val hitPoint = point ?: return
            val base = DifficultyTuningImpl.valueFor(SicTuning.dimArmorEntry(ship.hullSize), isPlayer)
            val ventingOrOverloaded = ship.fluxTracker.isVenting || ship.fluxTracker.isOverloaded
            val bonus = SicTuning.dimArmorBonus(base, ventingOrOverloaded, ship.armorGrid.armorRating)
            if (bonus <= 0f) return

            val finalMaxArmor = ship.mutableStats.armorBonus.computeEffective(ship.armorGrid.armorRating).coerceAtLeast(1f)
            val armor = finalMaxArmor * directionalArmorFraction(ship, hitPoint)
            val hitStrength = ASTDArmorDamageReduction.hitStrength(dmg.type, dmg.baseDamage, param is BeamAPI || dmg.isDps)
            val ratio = ASTDCombatIntelTuning.bonusArmorDamageRatio(
                hitStrength = hitStrength,
                armor = armor,
                minArmor = ship.armorGrid.armorRating * ship.mutableStats.minArmorFraction.modifiedValue,
                effectiveArmorMult = ship.mutableStats.effectiveArmorBonus.mult,
                maxArmorDamageReduction = ship.mutableStats.maxArmorDamageReduction.modifiedValue,
                bonusArmor = bonus,
            )
            if (ratio < 0.999f) {
                dmg.modifier.modifyMult(ARMOR_STAT_ID, ratio)
            }
        }

        /** 能量伤害吸收：减免吸收比例的伤害，被减免额度等额转化为硬辐能耗散。 */
        private fun applyEnergyAbsorb(dmg: DamageAPI) {
            if (dmg.type != DamageType.ENERGY) return
            val absorbFraction = DifficultyTuningImpl.valueFor(SicTuning.DIM_ENERGY_ABSORB, isPlayer)
            val absorbed = SicTuning.dimEnergyAbsorbed(dmg.damage, absorbFraction)
            if (absorbed <= 0f) return

            dmg.modifier.modifyMult(ABSORB_STAT_ID, 1f - absorbFraction)
            // FluxTrackerAPI 无 decreaseHardFlux：setHardFlux + setCurrFlux 组合实现等额硬辐能耗散
            val tracker = ship.fluxTracker
            tracker.setHardFlux((tracker.hardFlux - absorbed).coerceAtLeast(0f))
            tracker.setCurrFlux((tracker.currFlux - absorbed).coerceAtLeast(tracker.hardFlux))
        }
    }

    companion object {
        /** 装甲减伤计算值的 dmg.modifier key。 */
        private const val ARMOR_STAT_ID = "astd_sc_dim_shunt_armor"

        /** 能量伤害吸收的 dmg.modifier key。 */
        private const val ABSORB_STAT_ID = "astd_sc_dim_shunt_absorb"
    }
}
