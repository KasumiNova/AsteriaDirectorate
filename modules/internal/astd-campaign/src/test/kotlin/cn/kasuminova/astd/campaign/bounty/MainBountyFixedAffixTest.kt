package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.combat.affix.AffixRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 主线工单固定词缀表与文书追加条款的一致性（docs/story 08/10/12 定稿口径：
 * 条款编号 = 词缀编目号），以及固定表对互斥/相位约束的合法性。
 *
 * 对照关系：
 * - XC-c208-0216 条款①S-01 ②M-09；
 * - XC-c208-0217 条款①S-02 ②S-05 ③M-13；
 * - XC-c208-0221 条款①S-04 ②S-06 ③M-10（④为规模叙述，无词缀）；
 * - ZW-c208-0309 条款③S-03 + M-10（①②为引力锚定场编目外条目，无词缀），四阶段同表；
 * - ZX-c208-0002 条款①S-04 ②S-06 ③M-13 ④R-15；
 * - ZQ-c208-0001 条款①S-01+S-02 ②M-09+M-13，阶段三追加③R-16（编队级），中军旗舰 R-17。
 * 一章批二/批三与三章单 1/2 的条款未具名编目号（06/10 文档），保持随机抽取（fixedAffixIds = null）。
 */
class MainBountyFixedAffixTest {

    private fun def(key: String) = assertNotNull(MainBounties.byKey(key), key)

    private fun catalogNos(key: String, stageIndex: Int): List<String> =
        def(key).stages[stageIndex].fixedAffixIds!!.map { assertNotNull(AffixRegistry.getById(it), it).catalogNo }

    @Test
    fun `固定词缀表逐单对照文书条款编目号`() {
        assertEquals(listOf("S-01", "M-09"), catalogNos(MainBounties.KEY_XC_0216, 0))
        assertEquals(listOf("S-02", "S-05", "M-13"), catalogNos(MainBounties.KEY_XC_0217, 0))
        assertEquals(listOf("S-04", "S-06", "M-10"), catalogNos(MainBounties.KEY_XC_0221, 0))

        // ZW 四阶段同表（条款口径覆盖整支防御编队）
        repeat(4) { stage ->
            assertEquals(listOf("S-03", "M-10"), catalogNos(MainBounties.KEY_ZW_0309, stage), "ZW 阶段 $stage")
        }

        assertEquals(listOf("S-04", "S-06", "M-13", "R-15"), catalogNos(MainBounties.KEY_ZX_0002, 0))

        // ZQ：屏卫/火力线 ①②条款；中军追加 R-16 编队级 + 旗舰 R-17
        assertEquals(listOf("S-01", "S-02", "M-09", "M-13"), catalogNos(MainBounties.KEY_ZQ_0001, 0))
        assertEquals(listOf("S-01", "S-02", "M-09", "M-13"), catalogNos(MainBounties.KEY_ZQ_0001, 1))
        assertEquals(listOf("S-01", "S-02", "M-09", "M-13", "R-16"), catalogNos(MainBounties.KEY_ZQ_0001, 2))
        assertEquals(
            listOf("R-17"),
            def(MainBounties.KEY_ZQ_0001).stages[2].flagshipAffixIds.map {
                assertNotNull(AffixRegistry.getById(it), it).catalogNo
            },
            "中军旗舰固定 R-17 奇点驱动",
        )
    }

    @Test
    fun `未具名编目号的工单保持随机抽取`() {
        val randomKeys = listOf(
            MainBounties.KEY_PROLOGUE,
            MainBounties.KEY_YJ_1102, MainBounties.KEY_YJ_1103,
            MainBounties.KEY_YJ_1198, MainBounties.KEY_YJ_1201, MainBounties.KEY_YJ_1204,
            MainBounties.KEY_JJ_0007,
            MainBounties.KEY_ZX_1001, MainBounties.KEY_ZX_0344,
        )
        for (key in randomKeys) {
            def(key).stages.forEachIndexed { idx, stage ->
                assertNull(stage.fixedAffixIds, "$key 阶段 $idx 不应声明固定词缀表")
            }
        }
    }

    @Test
    fun `固定词缀表不违反互斥与相位约束`() {
        for (order in MainBounties.all) {
            order.stages.forEachIndexed { idx, stage ->
                val fixed = stage.fixedAffixIds ?: return@forEachIndexed
                val violations = AffixRegistry.validateFixedTable(fixed)
                assertTrue(violations.isEmpty(), "${order.key} 阶段 $idx 固定表违规：$violations")
                // R 型固定词缀仅出现在显式开放 R 的工单
                val hasR = fixed.any { assertNotNull(AffixRegistry.getById(it)).type == AffixRegistry.AffixType.R }
                if (hasR) {
                    assertTrue(order.allowRAffixes, "${order.key} 固定表含 R 型但未开放 allowRAffixes")
                }
            }
        }
    }

    @Test
    fun `toBountyDef 透传当前阶段固定词缀表`() {
        val zq = def(MainBounties.KEY_ZQ_0001)
        assertEquals(zq.stages[0].fixedAffixIds, zq.toBountyDef(0).fixedAffixIds)
        assertEquals(zq.stages[2].fixedAffixIds, zq.toBountyDef(2).fixedAffixIds)
        assertNull(def(MainBounties.KEY_JJ_0007).toBountyDef(0).fixedAffixIds)
    }
}
