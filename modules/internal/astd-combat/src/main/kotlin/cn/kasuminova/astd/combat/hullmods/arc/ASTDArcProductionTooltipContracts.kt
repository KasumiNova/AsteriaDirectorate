package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.ui.dsl.HullmodTooltipSpec

/**
 * ARC 量产内置船插的 tooltip 契约绑定层。
 *
 * 把各量产内置船插 companion 中的 `TOOLTIP` 卡片声明绑定到对应 hullmod id，
 * 并按舰体分组（xc101/xc102/xc103），供 AutomationEvidence 静态枚举文本键
 * （消费方按 [Contract.textKeys] 核对文案解析，勿破坏枚举口径）。
 */
object ASTDArcProductionTooltipContracts {

    /**
     * 单个内置船插的 tooltip 契约：hullmod id 与卡片声明的绑定。
     *
     * @property hullmodId 契约对应的 hullmod id（用于核对舰体是否已挂载该船插）。
     * @property card 卡片内容声明（渲染源在各 hullmod 的 companion `TOOLTIP`）。
     */
    class Contract(
        val hullmodId: String,
        val card: HullmodTooltipSpec,
    ) {
        /** 卡片引用的全部 i18n 文本键（保持声明序去重）。 */
        val textKeys: Set<String> get() = card.textKeys
    }

    val arcAdvancedFireControl = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_FIRE_CONTROL,
        card = ASTDArcAdvancedFireControlHullMod.TOOLTIP,
    )

    val arcSharedTacticalNetwork = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_SHARED_TACTICAL_NETWORK,
        card = ASTDArcSharedTacticalNetworkHullMod.TOOLTIP,
    )

    /** 等离子装甲护盾：标题栏由原版描述承接（showTitle=false）。 */
    val plasmaArmorShield = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD,
        card = ASTDPlasmaArmorShieldHullMod.TOOLTIP,
    )

    /** 离子化反冲蓄能器：标题栏由原版描述承接（showTitle=false）。 */
    val ionizedRecoilAccumulator = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_IONIZED_RECOIL_ACCUMULATOR,
        card = ASTDIonizedRecoilAccumulatorHullMod.TOOLTIP,
    )

    val arcAdvancedTargetingSystem = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
        card = ASTDArcAdvancedTargetingSystemHullMod.TOOLTIP,
    )

    val xc102Contracts = listOf(arcAdvancedFireControl, arcSharedTacticalNetwork)
    val xc101Contracts = listOf(plasmaArmorShield, ionizedRecoilAccumulator)
    val xc103Contracts = listOf(arcAdvancedTargetingSystem)
}
