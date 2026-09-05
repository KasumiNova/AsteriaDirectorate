package cn.kasuminova.astd.campaign.bounty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 主线定义表（[MainBounties]）与推进纯逻辑（[MainlineProgression]）验证：
 * 结构完整性（唯一键/gating 引用/无环）、文档数值口径（FP/危险等级/清算进度节拍/R 型解禁点）、
 * 以及一次完整通关 walkthrough（批次→章节→承包商等级→终局 gating 数据链）。
 */
class MainlineDefinitionTest {

    @Test
    fun `键唯一且结清组均有成员`() {
        val keys = MainBounties.defs.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "主线 key 不允许重复")
        for (group in MainBounties.groups) {
            val members = MainBounties.groupMembers[group.id]
            assertTrue(!members.isNullOrEmpty(), "结清组 ${group.id} 没有成员单")
        }
        // 组 id 必须注册在 groups 表
        for (def in MainBounties.defs) {
            assertNotNull(MainBounties.groupsById[def.groupId], "${def.key} 引用了未注册的结清组 ${def.groupId}")
        }
    }

    @Test
    fun `gating 引用完整且无环`() {
        val keys = MainBounties.defs.map { it.key }.toSet()
        for (def in MainBounties.defs) {
            for (mk in def.requiredMemKeys) {
                if (mk == BountyKeys.MEM_PROLOGUE_DOC_RECEIVED) continue
                assertTrue(mk.startsWith("\$"), "${def.key} 的 gating memKey 需带 $ 前缀：$mk")
                val ref = mk.removePrefix("\$")
                assertTrue(ref in keys, "${def.key} 的 gating 引用了不存在的主线单：$ref")
            }
        }
        // 拓扑排序验证无环且全部可达
        val done = LinkedHashSet<String>()
        var guard = 1000
        while (done.size < MainBounties.defs.size && guard-- > 0) {
            for (def in MainBounties.defs) {
                if (def.key in done) continue
                val depsOk = def.requiredMemKeys.all { mk ->
                    mk == BountyKeys.MEM_PROLOGUE_DOC_RECEIVED || mk.removePrefix("\$") in done
                }
                if (depsOk) done.add(def.key)
            }
        }
        assertEquals(MainBounties.defs.size, done.size, "gating 存在环或不可达节点")
    }

    @Test
    fun `章节结构与 FP 预设符合文档`() {
        fun fpOf(key: String) = MainBounties.defsByKey.getValue(key).baselineFP

        // 序章 80；第一章批次 120×2 / 200×3 / 300；第二章 XC 400/600/800、ZW 300×3+800；
        // 第三章 1000/1200/1500；第四章 1800/2200/2800
        assertEquals(80, fpOf("astd_main_prologue"))
        assertEquals(listOf(120, 120), listOf("astd_main_c1_b1_a", "astd_main_c1_b1_b").map(::fpOf))
        assertEquals(listOf(200, 200, 200), listOf("astd_main_c1_b2_a", "astd_main_c1_b2_b", "astd_main_c1_b2_c").map(::fpOf))
        assertEquals(300, fpOf("astd_main_c1_b3"))
        assertEquals(listOf(400, 600, 800), listOf("astd_main_c2_xc_1", "astd_main_c2_xc_2", "astd_main_c2_xc_3").map(::fpOf))
        assertEquals(listOf(300, 300, 300, 800), listOf("astd_main_c2_zw_s1", "astd_main_c2_zw_s2", "astd_main_c2_zw_s3", "astd_main_c2_zw_s4").map(::fpOf))
        assertEquals(listOf(1000, 1200, 1500), listOf("astd_main_c3_1", "astd_main_c3_2", "astd_main_c3_3").map(::fpOf))
        assertEquals(listOf(1800, 2200, 2800), listOf("astd_main_c4_s1", "astd_main_c4_s2", "astd_main_c4_s3").map(::fpOf))

        // 章节成员数：0=1 单，1=6 单，2=7 单（XC 3 + ZW 4），3=3 单，4=3 阶段
        assertEquals(1, MainBounties.chapterMembers.getValue(0).size)
        assertEquals(6, MainBounties.chapterMembers.getValue(1).size)
        assertEquals(7, MainBounties.chapterMembers.getValue(2).size)
        assertEquals(3, MainBounties.chapterMembers.getValue(3).size)
        assertEquals(3, MainBounties.chapterMembers.getValue(4).size)
    }

    @Test
    fun `清算序列进度节拍符合文档（含第三章反常回跳）`() {
        fun progressOf(key: String) = MainBounties.defsByKey.getValue(key).liquidationDisplay

        // 序章/第一章/第二章单不显示进度（首次显示在第二章末回执 97.3%）
        MainBounties.defs.filter { it.chapter <= 2 }.forEach {
            assertEquals(null, it.liquidationDisplay, "${it.key} 不应携带清算进度读数")
        }
        // 第三章：97.9 → 97.4（回跳）→ 98.8
        assertEquals(97.9f, progressOf("astd_main_c3_1")!!, 1e-4f)
        assertEquals(97.4f, progressOf("astd_main_c3_2")!!, 1e-4f)
        assertEquals(98.8f, progressOf("astd_main_c3_3")!!, 1e-4f)
        assertTrue(progressOf("astd_main_c3_2")!! < progressOf("astd_main_c3_1")!!, "第三章单 2 应为反常回跳")
        // 第四章：99.1 → 99.6 → 100.0
        assertEquals(99.1f, progressOf("astd_main_c4_s1")!!, 1e-4f)
        assertEquals(99.6f, progressOf("astd_main_c4_s2")!!, 1e-4f)
        assertEquals(100.0f, progressOf("astd_main_c4_s3")!!, 1e-4f)
    }

    @Test
    fun `词缀规则符合章节解禁口径`() {
        // 序章与批次一词缀不介入
        assertEquals(AffixRule.NONE, MainBounties.defsByKey.getValue("astd_main_prologue").affixRule)
        assertEquals(AffixRule.NONE, MainBounties.defsByKey.getValue("astd_main_c1_b1_a").affixRule)
        // R 型仅第三章单 3 与第四章开放
        for (def in MainBounties.defs) {
            val allowsR = def.affixRule.rMax > 0
            val expectR = def.key == "astd_main_c3_3" || def.chapter == 4
            assertEquals(expectR, allowsR, "${def.key} 的 R 型开放口径不符")
        }
        // 第三章单 3 固定至少 1 条 R；第四章阶段三打满（R=2）
        assertEquals(1, MainBounties.defsByKey.getValue("astd_main_c3_3").affixRule.rMin)
        assertEquals(2, MainBounties.defsByKey.getValue("astd_main_c4_s3").affixRule.rMin)
        assertEquals(2, MainBounties.defsByKey.getValue("astd_main_c4_s3").affixRule.rMax)
    }

    @Test
    fun `第四章等级从缺且其余章节危险等级在 1 到 6`() {
        for (def in MainBounties.defs) {
            if (def.chapter == 4) {
                assertTrue(def.dangerAbsent, "第四章工单危险等级栏应为「等级从缺」")
            } else {
                assertFalse(def.dangerAbsent)
                assertTrue(def.dangerLevel in 1..6, "${def.key} 危险等级越界：${def.dangerLevel}")
            }
        }
    }

    @Test
    fun `批次结清奖金基数符合文档`() {
        fun bonusOf(id: String) = MainBounties.groupsById.getValue(id).bonusBase
        assertEquals(300_000, bonusOf("c1_b1"))
        assertEquals(500_000, bonusOf("c1_b2"))
        assertEquals(750_000, bonusOf("c1_b3"))
        assertEquals(1_000_000, bonusOf("c2_xc"))
        assertEquals(1_000_000, bonusOf("c2_zw"))
        assertEquals(1_500_000, bonusOf("c3"))
        assertEquals(2_000_000, bonusOf("c4"))
    }

    @Test
    fun `完整通关 walkthrough：组与章节按序结清`() {
        val succeeded = LinkedHashSet<String>()
        val clearedGroups = LinkedHashSet<String>()
        val completedChapters = LinkedHashSet<Int>()

        // 按依赖拓扑顺序逐单结清
        val order = ArrayList<String>()
        var guard = 1000
        while (order.size < MainBounties.defs.size && guard-- > 0) {
            for (def in MainBounties.defs) {
                if (def.key in order) continue
                val depsOk = def.requiredMemKeys.all { mk ->
                    mk == BountyKeys.MEM_PROLOGUE_DOC_RECEIVED || mk.removePrefix("\$") in order
                }
                if (depsOk) order.add(def.key)
            }
        }

        val chapterClearOrder = ArrayList<Int>()
        for (key in order) {
            succeeded.add(key)
            val newGroups = MainlineProgression.newlyClearedGroups(succeeded, clearedGroups)
            newGroups.forEach { clearedGroups.add(it.id) }
            val newChapters = MainlineProgression.newlyClearedChapters(succeeded, completedChapters)
            chapterClearOrder.addAll(newChapters)
            completedChapters.addAll(newChapters)
        }

        assertEquals(MainBounties.groups.map { it.id }.toSet(), clearedGroups, "全部结清组应结清")
        assertEquals(setOf(0, 1, 2, 3, 4), completedChapters, "全部章节应结清")
        assertEquals(listOf(0, 1, 2, 3, 4), chapterClearOrder, "章节应按序结清")
    }

    @Test
    fun `批次制：批次二在批次一未结清前不解锁`() {
        val succeeded = linkedSetOf("astd_main_prologue", "astd_main_c1_b1_a")
        // 批次一还差一单，组不应结清
        assertTrue(MainlineProgression.newlyClearedGroups(succeeded, emptySet()).none { it.id == "c1_b1" })
        succeeded.add("astd_main_c1_b1_b")
        val cleared = MainlineProgression.newlyClearedGroups(succeeded, emptySet())
        assertTrue(cleared.any { it.id == "c1_b1" }, "批次一两单完成后批次一应结清")
    }

    @Test
    fun `第二章双线并行：单线结清不算章节结清`() {
        val succeeded = linkedSetOf(
            "astd_main_prologue",
            "astd_main_c1_b1_a", "astd_main_c1_b1_b",
            "astd_main_c1_b2_a", "astd_main_c1_b2_b", "astd_main_c1_b2_c",
            "astd_main_c1_b3",
            "astd_main_c2_xc_1", "astd_main_c2_xc_2", "astd_main_c2_xc_3",
        )
        // 星坠线结清但紫菀线未动：第一章应结清，第二章不应
        val chapters = MainlineProgression.newlyClearedChapters(succeeded, setOf(0))
        assertTrue(1 in chapters, "第一章应结清")
        assertFalse(2 in chapters, "紫菀线未结清时第二章不应结清")
        val groups = MainlineProgression.newlyClearedGroups(succeeded, setOf("prologue", "c1_b1", "c1_b2", "c1_b3"))
        assertTrue(groups.any { it.id == "c2_xc" }, "星坠线应结清")
        assertTrue(groups.none { it.id == "c2_zw" }, "紫菀线不应结清")
    }

    @Test
    fun `承包商等级映射：序章注册一级，章末递升至五级`() {
        assertEquals(1, MainlineProgression.contractorLevelAfterChapter(0))
        assertEquals(2, MainlineProgression.contractorLevelAfterChapter(1))
        assertEquals(3, MainlineProgression.contractorLevelAfterChapter(2))
        assertEquals(4, MainlineProgression.contractorLevelAfterChapter(3))
        assertEquals(5, MainlineProgression.contractorLevelAfterChapter(4))
    }
}
