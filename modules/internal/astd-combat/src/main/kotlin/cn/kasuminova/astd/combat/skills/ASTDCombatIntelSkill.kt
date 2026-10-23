package cn.kasuminova.astd.combat.skills

import cn.kasuminova.astd.combat.hullmods.base.isPhaseShip
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import cn.kasuminova.astd.combat.hullmods.base.directionalArmorFraction
import cn.kasuminova.astd.combat.skills.ASTDCombatIntelTuning.DefenseBranch
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.characters.CustomSkillDescription
import com.fs.starfarer.api.characters.LevelBasedEffect
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI
import com.fs.starfarer.api.characters.ShipSkillEffect
import com.fs.starfarer.api.characters.SkillSpecAPI
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.roundToInt

class ASTDCombatIntelSkill {

    // 原版技能 tooltip 对 hasCustomDescription=true 的 effect 以 createCustomDescription 取代默认描述行，空实现即由卡片承接
    abstract class CardOnlyDescription : ShipSkillEffect, CustomSkillDescription {
        final override fun hasCustomDescription(): Boolean = true

        override fun createCustomDescription(
            stats: MutableCharacterStatsAPI,
            skill: SkillSpecAPI,
            tooltip: TooltipMakerAPI,
            width: Float,
        ) {
        }
    }

