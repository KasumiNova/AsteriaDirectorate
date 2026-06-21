package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeConfig
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherIds
import cn.kasuminova.astd.combat.hullmods.base.hasASTDDualModeAutomated
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * 透镜阵列核心双模式状态机：无人/载人通过 PermaMod + next marker 切换。
 *
 * 本文件已由「lens 自造一套状态机」收敛为「转调 Task 2/3 的通用双模式框架」
 * （[cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeConfig] 及其扩展函数）。
 * lens 仅在此声明自己的 [LENS_DUAL_MODE_CONFIG]（一份 id 集合）并注册到
 * [ASTDDualModeRegistry]，状态机逻辑全部复用通用实现，避免与 arc 漂移。
 */

/**
 * 透镜阵列核心（gravitational_lens）的双模式配置。
 *
 * 动机：lens 与 arc 共用「拆切换器即轮换模式」交互。此前 lens 自造了独立状态机 +
 * 独立切换器（astd_lens_mode_switcher / ASTDLensDualModeSwitcherHullMod），与通用框架重复；
 * 且 lens 的 mode hullmod 缺「拆即切」逻辑导致玩家拆下切换器后无法切到对面模式。
 * 现改为复用通用切换器 [ASTDDualModeSwitcherIds.SWITCHER_ID] + lens 自己的 mode/next/system id。
 *
 * 字段对应关系见 [ASTDDualModeConfig] 各成员注释。
 */
val LENS_DUAL_MODE_CONFIG = ASTDDualModeConfig(
    switcherId = ASTDDualModeSwitcherIds.SWITCHER_ID,
    crewedModeId = LensArrayCoreHullModIds.MODE_CREWED,
    automatedModeId = LensArrayCoreHullModIds.MODE_AUTOMATED,
    nextCrewedMarker = LensArrayCoreHullModIds.NEXT_CREWED,
    nextAutomatedMarker = LensArrayCoreHullModIds.NEXT_AUTOMATED,
    crewedSystemId = LensArrayCoreHullModIds.SYSTEM_CREWED,
    automatedSystemId = LensArrayCoreHullModIds.SYSTEM_AUTOMATED,
)

/**
 * lens 双模式配置注册入口。
 *
 * 注册时机决策：通用切换器 [cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherHullMod]
 * 的 tooltip 在 refit 选择器里可独立于任何 mode hullmod 被渲染（它需 [ASTDDualModeRegistry.configForShip]
 * 反查 lens config 才能显示「当前/目标模式」）。若仅靠「mode hullmod 引用 [LENS_DUAL_MODE_CONFIG]
 * 时触发 object 初始化」来注册，无法保证切换器 tooltip 首次渲染前 config 已注册。
 * 故由 [AsteriaDirectoratePlugin.onApplicationLoad] 在应用加载阶段显式调用本函数注册，
 * 保证任何 refit / 战役逻辑用到 config 时它已就绪。本函数幂等（register 覆盖同 key），允许多次调用。
 */
fun registerLensDualModeConfig() {
    ASTDDualModeRegistry.register(LensArrayCoreHullModIds.HULL_ID, LENS_DUAL_MODE_CONFIG)
}

/**
 * 判断 variant 当前是否处于无人模式（转调通用 [hasASTDDualModeAutomated]）。
 * 供战斗插件 / 核心 hullmod 判定模式分支用。
 */
internal fun ShipVariantAPI.hasLensAutomatedMode(): Boolean =
    hasASTDDualModeAutomated(LENS_DUAL_MODE_CONFIG)
