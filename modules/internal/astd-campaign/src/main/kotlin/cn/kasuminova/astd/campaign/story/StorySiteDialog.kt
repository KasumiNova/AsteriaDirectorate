package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyFidConfigGen
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl
import com.fs.starfarer.api.util.Misc
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator

/**
 * 第二章遗址实地交互对话（docs/story/07）：赏金目标站与纯描述剧情站的交互插件工厂。
 *
 * 状态机见 [StorySites.resolveInteraction]（纯逻辑）；本类负责：
 * - 读取游戏侧事实（MagicBounty 接取阶段 / [BountyState] 核销与待交付 / [StoryCargo] 托管资产）；
 * - 接取后「发起攻击」：就地换插件拉起当前活动赏金的原版 [FleetInteractionDialogPluginImpl]
 *   （配置由 [BountyFidConfigGen] 提供，舰队由装配侧放置并已重建）；
 * - 资产单（星坠 c2_xc_3 / 紫菀 c2_zw_s4）胜利后再交互「回收托管资产」，登记 [StoryCargo]
 *   并以原版 HUD 提示托管规则（不可交易/存放/丢弃，仅可交付）；
 * - 未解锁/顺序门槛/核心排斥一律给设定描述文本，不提供强闯入口。
 */
object StorySiteDialog {

    /** 遗址交互文案的 I18n category（contents/data/strings/storysite_strings.json）。 */
    const val I18N_CATEGORY: String = "asteria_directorate_storysite"

    const val OPT_ENGAGE: String = "engage"
    const val OPT_COLLECT: String = "collect"
    const val OPT_LEAVE: String = "leave"

    private const val NODE_ROOT: String = "root"
    private const val NODE_COLLECTED: String = "collected"

    private const val SESSION_STATE: String = "astd.storysite.state"

    private val log: Logger by lazy { Global.getLogger(StorySiteDialog::class.java) }

    /** 为遗址实体创建交互对话插件（目标站与描述站共用入口）。 */
    fun createPlugin(entity: SectorEntityToken): GraphDialogPlugin {
        val site = StorySites.siteForEntity(entity.id)
        val graph = if (site != null) targetGraph(site) else flavorGraph(entity.id)
        return GraphDialogPlugin(graph, OPT_LEAVE, I18n[I18N_CATEGORY, "site.option.leave"])
    }

    // ------------------------------------------------------------------
    // 赏金目标站
    // ------------------------------------------------------------------

    private fun targetGraph(site: StorySites.SiteDef): DialogGraph = dialogGraph(start = NODE_ROOT) {
        node(
            NODE_ROOT,
            DialogDsl.node(
                onEnter = { ctx ->
                    val state = resolveFor(site)
                    ctx.sessionState[SESSION_STATE] = state
                    sayLines(ctx, lineKey(site, state), varsFor(site, state))
                },
                options = { ctx ->
                    val state = ctx.sessionState[SESSION_STATE] as? StorySites.SiteInteraction
                        ?: resolveFor(site)
                    buildList {
                        when (state) {
                            StorySites.SiteInteraction.ENGAGE -> add(
                                DialogDsl.option(
                                    OPT_ENGAGE,
                                    I18n[I18N_CATEGORY, "site.option.engage"],
                                    DialogDsl.run { c -> engage(c, site) },
                                )
                            )
                            StorySites.SiteInteraction.RECOVER -> add(
                                DialogDsl.option(
                                    OPT_COLLECT,
                                    I18n[I18N_CATEGORY, "site.option.collect"],
                                    DialogDsl.run { c -> collect(c, site) },
                                )
                            )
                            else -> {}
                        }
                        add(
                            DialogDsl.option(
                                OPT_LEAVE,
                                I18n[I18N_CATEGORY, "site.option.leave"],
                                DialogDsl.close(),
                            )
                        )
                    }
                },
            ),
        )
        node(
            NODE_COLLECTED,
            DialogDsl.node(
                onEnter = { ctx -> sayLines(ctx, "site.${site.textKey}.collected") },
                options = {
                    listOf(
                        DialogDsl.option(
                            OPT_LEAVE,
                            I18n[I18N_CATEGORY, "site.option.leave"],
                            DialogDsl.close(),
                        )
                    )
                },
            ),
        )
    }

