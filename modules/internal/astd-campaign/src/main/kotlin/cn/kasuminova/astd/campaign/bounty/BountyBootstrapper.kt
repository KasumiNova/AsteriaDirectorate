package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.ending.EndingCampaignManager
import cn.kasuminova.astd.campaign.ending.ExecutorCampaignPlugin
import cn.kasuminova.astd.campaign.ending.ExecutorCoreCustodyListener
import com.fs.starfarer.api.Global

/**
 * 在 ModPlugin 生命周期中注册赏金管理脚本。
 */
object BountyBootstrapper {

    /** Sector memory key：结局管理脚本注册去重（口径同 [BountyKeys.MEMORY_MANAGER_ADDED]）。 */
    private const val MEMORY_ENDING_MANAGER_ADDED: String = "\$astd_ending_manager_added"

    /** Sector memory key：执行官核心保管监听注册去重。 */
    private const val MEMORY_CUSTODY_LISTENER_ADDED: String = "\$astd_executor_custody_added"

    /** Sector memory key：执行官战役插件注册去重（AI 核心军官/行政官分发链接入）。 */
    private const val MEMORY_CAMPAIGN_PLUGIN_ADDED: String = "\$astd_executor_plugin_added"

    @JvmStatic
    fun onGameLoad() {
        val sector = Global.getSector() ?: return
        val mem = sector.memoryWithoutUpdate
        if (!mem.getBoolean(BountyKeys.MEMORY_MANAGER_ADDED)) {
            sector.addScript(BountyCampaignManager())
            mem.set(BountyKeys.MEMORY_MANAGER_ADDED, true)
        }
        // 第五章「归档」结局管理脚本（延迟条目激活 + 强度修正幂等重挂）
        if (!mem.getBoolean(MEMORY_ENDING_MANAGER_ADDED)) {
            sector.addScript(EndingCampaignManager())
            mem.set(MEMORY_ENDING_MANAGER_ADDED, true)
        }
        // 「执行官」backing commodity 保管监听（出售撤销）
        if (!mem.getBoolean(MEMORY_CUSTODY_LISTENER_ADDED)) {
            sector.listenerManager.addListener(ExecutorCoreCustodyListener())
            mem.set(MEMORY_CUSTODY_LISTENER_ADDED, true)
        }
        // 「执行官」战役插件（pickAICoreOfficerPlugin/pickAICoreAdminPlugin 分发链接入）
        if (!mem.getBoolean(MEMORY_CAMPAIGN_PLUGIN_ADDED)) {
            sector.registerPlugin(ExecutorCampaignPlugin())
            mem.set(MEMORY_CAMPAIGN_PLUGIN_ADDED, true)
        }
    }
}
