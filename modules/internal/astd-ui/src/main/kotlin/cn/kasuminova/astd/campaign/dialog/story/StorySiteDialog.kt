package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 遗址站/剧情站点交互对话（doc 05/07 生涯单位交互；docs 09/11 无新对话，站点只承载三态文本与回收交互）。
 *
 * 结构：`describe`（timed：站点描写 + 工单状态文本，播完自动进 `menu`）→
 * `menu`（回收托管资产选项 ×N + 离开）。可从 Escape / 离开选项退出。
 *
 * 文案键族（category=asteria_directorate，strings.json）：
 * - `story.site.<siteKey>.desc`：站点常驻描写；
 * - `story.site.<siteKey>.state.none/active/settled`：未接单/执行中（含已击毁待核销）/结清后三态文本；
 * - `story.site.custody.*`：回收托管资产交互文本。
 *
 * 数据由 [StorySiteBackend] 注入（campaign 侧按实体 id 装配），本类不含任何生涯层引用。
 */
object StorySiteDialog {

    const val NODE_DESCRIBE: String = "describe"
    const val NODE_MENU: String = "menu"

    private const val OPT_RECOVER_PREFIX = "recover_"
    private const val OPT_LEAVE = "leave"

    /** sessionState：describe 自动跳转标记。 */
    private const val STATE_DESCRIBE_FIRED = "site.describe.fired"

    private val CAT = I18n.Categories.MOD

    /** 站点关联工单的三态。 */
    enum class SiteOrderState { NONE, ACTIVE, SETTLED }

    /**
     * 单个站点的交互数据后端（campaign 侧实现，按实体 id 构造注入）。
     */
    interface StorySiteBackend {
        /** 站点关联工单的当前状态（决定 state.* 文案段）。 */
        fun orderState(): SiteOrderState

        /** 当前可在此站回收的托管资产（特殊物品 id 列表；无则空表）。 */
        fun recoverableAssets(): List<String>

        /**
         * 执行回收：物品进玩家货舱并记日志。
         * @return 是否成功（失败时实现方已记日志，对话侧给出拒绝文案）
         */
        fun recoverAsset(itemId: String): Boolean
    }

    /** 构建站点交互插件。[siteKey] 为 i18n 键段（story.site.<siteKey>.*）。 */
    fun createPlugin(siteKey: String, backend: StorySiteBackend): GraphDialogPlugin =
        GraphDialogPlugin(
            graph = createGraph(siteKey, backend),
            closeOnEscapeOptionId = OPT_LEAVE,
            closeOnEscapeText = I18n[CAT, "dialog.core.leave"],
        )

    /** 构建站点对话图（测试可直接消费）。 */
    fun createGraph(siteKey: String, backend: StorySiteBackend): DialogGraph =
        dialogGraph(start = NODE_DESCRIBE) {
            node(
                NODE_DESCRIBE, DialogDsl.timedNode(
                    onEnter = { ctx ->
                        ctx.sessionState[STATE_DESCRIBE_FIRED] = false
                        ctx.enqueueI18nFading(CAT, "story.site.$siteKey.desc", 0.3f, fadeIn = 0.3f)
                        val stateKey = when (backend.orderState()) {
                            SiteOrderState.NONE -> "none"
                            SiteOrderState.ACTIVE -> "active"
                            SiteOrderState.SETTLED -> "settled"
                        }
                        ctx.enqueueI18n(CAT, "story.site.$siteKey.state.$stateKey", 0.8f)
                    },
                    onAdvance = { ctx, _ ->
                        if (!ctx.textQueue.hasPending && ctx.sessionState[STATE_DESCRIBE_FIRED] != true) {
                            ctx.sessionState[STATE_DESCRIBE_FIRED] = true
                            ctx.goto(NODE_MENU)
                        }
                    },
                )
            )
            node(NODE_MENU, DialogDsl.node { ctx ->
                val options = backend.recoverableAssets().map { itemId ->
                    DialogDsl.option(
                        id = OPT_RECOVER_PREFIX + itemId,
                        text = I18n.t(CAT, "story.site.custody.option", "itemName" to itemName(itemId)),
                        action = DialogDsl.run { c ->
                            if (backend.recoverAsset(itemId)) {
                                c.sayI18n(CAT, "story.site.custody.recovered", null, "itemName" to itemName(itemId))
                                c.hudMessageI18n(CAT, "story.item.grant.$itemId")
                            } else {
                                c.sayI18n(CAT, "story.site.custody.rejected")
                            }
                        },
                    )
                }
                options + DialogDsl.option(
                    id = OPT_LEAVE,
                    text = I18n[CAT, "dialog.core.leave"],
                    action = DialogDsl.close(),
                )
            })
        }

    /** 物品显示名（strings.json 键 story.item.name.<itemId>）。 */
    private fun itemName(itemId: String): String = I18n[CAT, "story.item.name.$itemId"]
}
