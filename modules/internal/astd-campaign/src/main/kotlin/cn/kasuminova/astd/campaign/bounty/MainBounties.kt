package cn.kasuminova.astd.campaign.bounty

/**
 * 主线赏金定义表（docs/story 新设定：方案 A「自我核销」，五章结构）。
 *
 * - 序章「新来的承包商」：1 单（XW 类目），由酒馆代办递出文书后出现（gating 见 [BountyKeys.MEM_PROLOGUE_DOC_RECEIVED]）。
 * - 第一章「积压工单」：批次制 2/3/1 单（YJ/YJ/JJ 类目），整批结清解锁下一批。
 * - 第二章「两份章程」：双线并行——星坠线（XC，3 单线性）+ 紫菀线（ZW，合并单拆 4 个内部阶段）。
 * - 第三章「核销」：ZX 执行类目 3 单线性，R 型词缀自单 3 解禁，目标池切换为总局遗存舰队。
 * - 第四章「最后一份文书」：ZQ 类目唯一工单（清除-00）拆 3 个执行阶段，等级从缺，主线战斗顶点。
 * - 第五章「归档」：无战斗，第四章结清后置 gating（见 [BountyState.archivalPending]）。
 *
 * 紫菀线合并单与第四章 ZQ 工单按文档口径拆为 MagicBounty 内部阶段单（顺序门槛），
 * 文书编号沿用同一张工单号，阶段体现在名称/描述/回执文案上。
 */
object MainBounties {

    /**
     * 结清组：批次/线路/章的结清单元。
     *
     * @property bonusBase 结清奖金基数（× 固有难度系数 k_s 缩放，k_s ≤ 5 即文档口径“最高 5×”）
     * @property receiptKey 结清回执 i18n 键（HUD 打印；为空则不打印）
     */
    data class GroupDef(
        val id: String,
        val bonusBase: Int,
        val receiptKey: String?,
    )

    val groups: List<GroupDef> = listOf(
        GroupDef("prologue", 0, null),
        GroupDef("c1_b1", 300_000, "group.c1_b1.receipt"),
        GroupDef("c1_b2", 500_000, "group.c1_b2.receipt"),
        GroupDef("c1_b3", 750_000, "group.c1_b3.receipt"),
        GroupDef("c2_xc", 1_000_000, "group.c2_xc.receipt"),
        GroupDef("c2_zw", 1_000_000, "group.c2_zw.receipt"),
        GroupDef("c3", 1_500_000, "group.c3.receipt"),
        GroupDef("c4", 2_000_000, null),
    )

    val groupsById: Map<String, GroupDef> = groups.associateBy { it.id }

    /** MagicBounty 的战斗完成标记与分局交付标记分离；后续工单只在交付后解锁。 */
    private fun memKeyOf(key: String): String = "\$$key"

    const val KEY_PROLOGUE: String = "astd_main_prologue"

