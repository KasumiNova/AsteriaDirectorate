package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 主线赏金（MVP 数据版）：
 * - 数量按照“扩容后目标”先给出 24 个 key（方便代码侧进度/解锁逻辑对齐）。
 * - 文案的权威来源在 docs/story；这里仅放短描述（用于 bounty board/调试）。
 */
object StoryBounties {

    // 约定：主线 key 统一 astd_main_XX（两位）。
    private fun mkKey(i: Int): String = "astd_main_${i.toString().padStart(2, '0')}"

    private fun i18nMainTitle(i: Int): String = I18n["asteria_directorate_bounty", "main.${i.toString().padStart(2, '0')}.title"]
    private fun i18nMainShort(i: Int): String = I18n["asteria_directorate_bounty", "main.${i.toString().padStart(2, '0')}.short"]

    val mains: List<BountyDef> = buildList {
        // 序章（2）
        add(
            BountyDef(
                key = mkKey(1),
                title = i18nMainTitle(1),
                shortDesc = i18nMainShort(1),
                threatTier = 2,
                baselineFP = 45,
                flagshipVariantId = "astd_aurora_grid_Standard",
                // 核心阵容：1 巡洋 + 2 驱逐（其余由缩放模型补齐）
                coreVariantIds = listOf(
                    "astd_magnetic_storm_zigzag_Standard",
                    "astd_magnetic_storm_zigzag_Standard",
                ),
            )
        )
        add(
            BountyDef(
                key = mkKey(2),
                title = i18nMainTitle(2),
                shortDesc = i18nMainShort(2),
                threatTier = 2,
                baselineFP = 55,
                flagshipVariantId = "astd_magnetic_storm_zigzag_Standard",
                requiredPreviousMainKey = mkKey(1),
                // 核心阵容：1 驱逐 + 若干护卫
                coreVariantIds = listOf(
                    "astd_echo_shimmer_Standard",
                    "astd_echo_shimmer_Standard",
                ),
            )
        )

        // 第一幕（8）：3..10
        add(BountyDef(mkKey(3), i18nMainTitle(3), i18nMainShort(3), 2, 60, "astd_arc_flash_Standard", mkKey(2)))
        add(
            BountyDef(mkKey(4), i18nMainTitle(4), i18nMainShort(4), 2, 65, "astd_nebula_echo_Standard", mkKey(3))
        )
        add(
            BountyDef(
                key = mkKey(5),
                title = i18nMainTitle(5),
                shortDesc = i18nMainShort(5),
                threatTier = 3,
                baselineFP = 85,
                flagshipVariantId = "astd_aurora_grid_Standard",
                requiredPreviousMainKey = mkKey(4),
                // 核心阵容：1 巡洋 + 3 驱逐 + 4 护卫（其余由缩放补齐）
                coreVariantIds = listOf(
                    "astd_magnetic_storm_zigzag_Standard",
                    "astd_magnetic_storm_zigzag_Standard",
                    "astd_magnetic_storm_zigzag_Standard",
                    "astd_echo_shimmer_Standard",
                    "astd_echo_shimmer_Standard",
                    "astd_echo_shimmer_Standard",
                    "astd_echo_shimmer_Standard",
                ),
            )
        )
        add(BountyDef(mkKey(6), i18nMainTitle(6), i18nMainShort(6), 3, 95, "astd_negentropy_edge_Standard", mkKey(5)))
        add(BountyDef(mkKey(7), i18nMainTitle(7), i18nMainShort(7), 3, 95, "astd_gravitational_lens_Standard", mkKey(6)))
        add(BountyDef(mkKey(8), i18nMainTitle(8), i18nMainShort(8), 3, 110, "astd_diffraction_Standard", mkKey(7)))
        add(BountyDef(mkKey(9), i18nMainTitle(9), i18nMainShort(9), 3, 120, "astd_dark_tide_nebula_Standard", mkKey(8)))
        add(BountyDef(mkKey(10), i18nMainTitle(10), i18nMainShort(10), 3, 130, "astd_plasma_arch_Standard", mkKey(9)))

        // 第二幕（8）：11..18
        add(BountyDef(mkKey(11), i18nMainTitle(11), i18nMainShort(11), 3, 145, "astd_aurora_grid_Standard", mkKey(10)))
        add(BountyDef(mkKey(12), i18nMainTitle(12), i18nMainShort(12), 3, 155, "astd_nebula_echo_Standard", mkKey(11)))
        add(BountyDef(mkKey(13), i18nMainTitle(13), i18nMainShort(13), 4, 180, "astd_gravitational_lens_Standard", mkKey(12)))
        add(BountyDef(mkKey(14), i18nMainTitle(14), i18nMainShort(14), 4, 200, "astd_gravitational_lens_Automated", mkKey(13)))
        add(BountyDef(mkKey(15), i18nMainTitle(15), i18nMainShort(15), 4, 215, "astd_arc_flare_Standard", mkKey(14)))
        add(BountyDef(mkKey(16), i18nMainTitle(16), i18nMainShort(16), 4, 235, "astd_magnetosphere_disturbance_Standard", mkKey(15)))
        add(BountyDef(mkKey(17), i18nMainTitle(17), i18nMainShort(17), 4, 255, "astd_radiation_belt_Standard", mkKey(16)))
        add(BountyDef(mkKey(18), i18nMainTitle(18), i18nMainShort(18), 4, 270, "astd_arc_jet_Standard", mkKey(17)))

        // 第三幕（6）：19..24
        add(BountyDef(mkKey(19), i18nMainTitle(19), i18nMainShort(19), 4, 285, "astd_arc_flare_Standard", mkKey(18)))
        add(BountyDef(mkKey(20), i18nMainTitle(20), i18nMainShort(20), 4, 305, "astd_diffraction_Standard", mkKey(19)))
        add(BountyDef(mkKey(21), i18nMainTitle(21), i18nMainShort(21), 5, 330, "astd_apex_logic_Standard", mkKey(20)))
        add(BountyDef(mkKey(22), i18nMainTitle(22), i18nMainShort(22), 5, 360, "astd_apex_logic_Standard", mkKey(21)))
        add(BountyDef(mkKey(23), i18nMainTitle(23), i18nMainShort(23), 5, 380, "astd_arc_flare_Standard", mkKey(22)))
        add(BountyDef(mkKey(24), i18nMainTitle(24), i18nMainShort(24), 5, 400, "astd_apex_logic_Standard", mkKey(23)))
    }

    val mainsByKey: Map<String, BountyDef> = mains.associateBy { it.key }
}
