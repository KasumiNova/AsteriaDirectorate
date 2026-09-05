package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.campaign.ui.DirectorateTerminalBackends
import com.fs.starfarer.api.Global

/**
 * 剧情运行时接线入口（由 ModPlugin.onGameLoad 调用；新档/读档同路径）。
 *
 * - 安装分局终端真实数据源（[BountyTerminalDataSource]）到 UI 侧 holder；
 * - 注册剧情运行时脚本（[StoryRuntimeScript]，经 sector memory key 去重）。
 */
object StoryBootstrap {

    @JvmStatic
    fun onGameLoad() {
        val sector = Global.getSector() ?: return

        // 数据源实例无状态，每次加载重装（覆盖默认的 persistentData 读取实现）。
        DirectorateTerminalBackends.install(BountyTerminalDataSource())
        sector.registerPlugin(StorySiteCampaignPlugin())
        EndingRuntimeScript.ensureAdded(sector)

        val mem = sector.memoryWithoutUpdate
        if (mem.getBoolean(BountyKeys.MEMORY_STORY_RUNTIME_ADDED)) return
        sector.addScript(StoryRuntimeScript())
        mem.set(BountyKeys.MEMORY_STORY_RUNTIME_ADDED, true)
    }
}
