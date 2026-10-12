package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.hullmods.HullmodIncompatibility
import cn.kasuminova.astd.combat.hullmods.base.IncompatibleHullmodStripper
import cn.kasuminova.astd.combat.hullmods.base.signedPercentOf
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier
import com.fs.starfarer.api.combat.listeners.WeaponOPCostModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color
import kotlin.math.roundToInt

/**
 * 高级舰装集成（列星级 XC-103 内置 Hullmod，规格 blue/20-production.md §驱逐舰-独特 Hullmod）：
 * 目标定位系统与武器管线集成。
 *
 * 随难度系数的效果（2026-10 裁定，advanceInCombat 逐帧按 owner 结算）：
 * 1. 非导弹武器射程 +15%/+20%/+40%（弹道/能量/光束三条射程乘区）；
 * 2. 射弹飞行速度 +15%/+20%/+40%（实弹与能量武器）；
 * 3. 武器辐能产出 -15%/-20%/-40%（实弹/能量/光束/导弹四条辐耗乘区）；
 * 4. 基础射程低于阈值的非导弹武器获得额外基础射程补偿，阈值|增益档位
 *    600|150 / 700|200 / 1000|350（战斗内 WeaponBaseRangeModifier 监听器逐武器结算，
 *    see [shortRangeFlatBonus]）。
 *
 * 固定效果（装配期写入，不随难度）：
 * 5. 小型武器装配点 -1 再 -20%，中型武器 -2 再 -20%
 *    （WeaponOPCostModifier 监听器，装配结算唯一入口 [weaponOpCost]）；
 * 6. 不兼容任何其他目标定位系统——互斥走 [IncompatibleHullmodStripper] 延迟清理：
 *    applyEffectsBeforeShipCreation 只检测入队（原版 updateStatsForOpCosts 实时迭代 hullMods
 *    期间回调本方法，结构性 removeMod 必抛 CME），五路清理延后到 AfterCreation/advanceInCombat/
 *    战役每帧 drainer 安全点执行；原版候选船插不受控无法灰掉，接受"装上即清理"行为
 *    （SKILL: hullmod-incompatibility-guidelines）。
 *
 * OP 减免与短射程补偿数学抽为纯函数（单元测试直接驱动）。
 *
 * 注意：必须重写 [affectsOPCosts] 返回 true——装配界面 OP 走 variant.statsForOpCosts，
 * 仅当任一船插声明影响 OP 时该 stats 才会创建并回调 applyEffectsBeforeShipCreation
 * （原版 HullVariantSpec.updateStatsForOpCosts 判例，DiableAvionicsMountBI 同口径）。
 */
class ASTDArcAdvancedTargetingSystemHullMod : BaseHullMod() {

