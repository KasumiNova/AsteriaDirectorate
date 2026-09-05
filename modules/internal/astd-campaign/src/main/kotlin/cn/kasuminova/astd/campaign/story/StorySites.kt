package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.world.StoryWorldIds

/**
 * 第二章遗址实地交互：赏金目标与遗址实体的对照表 + 实地回收规则（docs/story/07）。
 *
 * 职责边界：
 * - 本表只做「bountyKey ↔ 遗址实体」的静态映射与交互门槛/回收规则的纯逻辑判定，
 *   不触碰 MagicBounty 注册、locationId 选定与舰队放置（装配侧负责把 [targetId]
 *   填进 MagicBounty 的 location 并放置舰队）；
 * - 站点交互对话见 [StorySiteDialog]，实体匹配入口见 [StorySiteCampaignPlugin]；
 * - 任务资产（设计原型 / 数据核心）为托管记录，状态见 [StoryCargo]。
 *
 * 目标映射口径（07 文档「三单递进：外围警戒平台 → 内环防御群 → 试验场核心库」，
 * 按站点轨道由外向内递进）：
 * - 星坠 xc_1 外围警戒平台 → 附属站（轨道最外）；xc_2 内环防御群 → 动力船坞；
 *   xc_3 试验场核心库 → 主空间站；
 * - 紫菀 s1~s3 → 引力节点 Ⅰ/Ⅱ/Ⅲ（等边三角形阵地）；s4 核心数据舱 → 主空间站。
 */
object StorySites {

    /** 站点交互门槛：无 / 核心需全部节点破除（不可强闯）/ 节点按 s1→s2→s3 依序破除。 */
    enum class GateMode { NONE, ALL_BREACHED, IN_ORDER }

    /**
     * 遗址目标定义。
     *
     * @property bountyKey 主线赏金 key（[MainBounties] 定义表内）
     * @property targetEntityId 遗址实体 id（[StoryWorldIds]）
     * @property textKey i18n 文本键基干（实际键 = `site.<textKey>.<状态后缀>`，见 [StorySiteDialog]）
     * @property requiresAsset 胜利后须实地回收托管资产才可核销（星坠 c2_xc_3 / 紫菀 c2_zw_s4）
     * @property gateMode 交互门槛
     * @property gateKeys 门槛关联的前序 bountyKey（核心 = 三座节点；节点 = 全部前序节点）
     */
    data class SiteDef(
        val bountyKey: String,
        val targetEntityId: String,
        val textKey: String,
        val requiresAsset: Boolean = false,
        val gateMode: GateMode = GateMode.NONE,
        val gateKeys: List<String> = emptyList(),
    )

    const val KEY_C2_XC_1: String = "astd_main_c2_xc_1"
    const val KEY_C2_XC_2: String = "astd_main_c2_xc_2"
    const val KEY_C2_XC_3: String = "astd_main_c2_xc_3"
    const val KEY_C2_ZW_S1: String = "astd_main_c2_zw_s1"
    const val KEY_C2_ZW_S2: String = "astd_main_c2_zw_s2"
    const val KEY_C2_ZW_S3: String = "astd_main_c2_zw_s3"
    const val KEY_C2_ZW_S4: String = "astd_main_c2_zw_s4"

    private val ZW_NODE_KEYS: List<String> = listOf(KEY_C2_ZW_S1, KEY_C2_ZW_S2, KEY_C2_ZW_S3)

    /** 全部遗址赏金目标（星坠 3 单 + 紫菀合并单 4 阶段）。 */
    val sites: List<SiteDef> = listOf(
        SiteDef(KEY_C2_XC_1, StoryWorldIds.STARFALL_STATION_RESERVED, "xc1"),
        SiteDef(KEY_C2_XC_2, StoryWorldIds.STARFALL_STATION_DOCKYARD, "xc2"),
        SiteDef(KEY_C2_XC_3, StoryWorldIds.STARFALL_STATION_MAIN, "xc3", requiresAsset = true),
        SiteDef(KEY_C2_ZW_S1, StoryWorldIds.ASTER_GRAVITY_NODE_1, "zw_node1"),
        SiteDef(
            KEY_C2_ZW_S2, StoryWorldIds.ASTER_GRAVITY_NODE_2, "zw_node2",
            gateMode = GateMode.IN_ORDER, gateKeys = listOf(KEY_C2_ZW_S1),
        ),
        SiteDef(
            KEY_C2_ZW_S3, StoryWorldIds.ASTER_GRAVITY_NODE_3, "zw_node3",
            gateMode = GateMode.IN_ORDER, gateKeys = listOf(KEY_C2_ZW_S1, KEY_C2_ZW_S2),
        ),
        SiteDef(
            KEY_C2_ZW_S4, StoryWorldIds.ASTER_STATION_MAIN, "zw_core",
            requiresAsset = true,
            gateMode = GateMode.ALL_BREACHED, gateKeys = ZW_NODE_KEYS,
        ),
    )

