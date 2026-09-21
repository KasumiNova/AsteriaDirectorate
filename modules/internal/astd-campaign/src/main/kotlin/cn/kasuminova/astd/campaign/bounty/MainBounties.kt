package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.MainBounties.groups
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.impl.campaign.ids.Factions

/**
 * 赏金主线条目注册表（docs/story 02~12 定稿口径）。
 *
 * 主线共 15 张工单，归属 8 个结清组（批次/线/章）：
 * - 序章：XW-c206-0447／核销-03（80 FP，一级，无词缀，酒馆对话接取、不挂牌）；
 * - 第一章「积压工单」三批：批一 YJ 120×2、批二 YJ 200×3（词缀介入）、批三 JJ 300（余晖固定）；
 * - 第二章「两份章程」双线并行：星坠线 XC 400/600/800 三单线性递进，
 *   紫菀线 ZW 单工单四阶段（300×3 节点遭遇 + 800 核心守备）；
 * - 第三章「核销」ZX 1000/1200/1500 线性递进，单 3 固定至少 1 条 R（R 型解禁）；
 * - 第四章「最后一份文书」ZQ 单工单三阶段（1800/2200/2800，等级从缺，旗舰 R-17 奇点驱动）。
 *
 * 所有玩家可见文本只存 i18n 键（category = `asteria_directorate_bounty`，
 * 键前缀 `main.<工单短 id>.*`），文案定稿见 docs/story/06、08、10、12。
 */
object MainBounties {

    /**
     * 工单的一个执行阶段。单阶段工单 stages 只有一个元素；
     * 多阶段工单（ZW/ZQ）对外是同一工单 key 的逐阶段演进。
     *
     * @property baselineFP 预设舰队点数（02 文档 FP 全局算法的基数，实际规模由难度系数缩放）
     * @property flagshipVariantId 旗舰 variant 占位（TODO：随 P3/P4/P7 舰船池联调替换为定稿选型）
     * @property coreVariantIds 固定核心编成（护航占位，同样随舰船池联调）
     * @property flagshipAffixIds 旗舰专属词缀 id（如四章中军旗舰的 R-17 奇点驱动，编队词缀之外单独施加）
     * @property fixedAffixIds 固定词缀表：文书追加条款具名编目号对应的编队词缀（docs/story 08/10/12 定稿口径）。
     *   非 null 时该阶段编队词缀按本表挂载、不再随机抽取；null 时走随机抽取（一章批二/批三条款
     *   未具名编目号，仍为随机口径；动态/无限赏金同样走随机）。
     * @property liquidationDelta 该阶段击毁时清算序列进度增量（百分点；ZW 阶段为 0，ZQ 三阶段 0.3/0.5/0.4）
     * @property stageReceipt 本阶段完成后是否打印阶段回执（仅 ZQ 有定稿阶段回执，见 12 文档）
     */
    data class Stage(
        val baselineFP: Int,
        val flagshipVariantId: String,
        val coreVariantIds: List<String> = emptyList(),
        val flagshipAffixIds: List<String> = emptyList(),
        val fixedAffixIds: List<String>? = null,
        val liquidationDelta: Float = 0f,
        val stageReceipt: Boolean = false,
    )