    /** 交互状态 → i18n 文本键后缀。 */
    private fun lineKey(site: StorySites.SiteDef, state: StorySites.SiteInteraction): String {
        val suffix = when (state) {
            StorySites.SiteInteraction.LOCKED -> "locked"
            StorySites.SiteInteraction.ORDER_LOCKED -> "order_locked"
            StorySites.SiteInteraction.REPULSED -> "repulsed"
            StorySites.SiteInteraction.ENGAGE -> "approach"
            StorySites.SiteInteraction.RECOVER -> "recover"
            StorySites.SiteInteraction.AWAIT_HANDIN -> "await"
            StorySites.SiteInteraction.DONE -> "done"
        }
        return "site.${site.textKey}.$suffix"
    }

    /** 排斥文本携带剩余节点数（「引力锚定设施仍有 %remaining% 座在阵」）。 */
    private fun varsFor(site: StorySites.SiteDef, state: StorySites.SiteInteraction): Array<Pair<String, Any?>> {
        if (state != StorySites.SiteInteraction.REPULSED) return emptyArray()
        val bountyState = BountyState.getOrCreate()
        val remaining = site.gateKeys.count { !isBreached(bountyState, it) }
        return arrayOf("remaining" to remaining)
    }

    /** 读取游戏侧事实并判定交互状态。 */
    private fun resolveFor(site: StorySites.SiteDef): StorySites.SiteInteraction {
        val bountyState = BountyState.getOrCreate()
        val key = site.bountyKey
        val succeeded = key in bountyState.succeededBountyKeys
        val defeated = isDefeated(bountyState, key)
        val accepted = activeBountyAccepted(key)
        val hasAsset = StoryCargo.getOrCreate().hasAsset(key)
        val breachedKeys = site.gateKeys.filterTo(LinkedHashSet()) { isBreached(bountyState, it) }
        return StorySites.resolveInteraction(site, accepted, defeated, succeeded, hasAsset, breachedKeys)
    }

    /** 战斗赢下待交付判定（事实来源：BountyState.defeatedBountyKeys，由装配侧补齐字段）。 */
    private fun isDefeated(state: BountyState, key: String): Boolean = key in state.defeatedBountyKeys

    /** 节点破除判定：战斗赢下或已核销均视为已破除。 */
    private fun isBreached(state: BountyState, key: String): Boolean =
        key in state.succeededBountyKeys || isDefeated(state, key)

    private fun activeBountyAccepted(key: String): Boolean = try {
        MagicBountyCoordinator.getInstance().getActiveBounty(key)?.stage == ActiveBounty.Stage.Accepted
    } catch (t: Throwable) {
        log.warn("[StorySiteDialog] 查询活动赏金失败（$key）：${t.message}")
        false
    }

    /**
     * 「发起攻击」：就地换插件拉起当前活动赏金的原版舰队遭遇对话。
     *
     * 采用原版守卫交互的既有换插件方式（dialog.setPlugin + init，见原版
     * SalvageDefenderInteraction）：交互目标切到赏金舰队后交给
     * [FleetInteractionDialogPluginImpl]，离开即整窗关闭（dismissOnLeave 默认），
     * MagicBounty 的战斗监听不受影响。
     */
    private fun engage(ctx: DialogContext, site: StorySites.SiteDef) {
        val fleet = try {
            MagicBountyCoordinator.getInstance().getActiveBounty(site.bountyKey)?.fleet
        } catch (t: Throwable) {
            log.warn("[StorySiteDialog] 拉取活动赏金失败（${site.bountyKey}）：${t.message}")
            null
        }
        if (fleet == null) {
            log.warn("[StorySiteDialog] 工单 ${site.bountyKey} 已接取但活动舰队不存在，无法拉起遭遇（实体 ${site.targetEntityId}）")
            return
        }

        val dialog = ctx.dialog
        val plugin = FleetInteractionDialogPluginImpl(BountyFidConfigGen(site.bountyKey).createConfig())
        dialog.interactionTarget = fleet
        dialog.setPlugin(plugin)
        plugin.init(dialog)
    }

