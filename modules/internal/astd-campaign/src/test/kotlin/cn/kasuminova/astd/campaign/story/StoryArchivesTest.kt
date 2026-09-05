package cn.kasuminova.astd.campaign.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 档案室解锁节奏（[StoryArchives]）验证：
 * 按主线结清进度逐章 walkthrough，核对每层每份档案的解锁时点与最终全解锁。
 */
class StoryArchivesTest {

    private fun unlocked(
        succeeded: Set<String> = emptySet(),
        clearedGroups: Set<String> = emptySet(),
        completedChapters: Set<Int> = emptySet(),
    ): Set<String> = StoryArchives.defs
        .filter { StoryArchives.isUnlocked(it.id, succeeded, clearedGroups, completedChapters) }
        .map { it.id }
        .toSet()

    @Test
    fun `档案表结构完整：id 唯一且层号在 1 到 5`() {
        val ids = StoryArchives.defs.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "档案 id 不允许重复")
        assertEquals(20, StoryArchives.defs.size, "档案总数应为 20（7+6+3+3+1）")
        for (def in StoryArchives.defs) {
            assertTrue(def.layer in 1..5, "${def.id} 层号越界：${def.layer}")
            assertTrue(def.paragraphs >= 1, "${def.id} 至少一段正文")
        }
    }

    @Test
    fun `层开放口径：序章结清开第一层，此后每章开一层`() {
        assertFalse(StoryArchives.isLayerOpen(1, emptySet()))
        assertTrue(StoryArchives.isLayerOpen(1, setOf(0)))
        assertTrue(StoryArchives.isLayerOpen(2, setOf(0, 1)))
        assertTrue(StoryArchives.isLayerOpen(5, setOf(0, 1, 2, 3, 4)))
        assertFalse(StoryArchives.isLayerOpen(5, setOf(0, 1, 2, 3)), "清算令原件须第四章结清后开放")
        assertFalse(StoryArchives.isLayerOpen(0, setOf(0)))
        assertFalse(StoryArchives.isLayerOpen(6, setOf(0, 1, 2, 3, 4)))
    }

    @Test
    fun `开局时无任何档案解锁`() {
        assertEquals(emptySet(), unlocked())
    }

    @Test
    fun `第一层随第一章批次逐份解锁`() {
        // 序章结清：只开章程
        assertEquals(setOf("l1_charter"), unlocked(completedChapters = setOf(0)))

        val afterB1 = unlocked(clearedGroups = setOf("c1_b1"), completedChapters = setOf(0))
        assertEquals(setOf("l1_charter", "l1_division_memo"), afterB1)

        val afterB2 = unlocked(clearedGroups = setOf("c1_b1", "c1_b2"), completedChapters = setOf(0))
        assertEquals(
            setOf("l1_charter", "l1_division_memo", "l1_tritach_report", "l1_audit_203", "l1_coordinator_rules"),
            afterB2,
        )

        val afterB3 = unlocked(clearedGroups = setOf("c1_b1", "c1_b2", "c1_b3"), completedChapters = setOf(0, 1))
        assertEquals(7, afterB3.size, "批次三结清后第一层 7 份应全开")
    }

    @Test
    fun `第二层两线交替解锁，残页须双线结清`() {
        val xc1 = unlocked(succeeded = setOf("astd_main_c2_xc_1"))
        assertEquals(setOf("l2_undelivered_catalog"), xc1)

        val zwOnly = unlocked(succeeded = setOf("astd_main_c2_zw_s2"))
        assertEquals(setOf("l2_admin_core_whitepaper"), zwOnly)

        val bothLinesDone = unlocked(
            succeeded = setOf(
                "astd_main_c2_xc_1", "astd_main_c2_xc_2", "astd_main_c2_xc_3",
                "astd_main_c2_zw_s1", "astd_main_c2_zw_s2", "astd_main_c2_zw_s3", "astd_main_c2_zw_s4",
            ),
            clearedGroups = setOf("c2_xc", "c2_zw"),
        )
        assertEquals(6, bothLinesDone.size, "双线结清后第二层 6 份应全开")

        // 只结清单线时残页不开放
        val xcCleared = unlocked(
            succeeded = setOf("astd_main_c2_xc_1", "astd_main_c2_xc_2", "astd_main_c2_xc_3", "astd_main_c2_zw_s2"),
            clearedGroups = setOf("c2_xc"),
        )
        assertFalse("l2_joint_memo_fragment" in xcCleared, "紫菀线未结清时残页不应开放")
    }

    @Test
    fun `第三四层随单逐份解锁`() {
        assertEquals(setOf("l3_seal_order_excerpt"), unlocked(succeeded = setOf("astd_main_c3_1")))
        assertEquals(setOf("l3_target_registry"), unlocked(succeeded = setOf("astd_main_c3_2")))
        assertEquals(setOf("l3_review_cover_page"), unlocked(succeeded = setOf("astd_main_c3_3")))

        assertEquals(setOf("l4_review_full_text"), unlocked(succeeded = setOf("astd_main_c4_s1")))
        assertEquals(setOf("l4_vote_record"), unlocked(succeeded = setOf("astd_main_c4_s2")))
        assertEquals(setOf("l4_decommission_summary"), unlocked(succeeded = setOf("astd_main_c4_s3")))
    }

    @Test
    fun `第五层仅第四章结清后开放`() {
        assertFalse("l5_liquidation_order" in unlocked(completedChapters = setOf(0, 1, 2, 3)))
        assertTrue("l5_liquidation_order" in unlocked(completedChapters = setOf(0, 1, 2, 3, 4)))
    }

    @Test
    fun `完整通关后全部 20 份档案解锁`() {
        val all = unlocked(
            succeeded = setOf(
                "astd_main_c2_xc_1", "astd_main_c2_xc_2", "astd_main_c2_xc_3",
                "astd_main_c2_zw_s2", "astd_main_c2_zw_s4",
                "astd_main_c3_1", "astd_main_c3_2", "astd_main_c3_3",
                "astd_main_c4_s1", "astd_main_c4_s2", "astd_main_c4_s3",
            ),
            clearedGroups = setOf("c1_b1", "c1_b2", "c1_b3", "c2_xc", "c2_zw"),
            completedChapters = setOf(0, 1, 2, 3, 4),
        )
        assertEquals(StoryArchives.defs.map { it.id }.toSet(), all)
    }
}