    /**
     * 一张主线工单（文书）。
     *
     * @property key MagicBounty 侧 bounty key（带 `astd_` 前缀，全局唯一）
     * @property serial 文书编号（如 `YJ-c206-1102／核销-17`，仅展示用）
     * @property chapter 章节（0=序章，1~4）
     * @property groupId 结清组 id（见 [groups]）
     * @property threatTier 危险等级（驱动难度缩放；四章工单内部按满级处理）
     * @property dangerOmitted 危险等级栏是否留白（「等级从缺」，仅四章 ZQ）
     * @property stages 执行阶段（单阶段工单为单元素）
     * @property rewardMin / rewardMax 单票报酬区间（随难度系数缩放，见 02/05/07/09/11 数值口径）
     * @property requiresGroups 前置结清组（全部结清后才可挂出）
     * @property requiresOrders 前置工单（全部核销后才可挂出；线性递进用）
     * @property boardPosted 是否由终端挂出（序章为 false：酒馆对话接取，见 03/04 文档）
     * @property allowAffixes 是否挂词缀（序章与第一章批一不挂词缀）
     * @property allowRAffixes R 型词缀开关（仅三、四章，affixes.md v3 口径）
     * @property minRAffixes 固定至少携带的 R 型数量（三章单 3 = 1）
     * @property clauseCount 文书「追加条款」条数（i18n 键 main.<id>.clause.1..N；无追加条款为 0）
     * @property fleetFaction 目标舰队势力（占位，随舰船池/势力定义联调）
     * @property liquidationDelta 单阶段工单核销时的清算序列进度增量（ZX 三单 0.6 / -0.5 / 1.4；
     *   负值为三章单 2 的反常跳动——遗存目标被批量重新登记、清单变长稀释完成率，见 09 文档）
     * @property nodeDrivenStages 前 N 个执行阶段由引力节点拔除驱动（仅 ZW=3，07 文档：
     *   阶段一~三 = 拔除 3 座引力节点）。这些阶段不生成 MagicBounty 击毁工单——挂出仅登记
     *   报价与 posted 状态，阶段推进消费 StoryWorldState.gravityNodesPulled
     *   （同步逻辑见 [MainlineProgression.syncNodePulledStage]，桥接见 MainBountyBridge.tickPosted）；
     *   节点护卫舰队由 GravityNodeScripts 接触触发（基准 300 FP），与本表 baselineFP 口径一致。
     */
    data class WorkOrder(
        val key: String,
        val serial: String,
        val chapter: Int,
        val groupId: String,
        val threatTier: Int,
        val dangerOmitted: Boolean = false,
        val stages: List<Stage>,
        val rewardMin: Int,
        val rewardMax: Int,
        val requiresGroups: List<String> = emptyList(),
        val requiresOrders: List<String> = emptyList(),
        val boardPosted: Boolean = true,
        val allowAffixes: Boolean = true,
        val allowRAffixes: Boolean = false,
        val minRAffixes: Int = 0,
        val clauseCount: Int = 0,
        val fleetFaction: String,
        val liquidationDelta: Float = 0f,
        val nodeDrivenStages: Int = 0,
        /**
         * 目标舰队落点锚定实体 id（MagicBounty location_marketIDs 实参语义为实体 id 列表）。
         * 07 文档：第二章工单坐标指向遗址星系（星坠线锚定星坠恒星、紫菀线锚定紫菀恒星）；
         * null 表示任意跳点落位（一/三/四章无定点要求）。
         */
        val spawnAnchorEntityId: String? = null,
    ) {
        /** i18n 短 id（`main.<i18nId>.*`）。 */
        val i18nId: String get() = key.removePrefix(BountyKeys.BOUNTY_KEY_PREFIX + "main_")

        /** 是否为多阶段合并工单（ZW 四阶段 / ZQ 三阶段）。 */
        val multiStage: Boolean get() = stages.size > 1

        /** 该阶段是否为引力节点拔除驱动（不生成 MagicBounty 击毁工单）。 */
        fun isNodeDrivenStage(stageIndex: Int): Boolean = stageIndex < nodeDrivenStages

        /** 转换为舰队生成用的通用定义。 */
        fun toBountyDef(stageIndex: Int): BountyDef {
            val stage = stages[stageIndex.coerceIn(0, stages.lastIndex)]
            return BountyDef(
                key = key,
                title = serial,
                shortDesc = "",
                threatTier = threatTier,
                baselineFP = stage.baselineFP,
                flagshipVariantId = stage.flagshipVariantId,
                requiredPreviousMainKey = requiresOrders.lastOrNull(),
                isMain = true,
                coreVariantIds = stage.coreVariantIds,
                allowRAffixes = allowRAffixes,
                allowAffixes = allowAffixes,
                minRAffixes = minRAffixes,
                flagshipAffixIds = stage.flagshipAffixIds,
                fixedAffixIds = stage.fixedAffixIds,
            )
        }
    }

    /**
     * 结清组（批次/线/章）：组内工单全部核销后结清，发放结清奖金并推进 gating 链。
     *
     * @property id 组 id（i18n 键 `group.<id>.receipt`）
     * @property chapter 所属章节
     * @property bonusBase 结清奖金基数（随难度系数缩放、最高 5×；序章无结清奖金）
     */
    data class ClearGroup(val id: String, val chapter: Int, val bonusBase: Int)