    companion object {
        /** 非导弹武器射程加成（三锚点 +15%/+20%/+40%，百分数口径）。 */
        val RANGE_PERCENT = ScalingEntry(15f, 20f, 40f)

        /** 射弹飞行速度加成（三锚点 +15%/+20%/+40%，百分数口径）。 */
        val PROJECTILE_SPEED_PERCENT = ScalingEntry(15f, 20f, 40f)

        /** 武器辐能产出加成（三锚点 -15%/-20%/-40%，百分数口径）。 */
        val WEAPON_FLUX_PERCENT = ScalingEntry(-15f, -20f, -40f)

        /** 短射程补偿阈值（三锚点 600/700/1000 su）。 */
        val SHORT_RANGE_THRESHOLD = ScalingEntry(600f, 700f, 1000f)

        /** 短射程补偿增益上限（三锚点 150/200/350 su）。 */
        val SHORT_RANGE_MAX_FLAT = ScalingEntry(150f, 200f, 350f)

        private const val SMALL_WEAPON_OP_FLAT_REDUCTION = 1
        private const val MEDIUM_WEAPON_OP_FLAT_REDUCTION = 2
        private const val OP_PERCENT_REDUCTION = 0.2f

        /** 禁装船插列表：真相来源 [HullmodIncompatibility]，此处保留别名供单测断言集中定义。 */
        internal val INCOMPATIBLE_TARGETING_HULLMODS: Set<String> =
            HullmodIncompatibility.forbiddenByController(ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM)

        private val THEME = HullmodThemes.ARC

        /**
         * 短射程武器基础射程补偿（纯函数）：基础射程 < [threshold] 时补偿
         * min(阈值 - 基础射程, [maxFlat])，其余 0。
         */
        internal fun shortRangeFlatBonus(baseRange: Float, threshold: Float, maxFlat: Float): Float {
            if (baseRange <= 0f || baseRange >= threshold) return 0f
            return (threshold - baseRange).coerceAtMost(maxFlat)
        }

        /**
         * 武器装配点减免（纯函数）：小型 -[SMALL_WEAPON_OP_FLAT_REDUCTION]、
         * 中型 -[MEDIUM_WEAPON_OP_FLAT_REDUCTION]，随后统一 -20%，下限 0；大型与其他槽位不动。
         */
        internal fun weaponOpCost(size: WeaponAPI.WeaponSize?, currentCost: Int): Int {
            val flat = when (size) {
                WeaponAPI.WeaponSize.SMALL -> SMALL_WEAPON_OP_FLAT_REDUCTION
                WeaponAPI.WeaponSize.MEDIUM -> MEDIUM_WEAPON_OP_FLAT_REDUCTION
                else -> return currentCost
            }
            return ((currentCost - flat) * (1f - OP_PERCENT_REDUCTION)).roundToInt().coerceAtLeast(0)
        }
    }

