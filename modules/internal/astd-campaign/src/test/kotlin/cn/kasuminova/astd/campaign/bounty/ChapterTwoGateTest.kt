package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.story.StorySites
import cn.kasuminova.astd.campaign.story.StoryTerminalMapping
import cn.kasuminova.astd.campaign.ui.WorkOrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第二章解锁门槛与合并单口径验证：
 * 双线入口统一挂在第一章加急件交付、内部阶段顺序门槛、遗址站点门槛链与赏金 memKey 链对齐、
 * 终端可见性随门槛开闭、合并单各阶段共享同一文书编号。
 */
class ChapterTwoGateTest {

    private fun memKeyOf(key: String) = "\$$key"

    private fun def(key: String) = MainBounties.defsByKey.getValue(key)

    @Test
    fun `第二章双线入口统一挂在第一章加急件交付`() {
        for (key in listOf(StorySites.KEY_C2_XC_1, StorySites.KEY_C2_ZW_S1)) {
            assertEquals(
                listOf(memKeyOf("astd_main_c1_b3")),
                def(key).requiredMemKeys,
                "$key 的出现门槛应恰为第一章加急件交付",
            )
        }
    }

    @Test
    fun `第二章内部阶段顺序门槛不直接依赖第一章`() {
        val chain = listOf(
            "astd_main_c2_xc_2" to "astd_main_c2_xc_1",
            "astd_main_c2_xc_3" to "astd_main_c2_xc_2",
            "astd_main_c2_zw_s2" to "astd_main_c2_zw_s1",
            "astd_main_c2_zw_s3" to "astd_main_c2_zw_s2",
            "astd_main_c2_zw_s4" to "astd_main_c2_zw_s3",
        )
        for ((key, prev) in chain) {
            assertEquals(
                listOf(memKeyOf(prev)),
                def(key).requiredMemKeys,
                "$key 应仅挂紧邻前序单的交付门槛",
            )
        }
    }

    @Test
    fun `遗址站点门槛链与赏金 memKey 链对齐`() {
        val zw = listOf(
            StorySites.KEY_C2_ZW_S1,
            StorySites.KEY_C2_ZW_S2,
            StorySites.KEY_C2_ZW_S3,
            StorySites.KEY_C2_ZW_S4,
        )
        assertTrue(
            StorySites.sitesByBountyKey.getValue(zw[0]).gateKeys.isEmpty(),
            "节点一不应有前序门槛",
        )
        for (idx in 1..3) {
            val site = StorySites.sitesByBountyKey.getValue(zw[idx])
            assertEquals(
                zw.take(idx),
                site.gateKeys,
                "${zw[idx]} 的交互门槛应覆盖全部前序阶段（节点依序破除/核心须全部破除）",
            )
            // 赏金出现门槛只挂紧邻前序：交互门槛链（不可强闯）由站点侧补齐
            assertEquals(
                listOf(memKeyOf(zw[idx - 1])),
                def(zw[idx]).requiredMemKeys,
                "${zw[idx]} 的赏金门槛与站点门槛链末端不一致",
            )
        }
    }

    @Test
    fun `终端可见性：第一章未结清时双线不挂出，加急件交付后双线同时挂出`() {
        val chapterOneDelivered = setOf(
            memKeyOf(MainBounties.KEY_PROLOGUE),
            memKeyOf("astd_main_c1_b1_a"), memKeyOf("astd_main_c1_b1_b"),
            memKeyOf("astd_main_c1_b2_a"), memKeyOf("astd_main_c1_b2_b"), memKeyOf("astd_main_c1_b2_c"),
        )
        // 批次三（加急件）未交付：双线首单均不挂出
        for (key in listOf(StorySites.KEY_C2_XC_1, StorySites.KEY_C2_ZW_S1)) {
            assertNull(
                StoryTerminalMapping.visibleStatus(def(key), emptySet(), emptySet()) { it in chapterOneDelivered },
                "$key 在第一章未结清时不应挂出",
            )
        }
        // 加急件交付：双线首单同时挂出（两线并行）
        val chapterOneDone = chapterOneDelivered + memKeyOf("astd_main_c1_b3")
        for (key in listOf(StorySites.KEY_C2_XC_1, StorySites.KEY_C2_ZW_S1)) {
            assertEquals(
                WorkOrderStatus.AVAILABLE,
                StoryTerminalMapping.visibleStatus(def(key), emptySet(), emptySet()) { it in chapterOneDone },
                "$key 在第一章结清后应可接取",
            )
        }
        // 星坠线第二单仍需 xc_1 交付，不随第一章结清提前挂出
        assertNull(
            StoryTerminalMapping.visibleStatus(def("astd_main_c2_xc_2"), emptySet(), emptySet()) { it in chapterOneDone },
        )
    }

    @Test
    fun `合并工单口径：紫菀四阶段与第四章三阶段各自共享同一文书编号`() {
        val zw = MainBounties.groupMembers.getValue("c2_zw").map(::def)
        assertEquals(4, zw.size)
        assertEquals(
            listOf("ZW-c208-0309／回收-02〔封存〕"),
            zw.map { it.code }.distinct(),
            "紫菀合并单拆阶段应沿用同一张工单号",
        )

        val c4 = MainBounties.groupMembers.getValue("c4").map(::def)
        assertEquals(3, c4.size)
        assertEquals(
            listOf("ZQ-c208-0001／清除-00"),
            c4.map { it.code }.distinct(),
            "第四章 ZQ 工单拆阶段应沿用同一张工单号",
        )

        // 除两处合并单共享外，其余工单号两两不同；两组共享号亦不相同
        val distinct = MainBounties.defs.map { it.code }.distinct()
        assertEquals(MainBounties.defs.size - (zw.size - 1) - (c4.size - 1), distinct.size, "文书编号出现意外撞号")
    }
}