    const val GROUP_PROLOGUE = "prologue"
    const val GROUP_CH1_BATCH1 = "ch1_batch1"
    const val GROUP_CH1_BATCH2 = "ch1_batch2"
    const val GROUP_CH1_BATCH3 = "ch1_batch3"
    const val GROUP_CH2_XC = "ch2_xc"
    const val GROUP_CH2_ZW = "ch2_zw"
    const val GROUP_CH3 = "ch3"
    const val GROUP_CH4 = "ch4"

    /** 8 个结清组（序章 + 一章三批 + 二章双线 + 三章 + 四章）。 */
    val groups: List<ClearGroup> = listOf(
        ClearGroup(GROUP_PROLOGUE, chapter = 0, bonusBase = 0),
        ClearGroup(GROUP_CH1_BATCH1, chapter = 1, bonusBase = 300_000),
        ClearGroup(GROUP_CH1_BATCH2, chapter = 1, bonusBase = 500_000),
        ClearGroup(GROUP_CH1_BATCH3, chapter = 1, bonusBase = 750_000),
        ClearGroup(GROUP_CH2_XC, chapter = 2, bonusBase = 1_000_000),
        ClearGroup(GROUP_CH2_ZW, chapter = 2, bonusBase = 1_000_000),
        ClearGroup(GROUP_CH3, chapter = 3, bonusBase = 1_500_000),
        ClearGroup(GROUP_CH4, chapter = 4, bonusBase = 2_000_000),
    )

    // ─── 序章「新来的承包商」（03 文档） ───

    const val KEY_PROLOGUE = "astd_main_xw_c206_0447"

    // ─── 第一章「积压工单」（05/06 文档） ───

    const val KEY_YJ_1102 = "astd_main_yj_c206_1102"
    const val KEY_YJ_1103 = "astd_main_yj_c206_1103"
    const val KEY_YJ_1198 = "astd_main_yj_c206_1198"
    const val KEY_YJ_1201 = "astd_main_yj_c206_1201"
    const val KEY_YJ_1204 = "astd_main_yj_c206_1204"
    const val KEY_JJ_0007 = "astd_main_jj_c206_0007"

    // ─── 第二章「两份章程」（07/08 文档） ───

    const val KEY_XC_0216 = "astd_main_xc_c208_0216"
    const val KEY_XC_0217 = "astd_main_xc_c208_0217"
    const val KEY_XC_0221 = "astd_main_xc_c208_0221"
    const val KEY_ZW_0309 = "astd_main_zw_c208_0309"

    // ─── 第三章「核销」（09/10 文档） ───

    const val KEY_ZX_1001 = "astd_main_zx_c208_1001"
    const val KEY_ZX_0344 = "astd_main_zx_c207_0344"
    const val KEY_ZX_0002 = "astd_main_zx_c208_0002"

    // ─── 第四章「最后一份文书」（11/12 文档） ───

    const val KEY_ZQ_0001 = "astd_main_zq_c208_0001"

