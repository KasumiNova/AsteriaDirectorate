package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.dialog.story.StorySiteDialog
import cn.kasuminova.astd.campaign.dialog.story.StorySiteDialog.SiteOrderState
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.campaign.SectorEntityToken
import org.apache.log4j.Logger

/**
 * 剧情站点交互注册表（doc 05/07/09/11 与 [StoryWorldIds] 实体清单对齐）。
 *
 * 覆盖：
 * - 星坠遗址星系站点（武器试验场遗留工单 XC 线目标站，doc 07）；
 * - 紫菀遗址星系站点（科研遗址遗留工单 ZW 线目标站；核心数据舱在引力节点未拔全时
 *   仍由 CoreVaultRepelDialogPlugin 排斥，拔全后落到本站交互）；
 * - 主星系预留站（doc 03：本体阶段仅提供描述文本）；
 * - 引力节点 ×3（生涯可交互单位，纯描述）。
 * 三章溯源链（doc 09）目标为遗存舰队、无新站点，不产生新条目。
 *
 * 每个站点：三态文本（未接单/执行中/结清后）+ 可选「回收托管资产」交互
 * （[custody]：物品 id → 来源工单；工单最终阶段击毁后可在本站回收）。
 */
object StorySites {

    private val log: Logger = Global.getLogger(StorySites::class.java)

    /**
     * 站点定义。
     *
     * @property entityId 实体 id（[StoryWorldIds]）
     * @property siteKey i18n 键段（strings.json `story.site.<siteKey>.*`）
     * @property orderKeys 三态判定关联的主线工单
     * @property custody 托管资产（物品 id → 来源工单 key；工单待核销且玩家未持有时可回收）
     */
    data class SiteDef(
        val entityId: String,
        val siteKey: String,
        val orderKeys: List<String> = emptyList(),
        val custody: Map<String, String> = emptyMap(),
    )

    private val XC_ORDERS = listOf(MainBounties.KEY_XC_0216, MainBounties.KEY_XC_0217, MainBounties.KEY_XC_0221)
    private val ZW_ORDERS = listOf(MainBounties.KEY_ZW_0309)

    /** 全部站点（实体 id 索引）。 */
    val all: Map<String, SiteDef> = listOf(
        // ─── 星坠遗址星系（武器试验场，XC 线） ───
        SiteDef(
            StoryWorldIds.STARFALL_STATION_MAIN, "starfall_main",
            orderKeys = XC_ORDERS,
            custody = mapOf(StoryQuestItems.DESIGN_PROTOTYPE to MainBounties.KEY_XC_0221),
        ),
        SiteDef(StoryWorldIds.STARFALL_STATION_DOCKYARD, "starfall_dockyard", orderKeys = XC_ORDERS),
        SiteDef(StoryWorldIds.STARFALL_STATION_RESERVE, "starfall_reserve"),

        // ─── 紫菀遗址星系（科研遗址，ZW 线） ───
        SiteDef(StoryWorldIds.ASTER_STATION_MAIN, "aster_main", orderKeys = ZW_ORDERS),
        SiteDef(StoryWorldIds.ASTER_STATION_DOCKYARD, "aster_dockyard", orderKeys = ZW_ORDERS),
        SiteDef(StoryWorldIds.ASTER_STATION_SINGULARITY, "aster_singularity"),
        SiteDef(StoryWorldIds.ASTER_STATION_DEFENSE, "aster_defense"),
        SiteDef(
            StoryWorldIds.ASTER_CORE_VAULT, "aster_core_vault",
            orderKeys = ZW_ORDERS,
            custody = mapOf(StoryQuestItems.DATA_CORE to MainBounties.KEY_ZW_0309),
        ),
        SiteDef(StoryWorldIds.ASTER_NODE_1, "aster_node_1", orderKeys = ZW_ORDERS),
        SiteDef(StoryWorldIds.ASTER_NODE_2, "aster_node_2", orderKeys = ZW_ORDERS),
        SiteDef(StoryWorldIds.ASTER_NODE_3, "aster_node_3", orderKeys = ZW_ORDERS),

        // ─── 主星系预留站（纯描述，doc 03） ───
        SiteDef(StoryWorldIds.MAIN_STATION_RESERVE_A, "main_reserve_a"),
        SiteDef(StoryWorldIds.MAIN_STATION_RESERVE_B, "main_reserve_b"),
    ).associateBy { it.entityId }

    /** 该实体是否为已登记的剧情站点。 */
    fun isStorySite(entityId: String): Boolean = entityId in all

    /** 为该实体构建交互插件；未登记返回 null（调用方走默认交互）。 */
    fun createPlugin(entity: SectorEntityToken): InteractionDialogPlugin? {
        val def = all[entity.id] ?: return null
        return StorySiteDialog.createPlugin(def.siteKey, SiteBackendImpl(def))
    }

    /** 站点三态判定：全部核销 → SETTLED；任一挂出/待核销（或部分核销）→ ACTIVE；否则 NONE。 */
    fun orderStateOf(def: SiteDef, state: BountyState): SiteOrderState {
        if (def.orderKeys.isEmpty()) return SiteOrderState.NONE
        if (def.orderKeys.all { it in state.settledWorkOrders }) return SiteOrderState.SETTLED
        val anyProgress = def.orderKeys.any {
            it in state.postedWorkOrders || it in state.destroyedWorkOrders || it in state.settledWorkOrders
        }
        return if (anyProgress) SiteOrderState.ACTIVE else SiteOrderState.NONE
    }

    private class SiteBackendImpl(private val def: SiteDef) : StorySiteDialog.StorySiteBackend {

        override fun orderState(): SiteOrderState = orderStateOf(def, BountyState.getOrCreate())

        override fun recoverableAssets(): List<String> {
            val state = BountyState.getOrCreate()
            return def.custody
                .filter { (itemId, orderKey) ->
                    // 工单已击毁待核销（最终阶段）且玩家尚未持有交割物
                    orderKey in state.destroyedWorkOrders && !StoryQuestItems.playerHas(itemId)
                }
                .keys
                .toList()
        }

        override fun recoverAsset(itemId: String): Boolean {
            if (itemId !in recoverableAssets()) {
                log.warn("[ASTD] 托管资产回收被拒绝：${def.entityId} 当前不可回收 $itemId")
                return false
            }
            StoryQuestItems.grantToPlayer(itemId)
            return true
        }
    }
}
