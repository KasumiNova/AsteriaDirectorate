package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.Global

/**
 * 在 ModPlugin 生命周期中注册赏金管理脚本。
 */
object BountyBootstrapper {

    @JvmStatic
    fun onGameLoad() {
        val sector = Global.getSector() ?: return
        val mem = sector.memoryWithoutUpdate
        if (mem.getBoolean(BountyKeys.MEMORY_MANAGER_ADDED)) return

        sector.addScript(BountyCampaignManager())
        mem.set(BountyKeys.MEMORY_MANAGER_ADDED, true)
    }
}