    /** 全部主线条目（剧情顺序）。 */
    val all: List<WorkOrder> = listOf(
        // 序章：酒馆对话接取（不挂牌），80 FP，一级，无词缀
        WorkOrder(
            key = KEY_PROLOGUE,
            serial = "XW-c206-0447／核销-03",
            chapter = 0,
            groupId = GROUP_PROLOGUE,
            threatTier = 1,
            stages = listOf(
                // TODO：选型随 P3/P4/P7 舰船池联调（总局旧制式巡洋舰，剧情要求带随机 D-mod）
                Stage(baselineFP = 80, flagshipVariantId = "astd_zl_101_Standard"),
            ),
            rewardMin = 200_000,
            rewardMax = 1_000_000,
            boardPosted = false,
            allowAffixes = false,
            fleetFaction = "derelict",
        ),

        // 一章批一「例行核销」：120 FP×2，二级，无词缀
        WorkOrder(
            key = KEY_YJ_1102,
            serial = "YJ-c206-1102／核销-17",
            chapter = 1,
            groupId = GROUP_CH1_BATCH1,
            threatTier = 2,
            // TODO：选型随 P3/P4/P7 舰船池联调（「红鹫」资产侵占方）
            stages = listOf(Stage(baselineFP = 120, flagshipVariantId = "dominator_d_Assault")),
            rewardMin = 300_000,
            rewardMax = 1_500_000,
            requiresGroups = listOf(GROUP_PROLOGUE),
            allowAffixes = false,
            fleetFaction = "pirates",
        ),
        WorkOrder(
            key = KEY_YJ_1103,
            serial = "YJ-c206-1103／追索-04",
            chapter = 1,
            groupId = GROUP_CH1_BATCH1,
            threatTier = 2,
            // TODO：选型随 P3/P4/P7 舰船池联调（承运船队「远衡」编队）
            stages = listOf(Stage(baselineFP = 120, flagshipVariantId = "falcon_p_Strike")),
            rewardMin = 300_000,
            rewardMax = 1_500_000,
            requiresGroups = listOf(GROUP_PROLOGUE),
            allowAffixes = false,
            fleetFaction = "pirates",
        ),

        // 一章批二「条款追加」：200 FP×3，三级，词缀介入
        WorkOrder(
            key = KEY_YJ_1198,
            serial = "YJ-c206-1198／核销-21",
            chapter = 1,
            groupId = GROUP_CH1_BATCH2,
            threatTier = 3,
            // TODO：选型随 P3/P4/P7 舰船池联调（浮标设施拾荒舰队）
            stages = listOf(Stage(baselineFP = 200, flagshipVariantId = "dominator_Assault")),
            rewardMin = 300_000,
            rewardMax = 1_500_000,
            requiresGroups = listOf(GROUP_CH1_BATCH1),
            clauseCount = 1,
            fleetFaction = "pirates",
        ),
        WorkOrder(
            key = KEY_YJ_1201,
            serial = "YJ-c206-1201／核销-25",
            chapter = 1,
            groupId = GROUP_CH1_BATCH2,
            threatTier = 3,
            // TODO：选型随 P3/P4/P7 舰船池联调（无主雇佣兵战斗群）
            stages = listOf(Stage(baselineFP = 200, flagshipVariantId = "eagle_Assault")),
            rewardMin = 300_000,
            rewardMax = 1_500_000,
            requiresGroups = listOf(GROUP_CH1_BATCH1),
            clauseCount = 2,
            fleetFaction = "pirates",
        ),
        WorkOrder(
            key = KEY_YJ_1204,
            serial = "YJ-c206-1204／核销-28",
            chapter = 1,
            groupId = GROUP_CH1_BATCH2,
            threatTier = 3,
            // TODO：选型随 P3/P4/P7 舰船池联调（中继站武装团伙）
            stages = listOf(Stage(baselineFP = 200, flagshipVariantId = "falcon_Attack")),
            rewardMin = 300_000,
            rewardMax = 1_500_000,
            requiresGroups = listOf(GROUP_CH1_BATCH1),
            clauseCount = 2,
            fleetFaction = "pirates",
        ),

        // 一章批三「加急件」：300 FP，四级，余晖固定
        WorkOrder(
            key = KEY_JJ_0007,
            serial = "JJ-c206-0007／清除-01〔加急〕",
            chapter = 1,
            groupId = GROUP_CH1_BATCH3,
            threatTier = 4,
            // TODO：选型随 P3/P4/P7 舰船池联调（游荡自动舰队）
            stages = listOf(Stage(baselineFP = 300, flagshipVariantId = "radiant_Standard")),
            rewardMin = 300_000,
            rewardMax = 1_500_000,
            requiresGroups = listOf(GROUP_CH1_BATCH2),
            clauseCount = 3,
            fleetFaction = Factions.REMNANTS,
        ),

        // 二章星坠线「武器试验场遗留工单」：400/600/800 线性递进
        WorkOrder(
            key = KEY_XC_0216,
            serial = "XC-c208-0216／注销-01〔封存〕",
            chapter = 2,
            groupId = GROUP_CH2_XC,
            threatTier = 4,
            // TODO：选型随 P3/P4/P7 舰船池联调（试验场外围警戒平台编队）
            // 条款①S-01 装甲强化套件、②M-09 编队目标定位协同改装（08 文档单 1）
            stages = listOf(
                Stage(
                    baselineFP = 400,
                    flagshipVariantId = "astd_zw_001_Standard",
                    fixedAffixIds = listOf(AffixRegistry.ID_IRONCLAD_PLATING, AffixRegistry.ID_RECURSIVE_TARGETING),
                ),
            ),
            rewardMin = 400_000,
            rewardMax = 2_000_000,
            requiresGroups = listOf(GROUP_CH1_BATCH3),
            clauseCount = 2,
            fleetFaction = Factions.REMNANTS,
            spawnAnchorEntityId = StoryWorldIds.STARFALL_STAR,
        ),
        WorkOrder(
            key = KEY_XC_0217,
            serial = "XC-c208-0217／注销-02〔封存〕",
            chapter = 2,
            groupId = GROUP_CH2_XC,
            threatTier = 4,
            // TODO：选型随 P3/P4/P7 舰船池联调（试验场内环防御群）
            // 条款①S-02 辐能调度网络、②S-05 引擎超频套件、③M-13 编队协同网络改装（08 文档单 2）
            stages = listOf(
                Stage(
                    baselineFP = 600,
                    flagshipVariantId = "astd_zw_102_Standard",
                    fixedAffixIds = listOf(
                        AffixRegistry.ID_CRYO_FLUX_NETWORK,
                        AffixRegistry.ID_ENGINE_OVERCLOCK,
                        AffixRegistry.ID_SWARM_COORDINATION,
                    ),
                ),
            ),
            rewardMin = 400_000,
            rewardMax = 2_000_000,
            requiresGroups = listOf(GROUP_CH1_BATCH3),
            requiresOrders = listOf(KEY_XC_0216),
            clauseCount = 3,
            fleetFaction = Factions.REMNANTS,
            spawnAnchorEntityId = StoryWorldIds.STARFALL_STAR,
        ),
        WorkOrder(
            key = KEY_XC_0221,
            serial = "XC-c208-0221／回收-01〔封存〕",
            chapter = 2,
            groupId = GROUP_CH2_XC,
            threatTier = 5,
            // TODO：选型随 P3/P4/P7 舰船池联调（试验场核心库守备编队）
            // 条款①S-04 护盾极化改装、②S-06 峰值维续套件、③M-10 反应式辐能装甲（08 文档单 3；
            // 条款④「守备规模超出常规范畴」为规模叙述，不对应词缀）
            stages = listOf(
                Stage(
                    baselineFP = 800,
                    flagshipVariantId = "astd_xc_102_Standard",
                    fixedAffixIds = listOf(
                        AffixRegistry.ID_POLARIZED_SHIELD,
                        AffixRegistry.ID_DIMENSIONAL_SPECIALTY,
                        AffixRegistry.ID_REACTIVE_FLUX_ARMOR,
                    ),
                ),
            ),
            rewardMin = 400_000,
            rewardMax = 2_000_000,
            requiresGroups = listOf(GROUP_CH1_BATCH3),
            requiresOrders = listOf(KEY_XC_0217),
            clauseCount = 4,
            fleetFaction = Factions.REMNANTS,
            spawnAnchorEntityId = StoryWorldIds.STARFALL_STAR,
        ),

        // 二章紫菀线「科研遗址遗留工单」：单工单四阶段（300×3 节点拔除 + 800 核心守备）
        WorkOrder(
            key = KEY_ZW_0309,
            serial = "ZW-c208-0309／回收-02〔封存〕",
            chapter = 2,
            groupId = GROUP_CH2_ZW,
            threatTier = 5,
            // TODO：选型随 P3/P4/P7 舰船池联调（引力节点护卫舰队 / 核心数据舱守备舰队）
            // 阶段 0~2 为引力节点拔除驱动（nodeDrivenStages=3）：不生成 MagicBounty 击毁工单，
            // 拔除进度（StoryWorldState.gravityNodesPulled）经 MainBountyBridge.tickPosted 同步推进；
            // 阶段 3 核心数据舱守备战（800 FP）保持 MagicBounty 击毁路径
            // 条款③S-03 辐能隔离套件 + M-10 辐能装甲改装（08 文档合并单；条款①②为引力锚定场
            // 编目外条目与全场抑制机制，不对应词缀），四阶段防御编队口径一致
            stages = listOf(
                Stage(
                    baselineFP = 300,
                    flagshipVariantId = "astd_xc_101_Standard",
                    fixedAffixIds = listOf(AffixRegistry.ID_FLUX_COIL_EXPANSION, AffixRegistry.ID_REACTIVE_FLUX_ARMOR),
                ),
                Stage(
                    baselineFP = 300,
                    flagshipVariantId = "astd_xc_101_Standard",
                    fixedAffixIds = listOf(AffixRegistry.ID_FLUX_COIL_EXPANSION, AffixRegistry.ID_REACTIVE_FLUX_ARMOR),
                ),
                Stage(
                    baselineFP = 300,
                    flagshipVariantId = "astd_xc_101_Standard",
                    fixedAffixIds = listOf(AffixRegistry.ID_FLUX_COIL_EXPANSION, AffixRegistry.ID_REACTIVE_FLUX_ARMOR),
                ),
                Stage(
                    baselineFP = 800,
                    flagshipVariantId = "astd_zl_001_Standard",
                    fixedAffixIds = listOf(AffixRegistry.ID_FLUX_COIL_EXPANSION, AffixRegistry.ID_REACTIVE_FLUX_ARMOR),
                ),
            ),
            rewardMin = 400_000,
            rewardMax = 2_000_000,
            requiresGroups = listOf(GROUP_CH1_BATCH3),
            clauseCount = 3,
            fleetFaction = Factions.REMNANTS,
            nodeDrivenStages = 3,
            spawnAnchorEntityId = StoryWorldIds.ASTER_STAR,
        ),

        // 三章「核销」：1000/1200/1500 线性递进，单 3 固定至少 1 条 R
        WorkOrder(
            key = KEY_ZX_1001,
            serial = "ZX-c208-1001／核销-03（重挂）",
            chapter = 3,
            groupId = GROUP_CH3,
            threatTier = 5,
            // TODO：选型随 P3/P4/P7 舰船池联调（重挂目标：与序章同识别码的自动巡航舰）
            stages = listOf(
                Stage(baselineFP = 1000, flagshipVariantId = "astd_zl_101_Standard", liquidationDelta = 0f),
            ),
            rewardMin = 500_000,
            rewardMax = 2_500_000,
            requiresGroups = listOf(GROUP_CH2_XC, GROUP_CH2_ZW),
            fleetFaction = Factions.REMNANTS,
            liquidationDelta = 0.6f,
        ),
        WorkOrder(
            key = KEY_ZX_0344,
            serial = "ZX-c207-0344／核销-41",
            chapter = 3,
            groupId = GROUP_CH3,
            threatTier = 5,
            // TODO：选型随 P3/P4/P7 舰船池联调（第七型自动巡逻编队）
            stages = listOf(Stage(baselineFP = 1200, flagshipVariantId = "astd_xc_002_Standard")),
            rewardMin = 500_000,
            rewardMax = 2_500_000,
            requiresGroups = listOf(GROUP_CH2_XC, GROUP_CH2_ZW),
            requiresOrders = listOf(KEY_ZX_1001),
            fleetFaction = Factions.REMNANTS,
            // 反常跳动 -0.5%：遗存目标被批量重新登记，清单变长稀释完成率（09 文档口径）
            liquidationDelta = -0.5f,
        ),
        WorkOrder(
            key = KEY_ZX_0002,
            serial = "ZX-c208-0002／清除-02",
            chapter = 3,
            groupId = GROUP_CH3,
            threatTier = 6,
            // TODO：选型随 P3/P4/P7 舰船池联调（成建封存编队，R 型首秀）
            // 条款①S-04 护盾极化、②S-06 峰值维续、③M-13 编队协同、④R-15 电网深化升级（10 文档单 3；
            // 固定表已含 R-15，覆盖 minRAffixes=1 的「固定至少 1 条 R」口径）
            stages = listOf(
                Stage(
                    baselineFP = 1500,
                    flagshipVariantId = "astd_xc_001_Standard",
                    fixedAffixIds = listOf(
                        AffixRegistry.ID_POLARIZED_SHIELD,
                        AffixRegistry.ID_DIMENSIONAL_SPECIALTY,
                        AffixRegistry.ID_SWARM_COORDINATION,
                        AffixRegistry.ID_GRID_DEEPENING,
                    ),
                ),
            ),
            rewardMin = 500_000,
            rewardMax = 2_500_000,
            requiresGroups = listOf(GROUP_CH2_XC, GROUP_CH2_ZW),
            requiresOrders = listOf(KEY_ZX_0344),
            allowRAffixes = true,
            minRAffixes = 1,
            clauseCount = 4,
            fleetFaction = Factions.REMNANTS,
            liquidationDelta = 1.4f,
        ),

        // 四章「最后一份文书」：ZQ 单工单三阶段（1800/2200/2800），等级从缺
        WorkOrder(
            key = KEY_ZQ_0001,
            serial = "ZQ-c208-0001／清除-00〔战斗群保障条例专类〕",
            chapter = 4,
            groupId = GROUP_CH4,
            threatTier = 6,
            dangerOmitted = true,
            stages = listOf(
                // TODO：选型随 P3/P4/P7 舰船池联调（编号战斗群屏卫/火力线/中军）
                // 条款①S-01 装甲强化 + S-02 辐能调度网络、②M-09 递归式目标定位 + M-13 编队协同（12 文档）；
                // 条款③R-16 激进式集群作战网络为中军（阶段三）编队级改装，中军旗舰 R-17 走 flagshipAffixIds
                Stage(
                    baselineFP = 1800,
                    flagshipVariantId = "astd_xc_102_Standard",
                    fixedAffixIds = listOf(
                        AffixRegistry.ID_IRONCLAD_PLATING,
                        AffixRegistry.ID_CRYO_FLUX_NETWORK,
                        AffixRegistry.ID_RECURSIVE_TARGETING,
                        AffixRegistry.ID_SWARM_COORDINATION,
                    ),
                    liquidationDelta = 0.3f,
                    stageReceipt = true,
                ),
                Stage(
                    baselineFP = 2200,
                    flagshipVariantId = "astd_xc_001_Standard",
                    fixedAffixIds = listOf(
                        AffixRegistry.ID_IRONCLAD_PLATING,
                        AffixRegistry.ID_CRYO_FLUX_NETWORK,
                        AffixRegistry.ID_RECURSIVE_TARGETING,
                        AffixRegistry.ID_SWARM_COORDINATION,
                    ),
                    liquidationDelta = 0.5f,
                    stageReceipt = true,
                ),
                Stage(
                    baselineFP = 2800,
                    flagshipVariantId = "astd_zl_001_Standard",
                    flagshipAffixIds = listOf(AffixRegistry.ID_SINGULARITY_DRIVE),
                    fixedAffixIds = listOf(
                        AffixRegistry.ID_IRONCLAD_PLATING,
                        AffixRegistry.ID_CRYO_FLUX_NETWORK,
                        AffixRegistry.ID_RECURSIVE_TARGETING,
                        AffixRegistry.ID_SWARM_COORDINATION,
                        AffixRegistry.ID_AGGRESSIVE_SWARM_NETWORK,
                    ),
                    liquidationDelta = 0.4f,
                    stageReceipt = true,
                ),
            ),
            rewardMin = 750_000,
            rewardMax = 3_750_000,
            requiresGroups = listOf(GROUP_CH3),
            allowRAffixes = true,
            clauseCount = 4,
            fleetFaction = Factions.REMNANTS,
        ),
    )

    private val byKey: Map<String, WorkOrder> = all.associateBy { it.key }
    private val groupById: Map<String, ClearGroup> = groups.associateBy { it.id }

    /** 按 bounty key 查主线条目；非主线（动态赏金）返回 null。 */
    fun byKey(key: String): WorkOrder? = byKey[key]

    fun group(id: String): ClearGroup? = groupById[id]

    /** 组内全部工单（注册表声明顺序）。 */
    fun ordersOfGroup(groupId: String): List<WorkOrder> = all.filter { it.groupId == groupId }

    /** 章节内全部结清组。 */
    fun groupsOfChapter(chapter: Int): List<ClearGroup> = groups.filter { it.chapter == chapter }
}