    val sitesByBountyKey: Map<String, SiteDef> = sites.associateBy { it.bountyKey }
    val sitesByEntityId: Map<String, SiteDef> = sites.associateBy { it.targetEntityId }

    /**
     * 非赏金目标的剧情站：仅提供设定描述文本（07 文档基调守则——
     * 遗址站不允许落到「无聊普通市场」默认交互）。
     */
    val flavorEntityTextKeys: Map<String, String> = mapOf(
        StoryWorldIds.MAIN_STATION_RESERVED to "flavor.main_reserved",
        StoryWorldIds.MAIN_STATION_DOCKYARD to "flavor.main_dockyard",
        StoryWorldIds.ASTER_STATION_GRAVITY_DOCKYARD to "flavor.aster_gravity_dockyard",
        StoryWorldIds.ASTER_STATION_SINGULARITY to "flavor.aster_singularity",
        StoryWorldIds.ASTER_STATION_FORCEFIELD_RESERVED to "flavor.aster_forcefield",
        StoryWorldIds.ASTER_STATION_SHIGUANG to "flavor.aster_shiguang",
    )

    /** 赏金目标实体 id（装配侧：MagicBounty locationId 选定与舰队放置用）。 */
    fun targetId(bountyKey: String): String? = sitesByBountyKey[bountyKey]?.targetEntityId

    fun siteForEntity(entityId: String): SiteDef? = sitesByEntityId[entityId]

    /** 该实体是否由本模组接管交互（赏金目标站或纯描述剧情站）。 */
    fun isStorySiteEntity(entityId: String): Boolean =
        entityId in sitesByEntityId || entityId in flavorEntityTextKeys

    fun requiresAsset(bountyKey: String): Boolean = sitesByBountyKey[bountyKey]?.requiresAsset == true

    /**
     * 站点交互状态（决策结果为纯逻辑，供单元测试直接驱动）。
     *
     * - [LOCKED]：工单未接取（未解锁），仅出示设定描述；
     * - [ORDER_LOCKED]：节点工单未接取且前序节点未破除，引导依序拆除；
     * - [REPULSED]：核心在三节点未全部破除时被无形力场排斥（不可强闯）；
     * - [ENGAGE]：工单已接取，可发起战斗；
     * - [RECOVER]：战斗已赢下且资产单尚未回收，可实地回收托管资产；
     * - [AWAIT_HANDIN]：战斗已赢下（资产单已回收），回分局终端交付核销；
     * - [DONE]：已核销，仅余现场描述。
     */
    enum class SiteInteraction { LOCKED, ORDER_LOCKED, REPULSED, ENGAGE, RECOVER, AWAIT_HANDIN, DONE }

    /**
     * 交互状态判定（纯逻辑）。
     *
     * @param accepted 工单已接取（MagicBounty ActiveBounty 处于 Accepted）
     * @param defeated 战斗已赢下待交付（事实来源：BountyState.defeatedBountyKeys）
     * @param succeeded 已核销（事实来源：BountyState.succeededBountyKeys）
     * @param hasAsset 托管资产已回收（[StoryCargo.hasAsset]）
     * @param breachedKeys 已破除的前序门槛 bountyKey（战斗赢下或已核销均计入）
     */
    fun resolveInteraction(
        site: SiteDef,
        accepted: Boolean,
        defeated: Boolean,
        succeeded: Boolean,
        hasAsset: Boolean,
        breachedKeys: Set<String>,
    ): SiteInteraction = when {
        succeeded -> SiteInteraction.DONE
        defeated -> if (site.requiresAsset && !hasAsset) SiteInteraction.RECOVER else SiteInteraction.AWAIT_HANDIN
        accepted -> SiteInteraction.ENGAGE
        site.gateMode == GateMode.ALL_BREACHED && !breachedKeys.containsAll(site.gateKeys) -> SiteInteraction.REPULSED
        site.gateMode == GateMode.IN_ORDER && !breachedKeys.containsAll(site.gateKeys) -> SiteInteraction.ORDER_LOCKED
        else -> SiteInteraction.LOCKED
    }
}