    class Level1 : CardOnlyDescription() {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String, level: Float) {
            if (!isPhaseShip(stats)) return
            val values = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, isPlayerShip(stats))
            stats.peakCRDuration.modifyMult(id, 1f + values.peakCrBonus)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String) {
            stats.peakCRDuration.unmodifyMult(id)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.t(
                I18n.Categories.MOD, "ui.skill.astd_combat_intel.effect.peak_cr",
                "pct" to formatPercent(displayValues().peakCrBonus),
            )

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.PILOTED_SHIP

        override fun createCustomDescription(
            stats: MutableCharacterStatsAPI,
            skill: SkillSpecAPI,
            tooltip: TooltipMakerAPI,
            width: Float,
        ) = renderDescriptionCard(tooltip, width, elite = false)
    }

    class Level2 : CardOnlyDescription() {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String, level: Float) {
            if (!isPhaseShip(stats)) return
            val ship = stats.entity as? ShipAPI ?: return
            ensureShipListener(ship, DATA_SURFACE_ARMOR_LISTENER) { PhaseSurfaceArmorListener(ship) }
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String) {
            val ship = stats.entity as? ShipAPI ?: return
            discardShipListener(ship, DATA_SURFACE_ARMOR_LISTENER)
        }

        override fun getEffectDescription(level: Float): String {
            val values = displayValues()
            return I18n.t(
                I18n.Categories.MOD, "ui.skill.astd_combat_intel.effect.surface_armor",
                "frigate" to formatNumber(values.surfaceArmorFrigate),
                "destroyer" to formatNumber(values.surfaceArmorDestroyer),
                "cruiser" to formatNumber(values.surfaceArmorCruiser),
                "capital" to formatNumber(values.surfaceArmorCapital),
                "decay" to formatNumber(values.surfaceArmorDecay),
            )
        }

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.PILOTED_SHIP
    }

    class Level3 : CardOnlyDescription() {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String, level: Float) {
            if (!isPhaseShip(stats)) return
            if (stats.variant?.hullSpec?.shipSystemId != ASTDCombatIntelTuning.GRAV_PHASE_SYSTEM_ID) return
            val hasCoils = stats.variant?.hasHullMod(ASTDCombatIntelTuning.ADAPTIVE_COILS_HULLMOD_ID) == true
            stats.dynamic.getMod(ASTDCombatIntelTuning.FLUX_THRESHOLD_MOD)
                .modifyPercent(id, ASTDCombatIntelTuning.fluxThresholdPercent(hasCoils))
            // PhaseCloakStats.getMaxTimeMult 用单参 getValue 读 stats map，必须写 getStat；getMod 写的是互不相通的 mods map
            stats.dynamic.getStat(ASTDCombatIntelTuning.PHASE_TIME_MULT_MOD)
                .modifyMult(id, ASTDCombatIntelTuning.PHASE_TIME_FLOW_MULT)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String) {
            stats.dynamic.getMod(ASTDCombatIntelTuning.FLUX_THRESHOLD_MOD).unmodifyPercent(id)
            stats.dynamic.getStat(ASTDCombatIntelTuning.PHASE_TIME_MULT_MOD).unmodifyMult(id)
        }

        override fun getEffectDescription(level: Float): String =
            I18n[I18n.Categories.MOD, "ui.skill.astd_combat_intel.effect.grav_phase"]

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.PILOTED_SHIP
    }

    class Level4 : CardOnlyDescription() {
        // 与 Level5 共用同一 listener：unapply 无引用计数，任一侧 unapply 即摘除，两侧必须同组出现
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String, level: Float) {
            if (isPhaseShip(stats)) return
            val ship = stats.entity as? ShipAPI ?: return
            ensureShipListener(ship, DATA_FLAT_REDUCTION_LISTENER) { FlatHitReductionListener(ship) }
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String) {
            val ship = stats.entity as? ShipAPI ?: return
            discardShipListener(ship, DATA_FLAT_REDUCTION_LISTENER)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.t(
                I18n.Categories.MOD, "ui.skill.astd_combat_intel.effect.shield",
                "reduction" to formatNumber(displayValues().shieldFlatReduction),
            )

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.PILOTED_SHIP
    }

    class Level5 : CardOnlyDescription() {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String, level: Float) {
            if (isPhaseShip(stats)) return
            val ship = stats.entity as? ShipAPI ?: return
            ensureShipListener(ship, DATA_FLAT_REDUCTION_LISTENER) { FlatHitReductionListener(ship) }
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String) {
            val ship = stats.entity as? ShipAPI ?: return
            discardShipListener(ship, DATA_FLAT_REDUCTION_LISTENER)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.t(
                I18n.Categories.MOD, "ui.skill.astd_combat_intel.effect.armor",
                "reduction" to formatNumber(displayValues().armorFlatReduction),
            )

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.PILOTED_SHIP
    }

    class Level6 : CardOnlyDescription() {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String, level: Float) {
            val ship = stats.entity as? ShipAPI ?: return
            ensureShipListener(ship, DATA_ELITE_RANGE_LISTENER) { EliteEnergyRangeListener() }
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String) {
            val ship = stats.entity as? ShipAPI ?: return
            discardShipListener(ship, DATA_ELITE_RANGE_LISTENER)
        }

        override fun getEffectDescription(level: Float): String {
            val values = displayValues()
            return I18n.t(
                I18n.Categories.MOD, "ui.skill.astd_combat_intel.effect.elite_range",
                "threshold" to formatNumber(values.eliteRangeThreshold),
                "bonus" to formatNumber(values.eliteRangeBonus),
            )
        }

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.PILOTED_SHIP

        override fun createCustomDescription(
            stats: MutableCharacterStatsAPI,
            skill: SkillSpecAPI,
            tooltip: TooltipMakerAPI,
            width: Float,
        ) = renderDescriptionCard(tooltip, width, elite = true)
    }

    class Level7 : CardOnlyDescription() {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String, level: Float) {
            val values = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, isPlayerShip(stats))
            stats.energyWeaponFluxCostMod.modifyMult(id, 1f - values.eliteFluxReduction)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String) {
            stats.energyWeaponFluxCostMod.unmodifyMult(id)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.t(
                I18n.Categories.MOD, "ui.skill.astd_combat_intel.effect.elite_flux",
                "pct" to formatPercent(displayValues().eliteFluxReduction),
            )

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.PILOTED_SHIP
    }

    private class PhaseSurfaceArmorListener(
        private val ship: ShipAPI,
    ) : DamageTakenModifier, AdvanceableListener {

        private var wasPhased: Boolean = ship.isPhased
        private var windowInitial: Float = 0f
        private var windowDecay: Float = 1f
        private var windowElapsed: Float = Float.MAX_VALUE

        override fun advance(amount: Float) {
            if (ship.isHulk || !ship.isAlive) {
                ship.removeListener(this)
                ship.removeCustomData(DATA_SURFACE_ARMOR_LISTENER)
                return
            }
            val phased = ship.isPhased
            if (wasPhased && !phased) {
                val values = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
                windowInitial = ASTDCombatIntelTuning.surfaceArmorFor(values, ship.hullSize)
                windowDecay = values.surfaceArmorDecay
                windowElapsed = 0f
            }
            wasPhased = phased
            if (windowElapsed < windowDecay) windowElapsed += amount
        }

        override fun modifyDamageTaken(
            param: Any?,
            target: CombatEntityAPI?,
            damage: DamageAPI?,
            point: Vector2f?,
            shieldHit: Boolean,
        ): String? {
            val dmg = damage ?: return null
            val hitPoint = point ?: return null
            if (target !== ship || shieldHit || ship.isHulk || !ship.isAlive) return null
            val bonus = ASTDCombatIntelTuning.surfaceArmorAt(windowInitial, windowDecay, windowElapsed)
            if (bonus <= 0f) return null

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
                dmg.modifier.modifyMult(ASTDCombatIntelTuning.STAT_SURFACE_ARMOR, ratio)
            }
            return null
        }
    }

    private class FlatHitReductionListener(
        private val ship: ShipAPI,
    ) : DamageTakenModifier {

        override fun modifyDamageTaken(
            param: Any?,
            target: CombatEntityAPI?,
            damage: DamageAPI?,
            point: Vector2f?,
            shieldHit: Boolean,
        ): String? {
            val dmg = damage ?: return null
            if (target !== ship || ship.isHulk || !ship.isAlive) return null
            val values = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
            val branch = ASTDCombatIntelTuning.defenseBranch(isPhase = false, hasShield = ship.shield != null)
            val reduction: Float
            val floor: Float
            when (branch) {
                DefenseBranch.SHIELDED -> {
                    if (!shieldHit) return null
                    reduction = values.shieldFlatReduction
                    floor = ASTDCombatIntelTuning.SHIELD_DAMAGE_FLOOR
                }
                DefenseBranch.UNSHIELDED -> {
                    if (shieldHit) return null
                    reduction = values.armorFlatReduction
                    floor = ASTDCombatIntelTuning.ARMOR_DAMAGE_FLOOR
                }
                DefenseBranch.PHASE -> return null
            }

            val duration = if (dmg.isDps) dmg.dpsDuration.coerceAtLeast(0f) else 1f
            val effective = dmg.damage.coerceAtLeast(0f) * duration
            if (effective <= 0f) return null
            val reduced = ASTDCombatIntelTuning.applyFlatReduction(effective, reduction, floor)
            val mult = (reduced / effective).coerceIn(0f, 1f)
            if (mult < 0.999f) {
                dmg.modifier.modifyMult(ASTDCombatIntelTuning.STAT_FLAT_REDUCTION, mult)
            }
            return null
        }
    }

    private class EliteEnergyRangeListener : WeaponBaseRangeModifier {
        override fun getWeaponBaseRangePercentMod(ship: ShipAPI, weapon: WeaponAPI): Float = 0f

        override fun getWeaponBaseRangeMultMod(ship: ShipAPI, weapon: WeaponAPI): Float = 1f

        override fun getWeaponBaseRangeFlatMod(ship: ShipAPI, weapon: WeaponAPI): Float {
            if (weapon.type != WeaponAPI.WeaponType.ENERGY) return 0f
            val base = weapon.spec?.maxRange ?: return 0f
            val values = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
            return ASTDCombatIntelTuning.eliteRangeFlatBonus(base, values.eliteRangeThreshold, values.eliteRangeBonus)
        }
    }

    companion object {
        private const val DATA_SURFACE_ARMOR_LISTENER = "astd_combat_intel_surface_armor_listener"
        private const val DATA_FLAT_REDUCTION_LISTENER = "astd_combat_intel_flat_reduction_listener"
        private const val DATA_ELITE_RANGE_LISTENER = "astd_combat_intel_elite_range_listener"

        private fun isPlayerShip(stats: MutableShipStatsAPI): Boolean =
            (stats.entity as? ShipAPI)?.owner == 0

        private fun displayValues(): ASTDCombatIntelTuning.Values =
            ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, true)

        private fun formatNumber(value: Float): String =
            if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

        private fun formatPercent(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"

        private fun renderDescriptionCard(tooltip: TooltipMakerAPI, width: Float, elite: Boolean) {
            val values = displayValues()
            tooltip.hullmodCard(width, HullmodThemes.ARC, null, topGapFill = 0f) {
                if (elite) {
                    para(
                        "ui.skill.astd_combat_intel.line.elite_range",
                        v("threshold", formatNumber(values.eliteRangeThreshold)),
                        v("bonus", formatNumber(values.eliteRangeBonus)),
                        hl(formatNumber(values.eliteRangeThreshold), HullmodTone.HIGHLIGHT),
                        hl(formatNumber(values.eliteRangeBonus), HullmodTone.HIGHLIGHT),
                    )
                    para(
                        "ui.skill.astd_combat_intel.line.elite_flux",
                        v("pct", formatPercent(values.eliteFluxReduction)),
                        hl(formatPercent(values.eliteFluxReduction), HullmodTone.HIGHLIGHT),
                    )
                    return@hullmodCard
                }
                para("ui.skill.astd_combat_intel.desc.intro")
                heading("ui.skill.astd_combat_intel.section.phase")
                para(
                    "ui.skill.astd_combat_intel.line.peak_cr",
                    v("pct", formatPercent(values.peakCrBonus)),
                    hl(formatPercent(values.peakCrBonus), HullmodTone.HIGHLIGHT),
                )
                para(
                    "ui.skill.astd_combat_intel.line.surface_armor",
                    v("frigate", formatNumber(values.surfaceArmorFrigate)),
                    v("destroyer", formatNumber(values.surfaceArmorDestroyer)),
                    v("cruiser", formatNumber(values.surfaceArmorCruiser)),
                    v("capital", formatNumber(values.surfaceArmorCapital)),
                    v("decay", formatNumber(values.surfaceArmorDecay)),
                    hl(formatNumber(values.surfaceArmorFrigate), HullmodTone.HIGHLIGHT),
                    hl(formatNumber(values.surfaceArmorDestroyer), HullmodTone.HIGHLIGHT),
                    hl(formatNumber(values.surfaceArmorCruiser), HullmodTone.HIGHLIGHT),
                    hl(formatNumber(values.surfaceArmorCapital), HullmodTone.HIGHLIGHT),
                )
                para(
                    "ui.skill.astd_combat_intel.line.grav_phase",
                    hl("75%", HullmodTone.HIGHLIGHT), hl("90%", HullmodTone.HIGHLIGHT),
                    hl("-25%", HullmodTone.HIGHLIGHT),
                )
                heading("ui.skill.astd_combat_intel.section.non_phase")
                para(
                    "ui.skill.astd_combat_intel.line.shield",
                    v("reduction", formatNumber(values.shieldFlatReduction)),
                    hl(formatNumber(values.shieldFlatReduction), HullmodTone.HIGHLIGHT),
                )
                para(
                    "ui.skill.astd_combat_intel.line.armor",
                    v("reduction", formatNumber(values.armorFlatReduction)),
                    hl(formatNumber(values.armorFlatReduction), HullmodTone.HIGHLIGHT),
                )
                spacer(6f)
                para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
            }
        }

        // 技能 apply 在每次 stat 刷新都会触发，customData 记录既有实例避免重复挂；首写必须走 setCustomData（字段惰性为 null 时 getCustomData() 返回一次性空表）
        private inline fun <reified T : Any> ensureShipListener(ship: ShipAPI, key: String, factory: () -> T): T {
            (ship.customData[key] as? T)?.let { return it }
            val listener = factory()
            ship.addListener(listener)
            ship.setCustomData(key, listener)
            return listener
        }

        private fun discardShipListener(ship: ShipAPI, key: String) {
            val listener = ship.customData[key] ?: return
            ship.removeCustomData(key)
            ship.removeListener(listener)
        }
    }
}
