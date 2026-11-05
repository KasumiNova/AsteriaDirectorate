package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.combat.hullmods.arc.ASTDAdvancedEnergyIntegrationHullMod
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAdvancedTargetingSystemHullMod
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod
import cn.kasuminova.astd.combat.hullmods.lens.AdvancedEcmNetworkTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HullmodIncompatibilityTest {

    @Test
    fun `控制方船插映射齐备`() {
        val arcTargeting = HullmodIncompatibility.forbiddenByController(
            ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
        )
        assertTrue("targetingunit" in arcTargeting, "列星高级舰装集成须禁装目标定位系统")

        val plasmaShield = HullmodIncompatibility.forbiddenByController(
            ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD,
        )
        assertTrue("hardenedshieldemitter" in plasmaShield, "熔壁等离子装甲护盾须禁装强化护盾")
        assertTrue("shield_shunt" in plasmaShield, "熔壁等离子装甲护盾须禁装护盾分流器")

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
    fun `原版目标定位系船插双向互斥`() {
        // 原版互斥只在候选方 isApplicableToShip 判定，赏金 SMod 管线程序化加装必须依赖本注册表双向拦截
        val bannedByAdvancedCore = HullmodIncompatibility.forbiddenCandidates(listOf("advancedcore"))
        assertTrue("targetingunit" in bannedByAdvancedCore, "内建先进目标定位核心须禁装目标定位系统")
        assertTrue("dedicated_targeting_core" in bannedByAdvancedCore, "内建先进目标定位核心须禁装专注型目标锁定核心")
        assertTrue("distributed_fire_control" in bannedByAdvancedCore, "内建先进目标定位核心须禁装分布式火控")

        val bannedByItu = HullmodIncompatibility.forbiddenCandidates(listOf("targetingunit"))
        assertTrue("advancedcore" in bannedByItu, "已装目标定位系统须禁装先进目标定位核心")
        assertTrue("dedicated_targeting_core" in bannedByItu, "已装目标定位系统须禁装专注型目标锁定核心")

        HullmodIncompatibility.TARGETING_FAMILY.forEach { id ->
            val banned = HullmodIncompatibility.forbiddenCandidates(listOf(id))
            HullmodIncompatibility.TARGETING_FAMILY.filter { it != id }.forEach { other ->
                assertTrue(other in banned, "目标定位系 $id 在场时 $other 应被禁装")
            }
        }
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
