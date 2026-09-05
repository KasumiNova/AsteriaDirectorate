package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.InfiniteBounties
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.story.BountyTerminalDataSource
import cn.kasuminova.astd.campaign.story.EndingKeys
import cn.kasuminova.astd.campaign.story.EndingRuntimeScript
import cn.kasuminova.astd.campaign.story.EndingState
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import com.fs.starfarer.api.Global

/**
 * 从干净基准存档逐单执行实际战斗与交付，不写入胜利集合或跳过章节。
 * 单张工单可以单独运行 campaign_bounty_battle；本场景是发布前较长的全线回归。
 */
class CampaignMainlineChecks(private val run: CampaignRun) : CampaignCheck {
    private var index = 0
    private var current: CampaignBountyChecks? = null
    private var finished = false
    private val terminal = BountyTerminalDataSource()

    override fun advance(amount: Float): Boolean {
        if (finished) return true
        val sector = checkNotNull(Global.getSector())
        sector.isPaused = false
        if (index < MainBounties.defs.size) {
            val def = MainBounties.defs[index]
            if (current == null) {
                run.detail("mainline.currentBounty", def.key)
                run.detail("mainline.completedCount", index)
                current = CampaignBountyChecks(run, def.key)
            }
            if (!checkNotNull(current).advance(amount)) return false
            run.detail("mainline.${def.key}", "Real battle, MagicBounty victory and ASTD settlement verified")
            index++
            current = null
            val state = BountyState.getOrCreate()
            if (MainBounties.chapterMembers.getValue(def.chapter).all { it in state.succeededBountyKeys }) {
                val key = when (def.chapter) {
                    0 -> "prologue"
                    1 -> "chapterOne"
                    2 -> "chapterTwo"
                    3 -> "chapterThree"
                    4 -> "chapterFour"
                    else -> throw CampaignCheckFailure("chapter", "Unknown chapter ${def.chapter}")
                }
                run.check(key, def.chapter in state.completedChapters, "All tickets settled and production chapter completion observed")
                if (def.chapter == 1) {
                    run.check("mainline.ruinUnlock", sector.getEntityById(StoryWorldIds.STARFALL_STAR) != null &&
                        sector.getEntityById(StoryWorldIds.ASTER_STAR) != null, "Chapter one settlement generated both ruin systems")
                }
            }
            return false
        }
        run.stage("mainline.archive")
        val state = BountyState.getOrCreate()
        run.check("mainline.allSettled", MainBounties.defs.all { it.key in state.succeededBountyKeys }, "All mainline bounty keys settled")
        run.check("mainline.archiveAvailable", state.archivalPending, "Final settlement offers archival choice")
        val choice = System.getProperty("astd.campaignAutomation.archiveChoice", "archive.public")
        run.check("archiveChoice", terminal.chooseEnding(choice) && !state.archivalPending && state.infiniteContractor,
            "Production archival decision accepted: $choice")
        run.check("mainline.noSecondArchive", !terminal.chooseEnding(choice), "Repeated archival selection rejected")
        val core = System.getProperty("astd.campaignAutomation.coreType", EndingKeys.CORE_TYPE_COMBAT)
        require(core in setOf(EndingKeys.CORE_TYPE_COMBAT, EndingKeys.CORE_TYPE_ADMIN))
        run.check("executiveCore", terminal.chooseEnding("core.$core") && EndingState.getOrCreate().executiveCoreType == core &&
            state.executiveCoreIssued, "Production executive selection accepted: $core")
        run.check("mainline.noSecondCore", !terminal.chooseEnding("core.$core"), "Repeated executive issuance rejected")
        EndingRuntimeScript().advance(1.1f)
        InfiniteBounties.ensureAvailable()
        val infinite = InfiniteBounties.definitions()
        run.check("infiniteAvailable", infinite.size == InfiniteBounties.SLOT_COUNT &&
            infinite.map { it.key }.distinct().size == infinite.size &&
            terminal.snapshot().batches.any { it.id == "infinite" && it.orders.size == InfiniteBounties.SLOT_COUNT },
            "Three generated infinite contracts appear in actual terminal snapshot")
        run.detail("mainline.infiniteKeys", infinite.joinToString { it.key })
        finished = true
        return true
    }
}
