package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeConfig
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherIds
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * arc_flare 双模式（载人/无人）id 集合与通用框架接入。
 *
 * 本文件已由「arc 自有状态机」收敛为「转调 Task 2/3 的通用双模式框架」
 * （[cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeConfig] 及其扩展函数）。
 * arc 仅在此声明自己的 [ARC_FLARE_DUAL_MODE_CONFIG]（一份 id 集合）并注册到
 * [ASTDDualModeRegistry]，状态机逻辑（ensureASTDDualModeState / activateDualMode /
 * hasASTDDualModeAutomated）全部复用通用实现，避免与 lens 漂移。
 *
 * 说明：arc 原本各自持有一份 isASTDShip / isASTDShipVariant，现已删除，统一改用
 * base 包同名扩展（见 [cn.kasuminova.astd.combat.hullmods.base.isASTDShip]）。
 * arc 专属的 [isASTDArcFlareVariant] / [isASTDArcFlareShip]（仅判定 arc_flare 这一具体 hull）保留。
 */
internal object ASTDArcFlareHullModIds {
    const val HULL_ID: String = "astd_arc_flare"

    const val MODE_CREWED: String = "astd_arc_flare_mode_crewed"
    const val MODE_AUTOMATED: String = "astd_arc_flare_mode_automated"
    const val NEXT_CREWED: String = "astd_arc_flare_mode_next_crewed"
    const val NEXT_AUTOMATED: String = "astd_arc_flare_mode_next_automated"

    /** 载人版「电弧过载」系统 id（载人 mode hullmod 激活时 setShipSystemId）。 */
    const val SYSTEM_CREWED: String = "astd_arc_flare_overdrive_crewed"

    /** 无人版「电弧过载」系统 id（无人 mode hullmod 激活时 setShipSystemId）。 */
    const val SYSTEM_AUTOMATED: String = "astd_arc_flare_overdrive_automated"
}

/**
 * arc_flare 的双模式配置。
 *
 * 动机：arc 与 lens 共用「拆切换器即轮换模式」交互。此前 arc 自造了独立状态机 +
 * 独立切换器（astd_arc_flare_mode_switcher / ASTDArcFlareDualModeSwitcherHullMod），与通用框架重复。
 * 现改为复用通用切换器 [ASTDDualModeSwitcherIds.SWITCHER_ID] + arc 自己的 mode/next/system id，
 * 行为与原 arc 状态机逐字段对应（见各字段），实现零回归迁移。
 *
 * 字段对应关系见 [ASTDDualModeConfig] 各成员注释。
 */
val ARC_FLARE_DUAL_MODE_CONFIG = ASTDDualModeConfig(
    switcherId = ASTDDualModeSwitcherIds.SWITCHER_ID,
    crewedModeId = ASTDArcFlareHullModIds.MODE_CREWED,
    automatedModeId = ASTDArcFlareHullModIds.MODE_AUTOMATED,
    nextCrewedMarker = ASTDArcFlareHullModIds.NEXT_CREWED,
    nextAutomatedMarker = ASTDArcFlareHullModIds.NEXT_AUTOMATED,
    crewedSystemId = ASTDArcFlareHullModIds.SYSTEM_CREWED,
    automatedSystemId = ASTDArcFlareHullModIds.SYSTEM_AUTOMATED,
)

/**
 * arc 双模式配置注册入口。
 *
 * 注册时机决策：通用切换器 [cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherHullMod]
 * 的 tooltip 在 refit 选择器里可独立于任何 mode hullmod 被渲染（它需 [ASTDDualModeRegistry.configForShip]
 * 反查 arc config 才能显示「当前/目标模式」）。故由 [AsteriaDirectoratePlugin.onApplicationLoad] 在应用加载
 * 阶段显式调用本函数注册（与 lens 并列），保证任何 refit / 战役逻辑用到 config 时它已就绪。
 * 本函数幂等（register 覆盖同 key），允许多次调用。
 */
fun registerArcFlareDualModeConfig() {
    ASTDDualModeRegistry.register(ASTDArcFlareHullModIds.HULL_ID, ARC_FLARE_DUAL_MODE_CONFIG)
}

internal fun ShipVariantAPI?.isASTDArcFlareVariant(): Boolean {
    val variant = this ?: return false
    val hullId = try {
        variant.hullSpec?.hullId
    } catch (_: Throwable) {
        null
    }
    val baseHullId = try {
        variant.hullSpec?.baseHullId
    } catch (_: Throwable) {
        null
    }
    return hullId == ASTDArcFlareHullModIds.HULL_ID || baseHullId == ASTDArcFlareHullModIds.HULL_ID
}

internal fun ShipAPI?.isASTDArcFlareShip(): Boolean {
    val ship = this ?: return false
    val hullId = try {
        ship.hullSpec?.hullId
    } catch (_: Throwable) {
        null
    }
    val baseHullId = try {
        ship.hullSpec?.baseHullId
    } catch (_: Throwable) {
        null
    }
    return hullId == ASTDArcFlareHullModIds.HULL_ID || baseHullId == ASTDArcFlareHullModIds.HULL_ID
}