    val defs: List<BountyDef> = buildList {
        // ── 序章「新来的承包商」（03 文档）：FP 80，危险等级一级，词缀不介入，报酬 20万~100万 ──
        add(
            BountyDef(
                key = KEY_PROLOGUE,
                code = "XW-c206-0447／核销-03",
                chapter = 0,
                groupId = "prologue",
                dangerLevel = 1,
                baselineFP = 80,
                flagshipVariantId = "astd_aurora_grid_Standard",
                fleetFactionId = "remnant",
                requiredMemKeys = listOf(BountyKeys.MEM_PROLOGUE_DOC_RECEIVED),
                affixRule = AffixRule.NONE,
                rewardMin = 200_000,
                rewardMax = 1_000_000,
                // 主力目标为随机巡洋舰资产 + 随机 D-mod（两百年失修）
                flagshipDMods = 2,
            )
        )

        // ── 第一章「积压工单」（05/06 文档）：单票 30万~150万 ──
        // 批次一「例行核销」：2 单，FP 120，危险等级二级，词缀不介入
        add(
            BountyDef(
                key = "astd_main_c1_b1_a",
                code = "YJ-c206-1102／核销-17",
                chapter = 1,
                groupId = "c1_b1",
                dangerLevel = 2,
                baselineFP = 120,
                flagshipVariantId = "astd_arc_flash_Standard",
                fleetFactionId = "pirates",
                requiredMemKeys = listOf(memKeyOf(KEY_PROLOGUE)),
                affixRule = AffixRule.NONE,
                rewardMin = 300_000,
                rewardMax = 1_500_000,
            )
        )
        add(
            BountyDef(
                key = "astd_main_c1_b1_b",
                code = "YJ-c206-1103／追索-04",
                chapter = 1,
                groupId = "c1_b1",
                dangerLevel = 2,
                baselineFP = 120,
                flagshipVariantId = "astd_nebula_echo_Standard",
                fleetFactionId = "pirates",
                requiredMemKeys = listOf(memKeyOf(KEY_PROLOGUE)),
                affixRule = AffixRule.NONE,
                rewardMin = 300_000,
                rewardMax = 1_500_000,
            )
        )
        // 批次二「条款追加」：3 单，FP 200，危险等级三级，词缀介入（S 2~4 + M 1~2）
        val c1b2Gate = listOf(memKeyOf("astd_main_c1_b1_a"), memKeyOf("astd_main_c1_b1_b"))
        add(
            BountyDef(
                key = "astd_main_c1_b2_a",
                code = "YJ-c206-1198／核销-21",
                clauseCount = 1,
                chapter = 1,
                groupId = "c1_b2",
                dangerLevel = 3,
                baselineFP = 200,
                flagshipVariantId = "astd_magnetic_storm_zigzag_Standard",
                fleetFactionId = "pirates",
                requiredMemKeys = c1b2Gate,
                affixRule = AffixRule.STANDARD,
                rewardMin = 300_000,
                rewardMax = 1_500_000,
            )
        )
        add(
            BountyDef(
                key = "astd_main_c1_b2_b",
                code = "YJ-c206-1201／核销-25",
                clauseCount = 2,
                chapter = 1,
                groupId = "c1_b2",
                dangerLevel = 3,
                baselineFP = 200,
                flagshipVariantId = "astd_negentropy_edge_Standard",
                fleetFactionId = "pirates",
                requiredMemKeys = c1b2Gate,
                affixRule = AffixRule.STANDARD,
                rewardMin = 300_000,
                rewardMax = 1_500_000,
            )
        )
        add(
            BountyDef(
                key = "astd_main_c1_b2_c",
                code = "YJ-c206-1204／核销-28",
                clauseCount = 2,
                chapter = 1,
                groupId = "c1_b2",
                dangerLevel = 3,
                baselineFP = 200,
                flagshipVariantId = "astd_gravitational_lens_Standard",
                fleetFactionId = "pirates",
                requiredMemKeys = c1b2Gate,
                affixRule = AffixRule.STANDARD,
                rewardMin = 300_000,
                rewardMax = 1_500_000,
            )
        )
        // 批次三「加急件」：1 单，FP 300，危险等级四级，赏金势力固定余晖
        add(
            BountyDef(
                key = "astd_main_c1_b3",
                code = "JJ-c206-0007／清除-01〔加急〕",
                clauseCount = 3,
                chapter = 1,
                groupId = "c1_b3",
                dangerLevel = 4,
                baselineFP = 300,
                flagshipVariantId = "astd_gravitational_lens_Automated",
                fleetFactionId = "remnant",
                requiredMemKeys = listOf(
                    memKeyOf("astd_main_c1_b2_a"),
                    memKeyOf("astd_main_c1_b2_b"),
                    memKeyOf("astd_main_c1_b2_c"),
                ),
                affixRule = AffixRule.STANDARD,
                rewardMin = 300_000,
                rewardMax = 1_500_000,
            )
        )

        // ── 第二章「两份章程」（07/08 文档）：单票 40万~200万，两线并行 ──
        val c2Gate = listOf(memKeyOf("astd_main_c1_b3"))
        // 星坠线（XC）：3 单线性递进，FP 400/600/800，等级 4/4/5
        add(
            BountyDef(
                key = "astd_main_c2_xc_1",
                code = "XC-c208-0216／注销-01〔封存〕",
                clauseCount = 1,
                chapter = 2,
                groupId = "c2_xc",
                dangerLevel = 4,
                baselineFP = 400,
                flagshipVariantId = "astd_arc_flare_Standard",
                fleetFactionId = "remnant",
                requiredMemKeys = c2Gate,
                affixRule = AffixRule.STANDARD,
                rewardMin = 400_000,
                rewardMax = 2_000_000,
                modOnlyComposition = true,
            )
        )
        add(
            BountyDef(
                key = "astd_main_c2_xc_2",
                code = "XC-c208-0217／注销-02〔封存〕",
                clauseCount = 1,
                chapter = 2,
                groupId = "c2_xc",
                dangerLevel = 4,
                baselineFP = 600,
                flagshipVariantId = "astd_radiation_belt_Standard",
                fleetFactionId = "remnant",
                requiredMemKeys = listOf(memKeyOf("astd_main_c2_xc_1")),
                affixRule = AffixRule.STANDARD,
                rewardMin = 400_000,
                rewardMax = 2_000_000,
                modOnlyComposition = true,
            )
        )
        add(
            BountyDef(
                key = "astd_main_c2_xc_3",
                code = "XC-c208-0221／回收-01〔封存〕",
                clauseCount = 1,
                chapter = 2,
                groupId = "c2_xc",
                dangerLevel = 5,
                baselineFP = 800,
                flagshipVariantId = "astd_plasma_arch_Standard",
                fleetFactionId = "remnant",
                requiredMemKeys = listOf(memKeyOf("astd_main_c2_xc_2")),
                affixRule = AffixRule.STANDARD,
                rewardMin = 400_000,
                rewardMax = 2_000_000,
                modOnlyComposition = true,
            )
        )
        // 紫菀线（ZW）：合并单拆 4 个内部阶段——3×300 FP 引力节点遭遇 + 800 FP 核心守备
        val zwStages = listOf(
            Triple("astd_main_c2_zw_s1", 300, "astd_diffraction_Standard"),
            Triple("astd_main_c2_zw_s2", 300, "astd_magnetosphere_disturbance_Standard"),
            Triple("astd_main_c2_zw_s3", 300, "astd_dark_tide_nebula_Standard"),
            Triple("astd_main_c2_zw_s4", 800, "astd_gravitational_lens_Automated"),
        )
        zwStages.forEachIndexed { idx, (key, fp, flagship) ->
            add(
                BountyDef(
                    key = key,
                    code = "ZW-c208-0309／回收-02〔封存〕",
                    chapter = 2,
                    groupId = "c2_zw",
                    dangerLevel = 5,
                    baselineFP = fp,
                    flagshipVariantId = flagship,
                    fleetFactionId = "remnant",
                    requiredMemKeys = if (idx == 0) c2Gate else listOf(memKeyOf(zwStages[idx - 1].first)),
                    affixRule = AffixRule.STANDARD,
                    rewardMin = 400_000,
                    rewardMax = 2_000_000,
                    modOnlyComposition = true,
                )
            )
        }

        // ── 第三章「核销」（09/10 文档）：ZX 类目 3 单线性，FP 1000/1200/1500，等级 5/5/6 ──
        // 清算序列进度节拍：97.9 → 97.4（反常回跳）→ 98.8；单 3 固定至少 1 条 R
        val c3Gate = listOf(memKeyOf("astd_main_c2_xc_3"), memKeyOf("astd_main_c2_zw_s4"))
        add(
            BountyDef(
                key = "astd_main_c3_1",
                code = "ZX-c208-1001／核销-03（重挂）",
                chapter = 3,
                groupId = "c3",
                dangerLevel = 5,
                baselineFP = 1000,
                flagshipVariantId = "astd_arc_flare_Standard",
                fleetFactionId = "remnant",
                requiredMemKeys = c3Gate,
                affixRule = AffixRule.STANDARD,
                rewardMin = 500_000,
                rewardMax = 2_500_000,
                liquidationDisplay = 97.9f,
                modOnlyComposition = true,
            )
        )
        add(
            BountyDef(
                key = "astd_main_c3_2",
                code = "ZX-c207-0344／核销-41",
                chapter = 3,
                groupId = "c3",
                dangerLevel = 5,
                baselineFP = 1200,
                flagshipVariantId = "astd_magnetosphere_disturbance_Standard",
                fleetFactionId = "remnant",
                requiredMemKeys = listOf(memKeyOf("astd_main_c3_1")),
                affixRule = AffixRule.STANDARD,
                rewardMin = 500_000,
                rewardMax = 2_500_000,
                liquidationDisplay = 97.4f,
                modOnlyComposition = true,
            )
        )
        add(
            BountyDef(
                key = "astd_main_c3_3",
                code = "ZX-c208-0002／清除-02",
                clauseCount = 1,
                chapter = 3,
                groupId = "c3",
                dangerLevel = 6,
                baselineFP = 1500,
                flagshipVariantId = "astd_arc_jet_Standard",
                fleetFactionId = "remnant",
                requiredMemKeys = listOf(memKeyOf("astd_main_c3_2")),
                affixRule = AffixRule.withR(rMin = 1, rMax = 2),
                rewardMin = 500_000,
                rewardMax = 2_500_000,
                liquidationDisplay = 98.8f,
                modOnlyComposition = true,
            )
        )

        // ── 第四章「最后一份文书」（11/12 文档）：ZQ 类目唯一工单拆 3 阶段，FP 1800/2200/2800 ──
        // 等级从缺；R 条款 1 / 1~2 / 2（阶段三打满搭配表 S4+M2+R2）；进度 99.1 → 99.6 → 100.0
        val c4Stages = listOf(
            Triple("astd_main_c4_s1", 1800, "astd_radiation_belt_Standard"),
            Triple("astd_main_c4_s2", 2200, "astd_plasma_arch_Standard"),
            Triple("astd_main_c4_s3", 2800, "astd_apex_logic_Standard"),
        )
        val c4Affix = listOf(
            AffixRule.withR(1, 1),
            AffixRule.withR(1, 2),
            AffixRule.withR(2, 2),
        )
        val c4Progress = listOf(99.1f, 99.6f, 100.0f)
        c4Stages.forEachIndexed { idx, (key, fp, flagship) ->
            add(
                BountyDef(
                    key = key,
                    code = "ZQ-c208-0001／清除-00",
                    chapter = 4,
                    groupId = "c4",
                    dangerLevel = 6,
                    dangerAbsent = true,
                    baselineFP = fp,
                    flagshipVariantId = flagship,
                    fleetFactionId = "remnant",
                    requiredMemKeys = if (idx == 0) listOf(memKeyOf("astd_main_c3_3")) else listOf(memKeyOf(c4Stages[idx - 1].first)),
                    affixRule = c4Affix[idx],
                    rewardMin = 750_000,
                    rewardMax = 3_750_000,
                    liquidationDisplay = c4Progress[idx],
                    modOnlyComposition = true,
                )
            )
        }
    }

