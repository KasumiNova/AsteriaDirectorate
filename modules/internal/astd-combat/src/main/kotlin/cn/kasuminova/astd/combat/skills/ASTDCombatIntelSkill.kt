package cn.kasuminova.astd.combat.skills

import cn.kasuminova.astd.combat.hullmods.base.isPhaseShip
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import cn.kasuminova.astd.combat.hullmods.base.directionalArmorFraction
import cn.kasuminova.astd.combat.skills.ASTDCombatIntelTuning.DefenseBranch
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.characters.LevelBasedEffect
import com.fs.starfarer.api.characters.ShipSkillEffect
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier
import org.lwjgl.util.vector.Vector2f
import kotlin.math.roundToInt

/**
 * 菀星战斗智能（astd_combat_intel）——本模组全部制式 AI 核心的固有技能
 * （docs/design/skills/00-ai-cores.md；数值声明见 [ASTDCombatIntelTuning]）。
 *
 * 普通效果（Level1~Level5，按座舰类型分支）：
 * - 相位舰船（[isPhaseShip]）：峰值时间乘区（Level1）+
 *   上浮后按舰级分档、随时间线性衰减的额外装甲减伤（Level2，[PhaseSurfaceArmorListener]）。
 *   若特殊系统为引力相位（astd_gravity_phase）：Level3 写原版 dynamic stat
 *   （失速阈值 0.75/0.90、相位时流 ×0.75），由 PhaseCloakStats 体系消费
 *   （GravityPhaseCloakStats 继承原版 PhaseCloakStats，消费点一致）。
 *   非引力相位的相位舰 AI 覆写分支经调研确认不可行（见阶段报告）：
 *   ShipSystemAPI/ShipSystemSpecAPI 均无运行时替换 AI 脚本的入口，.system 的 aiScript
 *   为全局静态数据，技能作用域无法按舰改写，本实现不包含该分支。
 * - 非相位舰船：Level4/Level5 挂 [FlatHitReductionListener]，按运行时护盾状态
 *   分派护盾/装甲单次固定减免（护盾下限 10、装甲下限 5）。
 *
 * 精英效果（Level6/Level7）：能量武器基础射程按阈值补足
 * （[EliteEnergyRangeListener]，逐武器判定 weapon.spec 原始射程）+
 * 能量武器辐能产出乘区。
 *
 * 监听器经 [ensureShipListener] 挂到座舰（stats.entity 为 ShipAPI 时，即战斗中）；
 * 难度数值在监听器内逐帧/逐命中实时解析，LunaLib 设置变更即时生效。
 */
class ASTDCombatIntelSkill {

    class Level1 : ShipSkillEffect {
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
    }

    class Level2 : ShipSkillEffect {
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

    class Level3 : ShipSkillEffect {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize?, id: String, level: Float) {
            if (!isPhaseShip(stats)) return
            if (stats.variant?.hullSpec?.shipSystemId != ASTDCombatIntelTuning.GRAV_PHASE_SYSTEM_ID) return
            val hasCoils = stats.variant?.hasHullMod(ASTDCombatIntelTuning.ADAPTIVE_COILS_HULLMOD_ID) == true
            stats.dynamic.getMod(ASTDCombatIntelTuning.FLUX_THRESHOLD_MOD)
                .modifyPercent(id, ASTDCombatIntelTuning.fluxThresholdPercent(hasCoils))
            // phase_time_mult 的消费点（PhaseCloakStats.getMaxTimeMult）用单参 getValue 读 stats map，
            // 必须写 getStat；getMod 写的是互不相通的 mods map
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

    class Level4 : ShipSkillEffect {
        // 与 Level5 共用 DATA_FLAT_REDUCTION_LISTENER 挂同一个 FlatHitReductionListener：
        // unapply 无引用计数，任一侧 unapply 即摘除——因此 Level4/Level5 必须永远同组出现，
        // 拆分进不同 effectGroup 会造成互摘（stat 刷新期间护盾/装甲减免短时丢失）
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

    class Level5 : ShipSkillEffect {
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

    class Level6 : ShipSkillEffect {
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
    }

    class Level7 : ShipSkillEffect {
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

    /**
     * 相位上浮减伤：advance 追踪相位状态，上浮沿（isPhased true → false）开启减伤窗口，
     * 窗口内额外装甲减伤值线性衰减；装甲命中时按 [ASTDCombatIntelTuning.bonusArmorDamageRatio]
     * 写入伤害乘区（等价于按「装甲 + 减伤值」结算，不与原版装甲重复计数）。
     */
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

    /**
     * 非相位舰单次固定减免：按运行时护盾状态分派（ship.shield 非空即有盾，
     * 覆盖护盾分流等船插改造后的无盾情形）；DPS 类伤害按 dpsDuration 折算后减免再换算回乘区。
     */
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

    /**
     * 精英能量武器射程补足：逐武器按 weapon.spec 原始射程判定（不受其他射程修正影响），
     * 低于阈值时补 min(上限, 阈值 - 原始射程)，最终基础射程不超过阈值。
     */
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

        /**
         * 技能 tooltip 展示数值：getEffectDescription 拿不到 stats/ship 上下文，
         * 统一按我方档位解析（技能描述面向玩家，随 LunaLib 难度设置即时变化）。
         */
        private fun displayValues(): ASTDCombatIntelTuning.Values =
            ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, true)

        /** 展示用数字格式：整数值不带小数点（800 而非 800.0），小数值原样（衰减 1.5 秒）。 */
        private fun formatNumber(value: Float): String =
            if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

        /** 展示用百分比格式：0.2 → "20%"（模板只放 %pct% 占位符，百分号由值携带）。 */
        private fun formatPercent(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"

        /**
         * 监听器幂等挂载：技能 apply 在每次 stat 刷新都会触发，customData 记录既有实例避免重复挂。
         * customData 首写必须走 setCustomData（字段惰性为 null 时 getCustomData() 返回一次性空表，
         * 与 AdvancedEcmNetworkHullMod 口径一致）。
         */
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