    /** 难度结算值（三锚点映射后）。 */
    internal data class Values(
        val rangePercent: Float,
        val projSpeedPercent: Float,
        val weaponFluxPercent: Float,
        val shortRangeThreshold: Float,
        val shortRangeMaxFlat: Float,
    ) {
        companion object {
            fun resolve(isPlayer: Boolean): Values = Values(
                rangePercent = DifficultyTuningImpl.valueFor(RANGE_PERCENT, isPlayer),
                projSpeedPercent = DifficultyTuningImpl.valueFor(PROJECTILE_SPEED_PERCENT, isPlayer),
                weaponFluxPercent = DifficultyTuningImpl.valueFor(WEAPON_FLUX_PERCENT, isPlayer),
                shortRangeThreshold = DifficultyTuningImpl.valueFor(SHORT_RANGE_THRESHOLD, isPlayer),
                shortRangeMaxFlat = DifficultyTuningImpl.valueFor(SHORT_RANGE_MAX_FLAT, isPlayer),
            )
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        // 只检测入队：本回调可能在原版 OP 结算的 hullMods 实时迭代内触发，结构性移除会抛 CME
        IncompatibleHullmodStripper.requestStrip(
            stats.variant,
            INCOMPATIBLE_TARGETING_HULLMODS,
            ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
        )

        stats.addListener(WeaponOPCostReduction())
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        // ShipFactory 迭代 getAllMods() 快照，此处入队并立即清理是安全的
        IncompatibleHullmodStripper.requestStrip(
            ship.variant,
            INCOMPATIBLE_TARGETING_HULLMODS,
            ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
        )
        IncompatibleHullmodStripper.drainPending()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        IncompatibleHullmodStripper.drainPending()
        if (ship.isHulk) return

        val values = Values.resolve(ship.owner == 0)
        val stats = ship.mutableStats
        stats.ballisticWeaponRangeBonus.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.rangePercent)
        stats.energyWeaponRangeBonus.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.rangePercent)
        stats.beamWeaponRangeBonus.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.rangePercent)
        stats.ballisticProjectileSpeedMult.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.projSpeedPercent)
        stats.energyProjectileSpeedMult.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.projSpeedPercent)
        stats.ballisticWeaponFluxCostMod.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.weaponFluxPercent)
        stats.energyWeaponFluxCostMod.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.weaponFluxPercent)
        stats.beamWeaponFluxCostMult.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.weaponFluxPercent)
        stats.missileWeaponFluxCostMod.modifyPercent(ASTDArcProductionShipIds.STAT_ARC_ADVANCED_TARGETING_SYSTEM, values.weaponFluxPercent)

        if (!ship.hasListenerOfClass(ShortWeaponBaseRangeModifier::class.java)) {
            ship.addListener(ShortWeaponBaseRangeModifier())
        }
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        // tooltip 展示口径：无舰上下文（装配面板）按我方档位展示（默认砺刃 v2）
        val values = Values.resolve(ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.arc_advanced_targeting_system.desc")
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_0.label",
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_0.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.rangePercent / 100f)),
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_1.label",
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_1.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.projSpeedPercent / 100f)),
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_2.label",
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_2.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.weaponFluxPercent / 100f)),
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_3.label",
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_3.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_4.label",
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_4.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_5.label",
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_5.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_6.label",
                    "ui.hullmod.arc_advanced_targeting_system.table.main.row_6.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
            }
            para(
                "ui.hullmod.arc_advanced_targeting_system.line.short_range",
                v("threshold", values.shortRangeThreshold.toInt()),
                v("maxFlat", values.shortRangeMaxFlat.toInt()),
                hl(values.shortRangeThreshold.toInt().toString(), HullmodTone.DEFAULT),
                hl(values.shortRangeMaxFlat.toInt().toString(), HullmodTone.HIGHLIGHT),
            )
            heading("ui.hullmod.arc_advanced_targeting_system.section.limits")
            para(
                "ui.hullmod.arc_advanced_targeting_system.line.limit_targeting",
                hl("#", HullmodTone.RED), hl("目标定位系统", HullmodTone.RED)
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun affectsOPCosts(): Boolean = true

    override fun isApplicableToShip(ship: ShipAPI): Boolean =
        !hasIncompatibleTargetingSystem(ship)

    override fun getUnapplicableReason(ship: ShipAPI): String? {
        if (hasIncompatibleTargetingSystem(ship)) {
            return I18n[I18n.Categories.MOD, "ui.hullmod.arc_advanced_targeting_system.unapplicable.targeting"]
        }
        return null
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun hasIncompatibleTargetingSystem(ship: ShipAPI): Boolean {
        val variant = ship.variant ?: return false
        return INCOMPATIBLE_TARGETING_HULLMODS.any { variant.hasHullMod(it) }
    }

    private class WeaponOPCostReduction : WeaponOPCostModifier {
        override fun getWeaponOPCost(
            stats: MutableShipStatsAPI,
            weapon: com.fs.starfarer.api.loading.WeaponSpecAPI,
            currCost: Int
        ): Int = weaponOpCost(weapon.size, currCost)
    }

    private class ShortWeaponBaseRangeModifier : WeaponBaseRangeModifier {
        override fun getWeaponBaseRangePercentMod(ship: ShipAPI, weapon: WeaponAPI): Float = 0f

        override fun getWeaponBaseRangeMultMod(ship: ShipAPI, weapon: WeaponAPI): Float = 1f

        override fun getWeaponBaseRangeFlatMod(ship: ShipAPI, weapon: WeaponAPI): Float {
            if (!isEligibleWeapon(weapon)) return 0f
            val baseRange = weapon.spec?.maxRange ?: return 0f
            val values = Values.resolve(ship.owner == 0)
            return shortRangeFlatBonus(baseRange, values.shortRangeThreshold, values.shortRangeMaxFlat)
        }

        private fun isEligibleWeapon(weapon: WeaponAPI): Boolean {
            if (weapon.isDecorative) return false
            return when (weapon.type) {
                WeaponAPI.WeaponType.MISSILE,
                WeaponAPI.WeaponType.LAUNCH_BAY,
                WeaponAPI.WeaponType.DECORATIVE,
                WeaponAPI.WeaponType.SYSTEM,
                WeaponAPI.WeaponType.STATION_MODULE -> false

                else -> true
            }
        }
    }
}
