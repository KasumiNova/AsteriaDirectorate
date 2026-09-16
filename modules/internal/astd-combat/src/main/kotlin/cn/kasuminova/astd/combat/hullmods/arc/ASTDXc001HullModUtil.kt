package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeConfig
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherIds
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * xc_001 双模式（载人/无人）id 集合与通用框架接入。
 *
 * 本文件已由「arc 自有状态机」收敛为「转调 Task 2/3 的通用双模式框架」
 * （[cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeConfig] 及其扩展函数）。
 * arc 仅在此声明自己的 [XC_001_DUAL_MODE_CONFIG]（一份 id 集合）并注册到
 * [ASTDDualModeRegistry]，状态机逻辑（ensureASTDDualModeState / activateDualMode /
 * hasASTDDualModeAutomated）全部复用通用实现，避免与 lens 漂移。
 *
 * 说明：arc 原本各自持有一份 isASTDShip / isASTDShipVariant，现已删除，统一改用
 * base 包同名扩展（见 [cn.kasuminova.astd.combat.hullmods.base.isASTDShip]）。
 * arc 专属的 [isASTDXc001Variant] / [isASTDXc001Ship]（仅判定 xc_001 这一具体 hull）保留。
 */
internal object ASTDXc001HullModIds {
    const val HULL_ID: String = "astd_xc_001"

    const val MODE_CREWED: String = "astd_xc_001_mode_crewed"
    const val MODE_AUTOMATED: String = "astd_xc_001_mode_automated"
    const val NEXT_CREWED: String = "astd_xc_001_mode_next_crewed"
    const val NEXT_AUTOMATED: String = "astd_xc_001_mode_next_automated"

    /** 载人版「电弧过载」系统 id（载人 mode hullmod 激活时 setShipSystemId）。 */
    const val SYSTEM_CREWED: String = "astd_xc_001_overdrive_crewed"

    /** 无人版「电弧过载」系统 id（无人 mode hullmod 激活时 setShipSystemId）。 */
    const val SYSTEM_AUTOMATED: String = "astd_xc_001_overdrive_automated"

    /** 舰体渲染插件的 combat engine key，由各 effect/manager 共用，避免各自持有字面量。 */
    const val KEY_AFTERIMAGE_RENDERER: String = "astd_xc_001_afterimage_renderer"
    const val KEY_EMISSIVE_OVERLAY_MANAGER: String = "astd_xc_001_emissive_overlay_manager"
    const val KEY_ENGINE_FLARE_MANAGER: String = "astd_xc_001_engine_flare_manager"

    /** 静态装饰灯 bloom 描边武器 id（[cn.kasuminova.astd.renderer.effect.system.ASTDDecorativeLightsEffect] 识别 bloom 层用）。 */
    const val WEAPON_LIGHTS_BLOOM: String = "astd_xc_001_lights_bloom"
}

/**
 * xc_001 的双模式配置。
 *
 * 动机：arc 与 lens 共用「拆切换器即轮换模式」交互。此前 arc 自造了独立状态机 +
 * 独立切换器（astd_xc_001_mode_switcher / ASTDXc001DualModeSwitcherHullMod，均已废弃移除），与通用框架重复。
 * 现改为复用通用切换器 [ASTDDualModeSwitcherIds.SWITCHER_ID] + arc 自己的 mode/next/system id，
 * 行为与原 arc 状态机逐字段对应（见各字段），实现零回归迁移。
 *
 * 字段对应关系见 [ASTDDualModeConfig] 各成员注释。
 */
val XC_001_DUAL_MODE_CONFIG = ASTDDualModeConfig(
    switcherId = ASTDDualModeSwitcherIds.SWITCHER_ID,
    crewedModeId = ASTDXc001HullModIds.MODE_CREWED,
    automatedModeId = ASTDXc001HullModIds.MODE_AUTOMATED,
    nextCrewedMarker = ASTDXc001HullModIds.NEXT_CREWED,
    nextAutomatedMarker = ASTDXc001HullModIds.NEXT_AUTOMATED,
    crewedSystemId = ASTDXc001HullModIds.SYSTEM_CREWED,
    automatedSystemId = ASTDXc001HullModIds.SYSTEM_AUTOMATED,
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
fun registerXc001DualModeConfig() {
    ASTDDualModeRegistry.register(ASTDXc001HullModIds.HULL_ID, XC_001_DUAL_MODE_CONFIG)
}

internal fun ShipVariantAPI?.isASTDXc001Variant(): Boolean {
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
    return hullId == ASTDXc001HullModIds.HULL_ID || baseHullId == ASTDXc001HullModIds.HULL_ID
}

internal fun ShipAPI?.isASTDXc001Ship(): Boolean {
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
    return hullId == ASTDXc001HullModIds.HULL_ID || baseHullId == ASTDXc001HullModIds.HULL_ID
}
