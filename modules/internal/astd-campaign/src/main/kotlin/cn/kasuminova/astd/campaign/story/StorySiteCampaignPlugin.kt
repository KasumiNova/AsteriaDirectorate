package cn.kasuminova.astd.campaign.story

import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.BaseCampaignPlugin
import com.fs.starfarer.api.campaign.CampaignPlugin
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.campaign.SectorEntityToken

/**
 * 剧情遗址实体的交互接管插件：按实体 ID 匹配，接管第二章遗址目标站与
 * 纯描述剧情站的交互对话（不动 rules.csv）。
 *
 * - 匹配 [StorySites.isStorySiteEntity] 的实体：以 [StorySiteDialog] 的
 *   Dialog DSL 插件应答（MOD_SPECIFIC 优先级，压过核心默认的市场/规则对话）；
 * - 其余实体一律交回后续插件链（舰队交互等原版行为不受影响，
 *   赏金舰队的 `$fidConifgGen` 配置保持生效）。
 *
 * 装配入口：由剧情运行时接线（StoryBootstrap.onGameLoad）调用
 * `Global.getSector().registerPlugin(StorySiteCampaignPlugin())`；
 * 本插件 isTransient（默认 true），不随存档序列化，每次加载重新注册。
 */
class StorySiteCampaignPlugin : BaseCampaignPlugin() {

    override fun getId(): String = PLUGIN_ID

    override fun pickInteractionDialogPlugin(
        interactionTarget: SectorEntityToken,
    ): PluginPick<InteractionDialogPlugin>? {
        if (StorySites.isStorySiteEntity(interactionTarget.id)) {
            return PluginPick(
                StorySiteDialog.createPlugin(interactionTarget),
                CampaignPlugin.PickPriority.MOD_SPECIFIC,
            )
        }
        return super.pickInteractionDialogPlugin(interactionTarget)
    }

    companion object {
        const val PLUGIN_ID: String = "astd_story_site"
    }
}