    /** 「回收托管资产」：登记 [StoryCargo] 并以原版 HUD 提示托管规则，随后展示回收确认文本。 */
    private fun collect(ctx: DialogContext, site: StorySites.SiteDef) {
        if (!StoryCargo.getOrCreate().collect(site.bountyKey)) return
        hudAssetCollected(site)
        ctx.goto(NODE_COLLECTED)
    }

    // ------------------------------------------------------------------
    // 战后回收 callback（装配侧可选入口）
    // ------------------------------------------------------------------

    /**
     * 战后回收 callback：战斗赢下后由装配侧直接调用，等价于玩家回到遗址实体
     * 再交互选择「回收托管资产」（登记 [StoryCargo] + HUD 托管提示）。
     *
     * 仅受理「遗址资产单且战斗已赢下待交付、尚未回收」的情形；其余拒绝并记日志，
     * 绝不把未完成的胜利推进为已回收。
     *
     * @return 是否受理成功
     */
    @JvmStatic
    fun collectAfterVictory(bountyKey: String): Boolean {
        val site = StorySites.sitesByBountyKey[bountyKey]
        if (site == null || !site.requiresAsset) {
            log.warn("[StorySiteDialog] 战后回收拒绝：'$bountyKey' 不是遗址资产单")
            return false
        }
        val bountyState = BountyState.getOrCreate()
        if (!isDefeated(bountyState, bountyKey) || bountyKey in bountyState.succeededBountyKeys) {
            log.warn("[StorySiteDialog] 战后回收拒绝：'$bountyKey' 未处于「战斗赢下待交付」状态")
            return false
        }
        val cargo = StoryCargo.getOrCreate()
        if (!cargo.collect(bountyKey)) return false
        hudAssetCollected(site)
        return true
    }

    /** 托管资产登记后的原版 HUD 提示（对话回收与战后 callback 共用）。 */
    private fun hudAssetCollected(site: StorySites.SiteDef) {
        HudMessages.campaign(
            I18n.t(
                I18N_CATEGORY,
                "hud.asset.collected",
                "assetName" to I18n[I18N_CATEGORY, "asset.${site.textKey}.name"],
            ),
            Misc.getHighlightColor(),
        )
    }

    // ------------------------------------------------------------------
    // 纯描述剧情站
    // ------------------------------------------------------------------

    private fun flavorGraph(entityId: String): DialogGraph {
        val textKey = StorySites.flavorEntityTextKeys.getValue(entityId)
        return dialogGraph(start = NODE_ROOT) {
            node(
                NODE_ROOT,
                DialogDsl.node(
                    onEnter = { ctx -> sayLines(ctx, textKey) },
                    options = {
                        listOf(
                            DialogDsl.option(
                                OPT_LEAVE,
                                I18n[I18N_CATEGORY, "site.option.leave"],
                                DialogDsl.close(),
                            )
                        )
                    },
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 输出工具
    // ------------------------------------------------------------------

    /** 多行文本拆段输出；遗址文本全为环境旁白，统一灰显（07 文档基调守则）。 */
    private fun sayLines(ctx: DialogContext, key: String, vars: Array<Pair<String, Any?>> = emptyArray()) {
        val color = Misc.getGrayColor()
        I18n.t(I18N_CATEGORY, key, *vars)
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { ctx.say(it, color) }
    }
}
