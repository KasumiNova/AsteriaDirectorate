package cn.kasuminova.astd.campaign.bounty.core

import com.fs.starfarer.api.campaign.CampaignFleetAPI

interface BountyFleetTuner {

    fun isAstdBountyFleet(fleet: CampaignFleetAPI): Boolean

    fun tuneIfNeeded(fleet: CampaignFleetAPI)
}
