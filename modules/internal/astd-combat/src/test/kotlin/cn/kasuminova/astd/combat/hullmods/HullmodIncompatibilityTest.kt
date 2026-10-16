package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.combat.hullmods.arc.ASTDAdvancedEnergyIntegrationHullMod
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAdvancedTargetingSystemHullMod
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod
import cn.kasuminova.astd.combat.hullmods.lens.AdvancedEcmNetworkTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 船插互斥注册表校验：五路控制方映射、候选并集解析，
 * 以及控制方船插的 strip 列表与注册表同源（集中定义回归防线）。
 */
class HullmodIncompatibilityTest {

    @Test
    fun `五路控制方船插映射齐备`() {
        val arcTargeting = HullmodIncompatibility.forbiddenByController(
            ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
        )
        assertTrue("targetingunit" in arcTargeting, "列星高级舰装集成须禁装目标定位系统")

        val plasmaShield = HullmodIncompatibility.forbiddenByController(
            ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD,
        )
        assertTrue("hardenedshieldemitter" in plasmaShield, "熔壁等离子装甲护盾须禁装强化护盾")
        assertTrue("shield_shunt" in plasmaShield, "熔壁等离子装甲护盾须禁装护盾分流器")

        // 奇点稳定器蓝/紫双变体登记同一禁装集合
        for (stabilizerId in listOf(ASTDSingularityStabilizerHullMod.ID_ARC, ASTDSingularityStabilizerHullMod.ID_LENS)) {
            val singularity = HullmodIncompatibility.forbiddenByController(stabilizerId)
            assertTrue("safetyoverrides" in singularity, "奇点稳定器（$stabilizerId）须禁装安全协议超驰")
        }

        val energyIntegration = HullmodIncompatibility.forbiddenByController(
            ASTDAdvancedEnergyIntegrationHullMod.MOD_ID,
        )
        assertTrue("armoredweapons" in energyIntegration, "先进能量集成须禁装炮塔装甲")
        assertTrue("turretgyros" in energyIntegration, "先进能量集成须禁装高级炮塔陀螺仪")

        val ecmNetwork = HullmodIncompatibility.forbiddenByController(
            AdvancedEcmNetworkTuning.HULLMOD_ID,
        )
        assertTrue("ecm" in ecmNetwork, "先进电子对抗网络须禁装电子对抗组件")
        assertTrue("eccm" in ecmNetwork, "先进电子对抗网络须禁装电子反对抗组件")

        assertTrue(HullmodIncompatibility.forbiddenByController("unknown_hullmod").isEmpty())
    }

    @Test
    fun `禁装候选并集覆盖全部在场控制方且忽略未登记船插`() {
        val banned = HullmodIncompatibility.forbiddenCandidates(
            listOf(
                ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
                ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD,
                "unrelated_hullmod",
            ),
        )
        assertTrue("targetingunit" in banned)
        assertTrue("hardenedshieldemitter" in banned)
        assertTrue("safetyoverrides" !in banned, "奇点稳定器不在场时安全协议超驰不应被禁")
        assertEquals(
            HullmodIncompatibility.forbiddenByController(ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM).size +
                HullmodIncompatibility.forbiddenByController(ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD).size,
            banned.size,
        )
    }

    @Test
    fun `控制方船插的清理列表与注册表同源`() {
        assertEquals(
            HullmodIncompatibility.forbiddenByController(ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM),
            ASTDArcAdvancedTargetingSystemHullMod.INCOMPATIBLE_TARGETING_HULLMODS,
            "高级舰装集成的 strip 列表必须以注册表为真相来源",
        )
    }
}
