package cn.kasuminova.astd.campaign.story

/**
 * 档案室条目注册表（纯数据 + 纯解锁判定，与游戏环境解耦，供单元测试直接驱动）。
 *
 * - 层/定题口径见 docs/story 05（第一层 7 份）、07（第二层 6 份）、09（第三层 3 份）、
 *   11（第四层 3 份 + 最终档案清算令原件，单列第五层）；
 * - 标题与正文段落在 `bounty_strings.json`（键名约定 `archive.<id>.title` / `archive.<id>.body.N`）；
 * - 解锁状态由主线结清进度推导（[isUnlocked]），不落盘，读档自然恢复。
 */
object StoryArchives {

    /**
     * 一份档案的静态定义。
     *
     * @property id 档案 id（i18n 键 `archive.<id>.*`）
     * @property layer 所属层（终端按层分组展示）
     * @property paragraphs 正文段落数（`archive.<id>.body.1..N`）
     */
    data class ArchiveDef(val id: String, val layer: Int, val paragraphs: Int)

    val defs: List<ArchiveDef> = listOf(
        // ── 第一层（05 文档，7 份）────────────────────────────────────
        ArchiveDef("l1_charter", 1, 2),
        ArchiveDef("l1_division_memo", 1, 2),
        ArchiveDef("l1_tritach_report", 1, 2),
        ArchiveDef("l1_audit_203", 1, 2),
        ArchiveDef("l1_coordinator_rules", 1, 2),
        ArchiveDef("l1_battlegroup_index", 1, 2),
        ArchiveDef("l1_contractor_rules", 1, 2),
        // ── 第二层（07 文档，6 份）────────────────────────────────────
        ArchiveDef("l2_undelivered_catalog", 2, 2),
        ArchiveDef("l2_mothball_fleet_list", 2, 2),
        ArchiveDef("l2_watch_log_last_page", 2, 2),
        ArchiveDef("l2_admin_core_whitepaper", 2, 2),
        ArchiveDef("l2_ethics_review", 2, 2),
        ArchiveDef("l2_joint_memo_fragment", 2, 1),
        // ── 第三层（09 文档，3 份）────────────────────────────────────
        ArchiveDef("l3_seal_order_excerpt", 3, 2),
        ArchiveDef("l3_target_registry", 3, 2),
        ArchiveDef("l3_review_cover_page", 3, 2),
        // ── 第四层（11 文档，3 份）────────────────────────────────────
        ArchiveDef("l4_review_full_text", 4, 2),
        ArchiveDef("l4_vote_record", 4, 2),
        ArchiveDef("l4_decommission_summary", 4, 2),
        // ── 第五层（11 文档，最终档案：清算令原件）─────────────────────
        ArchiveDef("l5_liquidation_order", 5, 3),
    )

    val defsById: Map<String, ArchiveDef> = defs.associateBy { it.id }

    /**
     * 层是否对玩家开放（整层开放后，层内条目再按 [isUnlocked] 逐份解锁）。
     *
     * 口径：序章结清开放第一层（chapter.0 回执「档案室（第一层）已对你开放」），
     * 此后每结清一章开放下一层；第五层（清算令原件）随第四章结清开放。
     */
    fun isLayerOpen(layer: Int, completedChapters: Set<Int>): Boolean =
        layer in 1..5 && (layer - 1) in completedChapters

    /**
     * 单份档案是否已解锁可读。
     *
     * 解锁节奏（按文档“随核销进度逐份开放”落到结清组/单）：
     * - 第一层：序章结清开 #1，批次一结清开 #2，批次二结清开 #3~5，批次三结清开 #6~7；
     * - 第二层：星坠线三单各开 #1~3，紫菀线阶段二开 #4、阶段四开 #5，双线结清开 #6 残页；
     * - 第三层：ZX 三单逐份开 #1~3；
     * - 第四层：ZQ 三阶段逐份开 #1~3；
     * - 第五层：第四章结清（清算序列 100%）开放清算令原件。
     */
    fun isUnlocked(
        id: String,
        succeeded: Set<String>,
        clearedGroups: Set<String>,
        completedChapters: Set<Int>,
    ): Boolean = when (id) {
        "l1_charter" -> 0 in completedChapters
        "l1_division_memo" -> "c1_b1" in clearedGroups
        "l1_tritach_report", "l1_audit_203", "l1_coordinator_rules" -> "c1_b2" in clearedGroups
        "l1_battlegroup_index", "l1_contractor_rules" -> "c1_b3" in clearedGroups
        "l2_undelivered_catalog" -> "astd_main_c2_xc_1" in succeeded
        "l2_mothball_fleet_list" -> "astd_main_c2_xc_2" in succeeded
        "l2_watch_log_last_page" -> "astd_main_c2_xc_3" in succeeded
        "l2_admin_core_whitepaper" -> "astd_main_c2_zw_s2" in succeeded
        "l2_ethics_review" -> "astd_main_c2_zw_s4" in succeeded
        "l2_joint_memo_fragment" -> "c2_xc" in clearedGroups && "c2_zw" in clearedGroups
        "l3_seal_order_excerpt" -> "astd_main_c3_1" in succeeded
        "l3_target_registry" -> "astd_main_c3_2" in succeeded
        "l3_review_cover_page" -> "astd_main_c3_3" in succeeded
        "l4_review_full_text" -> "astd_main_c4_s1" in succeeded
        "l4_vote_record" -> "astd_main_c4_s2" in succeeded
        "l4_decommission_summary" -> "astd_main_c4_s3" in succeeded
        "l5_liquidation_order" -> 4 in completedChapters
        else -> false
    }
}
