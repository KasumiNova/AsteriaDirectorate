package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier
import com.fs.starfarer.api.combat.listeners.WeaponOPCostModifier
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color
import kotlin.math.roundToInt

/**
 * 高级舰装集成（列星级 XC-103 内置 Hullmod，规格 blue/20-production.md §驱逐舰-独特 Hullmod）：
 * 目标定位系统与武器管线集成。
 *
 * 六件效果：
 * 1. 舰船武器射程 +20%（弹道/能量/光束三条射程乘区）；
 * 2. 实弹与能量武器射弹飞行速度 +20%；
 * 3. 实弹与能量武器辐能产出 -20%；
 * 4. 基础射程低于 [SHORT_RANGE_THRESHOLD] 的非导弹武器至多 +[SHORT_RANGE_MAX_FLAT] 基础射程
 *    （战斗内 WeaponBaseRangeModifier 监听器逐武器结算，see [shortRangeFlatBonus]）；
 * 5. 小型武器装配点 -1 再 -20%，中型武器 -2 再 -20%
 *    （WeaponOPCostModifier 监听器，装配结算唯一入口 [weaponOpCost]）；
 * 6. 不兼容任何其他目标定位系统——创建前后双路径五路清理（stripForbiddenHullMods），
 *    原版候选船插不受控无法灰掉，接受"装上即清理"行为（SKILL: hullmod-incompatibility-guidelines）。
 *
 * OP 减免与短射程补偿数学抽为纯函数（单元测试直接驱动）。
 */
class ASTDArcAdvancedTargetingSystemHullMod : BaseHullMod() {

    companion object {
        private const val RANGE_PERCENT = 20f
        private const val PROJECTILE_SPEED_PERCENT = 20f
        private const val WEAPON_FLUX_PERCENT = -20f
        private const val SHORT_RANGE_THRESHOLD = 700f
        private const val SHORT_RANGE_MAX_FLAT = 200f
        private const val SMALL_WEAPON_OP_FLAT_REDUCTION = 1
        private const val MEDIUM_WEAPON_OP_FLAT_REDUCTION = 2
        private const val OP_PERCENT_REDUCTION = 0.2f

        /** 禁装船插列表（集中定义）：一切其他目标定位系统。internal 供单测断言集中定义。 */
        internal val INCOMPATIBLE_TARGETING_HULLMODS = setOf(
            "targetingunit",
            "integratedtargetingunit",
            "dedicated_targeting_core",
            "dedicatedtargetingcore",
            "advancedcore",
            "advancedoptics",
            HullMods.DISTRIBUTED_FIRE_CONTROL,
        )

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(150, 232, 255),
            borderColor = Color(90, 180, 255),
            headerBackground = Color(20, 52, 82, 180),
            sectionBackground = Color(14, 36, 58, 120),
            accentColor = Color(60, 150, 230),
        )

        private val log = Global.getLogger(ASTDArcAdvancedTargetingSystemHullMod::class.java)

        /**
         * 短射程武器基础射程补偿（纯函数）：基础射程 < [SHORT_RANGE_THRESHOLD] 时补偿
         * min(阈值 - 基础射程, [SHORT_RANGE_MAX_FLAT])，其余 0。
         */
        internal fun shortRangeFlatBonus(baseRange: Float): Float {
            if (baseRange <= 0f || baseRange >= SHORT_RANGE_THRESHOLD) return 0f
            return (SHORT_RANGE_THRESHOLD - baseRange).coerceAtMost(SHORT_RANGE_MAX_FLAT)
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

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stripForbiddenHullMods(stats.variant)

        stats.ballisticWeaponRangeBonus.modifyPercent(id, RANGE_PERCENT)
        stats.energyWeaponRangeBonus.modifyPercent(id, RANGE_PERCENT)
        stats.beamWeaponRangeBonus.modifyPercent(id, RANGE_PERCENT)

        stats.ballisticProjectileSpeedMult.modifyPercent(id, PROJECTILE_SPEED_PERCENT)
        stats.energyProjectileSpeedMult.modifyPercent(id, PROJECTILE_SPEED_PERCENT)

        stats.ballisticWeaponFluxCostMod.modifyPercent(id, WEAPON_FLUX_PERCENT)
        stats.energyWeaponFluxCostMod.modifyPercent(id, WEAPON_FLUX_PERCENT)

        stats.addListener(WeaponOPCostReduction())
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        stripForbiddenHullMods(ship.variant)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (ship.isHulk) return
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
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = ASTDArcProductionTooltipContracts.arcAdvancedTargetingSystem.blocks,
        )
    }

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

    private fun stripForbiddenHullMods(variant: ShipVariantAPI?) {
        variant ?: return
        INCOMPATIBLE_TARGETING_HULLMODS.forEach { forbiddenId ->
            val installed = variant.hasHullMod(forbiddenId) ||
                    forbiddenId in variant.sMods ||
                    forbiddenId in variant.sModdedBuiltIns ||
                    forbiddenId in variant.suppressedMods
            if (installed) {
                log.info("astd_arc_advanced_targeting_system: 移除不兼容的目标定位系统船插 $forbiddenId（variant=${variant.hullVariantId}）")
            }
            variant.removeMod(forbiddenId)
            variant.removePermaMod(forbiddenId)
            variant.sMods.remove(forbiddenId)
            variant.sModdedBuiltIns.remove(forbiddenId)
            variant.removeSuppressedMod(forbiddenId)
        }
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
            return shortRangeFlatBonus(baseRange)
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
