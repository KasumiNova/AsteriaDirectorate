package cn.kasuminova.astd.campaign.bounty.core

import com.fs.starfarer.api.campaign.CampaignFleetAPI

/**
 * MagicBounty 赏金舰队后处理器。
 *
 * MagicBounty 数据侧只支持“旗舰 preset + 舰队势力随机池 + 静态 min_FP”，
 * 其余在舰队生成后由本层完成：排除舰清退、按原版最大舰队规模做 ASTD/余晖 1:1 混编补齐、
 * ASTD 占比兜底、制式核心军官分配与装舰核心表落盘、SMod 内插。
 * 处理幂等：以舰队 memory 标记，单支舰队只处理一次。
 */
interface BountyFleetTuner {

    /** 是否为本模组登记的 MagicBounty 赏金舰队（bounty key 带 astd_bounty_ 前缀）。 */
    fun isAstdBountyFleet(fleet: CampaignFleetAPI): Boolean

    /** 未处理过则执行完整后处理（排除舰清退 → 混编补齐 → 占比兜底 → 核心军官 → SMod 内插）。 */
    fun tuneIfNeeded(fleet: CampaignFleetAPI)
}