    val defsByKey: Map<String, BountyDef> = defs.associateBy { it.key }

    /** 结清组 → 成员单 key。 */
    val groupMembers: Map<String, List<String>> = defs.groupBy({ it.groupId }, { it.key })

    /** 章节 → 成员单 key。 */
    val chapterMembers: Map<Int, List<String>> = defs.groupBy({ it.chapter }, { it.key })
}

/**
 * 主线推进的纯计算层：结清组/章节判定与承包商等级映射，与游戏环境解耦，供单元测试直接驱动。
 */
object MainlineProgression {

    /** 本次成功后新结清的结清组（组成员全部成功且此前未结清）。 */
    fun newlyClearedGroups(
        succeededKeys: Set<String>,
        alreadyClearedGroups: Set<String>,
    ): List<MainBounties.GroupDef> = MainBounties.groups.filter { group ->
        group.id !in alreadyClearedGroups &&
            (MainBounties.groupMembers[group.id] ?: emptyList()).all { it in succeededKeys }
    }

    /** 本次成功后新结清的章节（章节成员全部成功且此前未结清）。 */
    fun newlyClearedChapters(
        succeededKeys: Set<String>,
        alreadyCompletedChapters: Set<Int>,
    ): List<Int> = MainBounties.chapterMembers.keys
        .filter { it !in alreadyCompletedChapters }
        .filter { chapter -> (MainBounties.chapterMembers[chapter] ?: emptyList()).all { it in succeededKeys } }
        .sorted()

    /** 章节结清后的承包商等级：序章注册为一级，此后每章递升一级，第四章末五级（常规顶格）。 */
    fun contractorLevelAfterChapter(chapter: Int): Int = (chapter + 1).coerceIn(1, 5)
}
