package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.InfiniteBountyState
import cn.kasuminova.astd.campaign.story.EndingKeys
import cn.kasuminova.astd.campaign.story.EndingState
import cn.kasuminova.astd.campaign.story.StoryCargo
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.impl.campaign.ids.Stats

/** 保存前读取业务事实，重新启动后逐项比对；不会仅凭保存过的 passed 标记宣布通过。 */
object CampaignSaveChecks {
    fun requireCleanFixture() {
        val sector = checkNotNull(Global.getSector())
        require(sector.persistentData[CampaignAutomationBootstrap.SNAPSHOT_KEY] == null) {
            "This fixture already contains an automation checkpoint; use the immutable baseline"
        }
        val bounty = BountyState.getOrCreate()
        require(bounty.succeededBountyKeys.isEmpty() && bounty.defeatedBountyKeys.isEmpty() &&
            bounty.completedChapters.isEmpty() && !bounty.infiniteContractor && bounty.archiveChoice.isEmpty()) {
            "Baseline must precede ASTD mainline progress; refusing to reset existing story state"
        }
    }

    fun capture(run: CampaignRun) {
        // 只用JDK容器与基本类型，release存档反序列化不会要求automation类。
        Global.getSector().persistentData[CampaignAutomationBootstrap.SNAPSHOT_KEY] = linkedMapOf(
            "runId" to run.runId,
            "scenario" to run.scenario,
            "process" to System.getProperty("astd.campaignAutomation.phase", "run"),
            "evidence" to LinkedHashMap(run.evidence),
            "details" to run.details.mapValuesTo(linkedMapOf()) { it.value.toString() },
            "facts" to facts(),
        )
    }

    fun verify(run: CampaignRun) {
        require(System.getProperty(CampaignAutomationBootstrap.PHASE_PROPERTY) == "reload")
        val saved = Global.getSector().persistentData[CampaignAutomationBootstrap.SNAPSHOT_KEY] as? Map<*, *>
            ?: throw CampaignCheckFailure("checkpoint_missing", "No saved business snapshot")
        run.check("checkpointIdentity", saved["runId"] == run.runId && saved["scenario"] == run.scenario &&
            saved["process"] == "run", "Save belongs to this run and the preceding process phase")
        val oldFacts = saved["facts"] as? Map<*, *>
            ?: throw CampaignCheckFailure("checkpoint_shape", "Missing facts map")
        val current = facts()
        val changes = (oldFacts.keys.map { it.toString() } + current.keys).toSortedSet().mapNotNull { key ->
            if (oldFacts[key] == current[key]) null else "$key: saved=${oldFacts[key]}, loaded=${current[key]}"
        }
        run.check("businessStateRestored", changes.isEmpty(), changes.joinToString("; ").ifEmpty {
            "${current.size} world, bounty, cargo and executive stat facts matched"
        })
        val savedEvidence = saved["evidence"] as? Map<*, *>
            ?: throw CampaignCheckFailure("checkpoint_shape", "Missing evidence map")
        savedEvidence.forEach { (key, value) ->
            require(key is String && value is Boolean) { "Invalid saved evidence" }
            run.observe(key, value, "Observed in run phase; business snapshot matched after reload")
        }
        (saved["details"] as? Map<*, *>)?.forEach { (key, value) ->
            if (key is String && value != null) run.detail(key, value.toString())
        }
        run.check("persisted", changes.isEmpty(), "Verified actual business state after disk save and process restart")
    }

    private fun facts(): LinkedHashMap<String, String> {
        val sector = checkNotNull(Global.getSector())
        val result = linkedMapOf<String, String>()
        val bounty = BountyState.getOrCreate()
        val ending = EndingState.getOrCreate()
        result["bounty.completed"] = bounty.succeededBountyKeys.sorted().joinToString()
        result["bounty.defeated"] = bounty.defeatedBountyKeys.sorted().joinToString()
        result["bounty.groups"] = bounty.clearedGroupIds.sorted().joinToString()
        result["bounty.chapters"] = bounty.completedChapters.sorted().joinToString()
        result["bounty.requests"] = bounty.settlementRequests.sorted().joinToString()
        result["bounty.mainCompleted"] = bounty.mainCompleted.toString()
        result["bounty.contractor"] = bounty.contractorId
        result["bounty.level"] = bounty.contractorLevel.toString()
        result["bounty.quotes"] = bounty.quotedRewards.toSortedMap().toString()
        result["bounty.ledger"] = bounty.ledgerEntries.joinToString("|") { "${it.code}:${it.amount}:${it.date}" }
        result["bounty.archive"] = "${bounty.archiveChoice}:${bounty.archiveTradeFactionId}:${bounty.archivalPending}"
        result["bounty.infinite"] = bounty.infiniteContractor.toString()
        result["core.issued"] = "${ending.executiveCoreType}:${bounty.executiveCoreIssued}:${ending.commandShipId}"
        result["core.memory"] = sector.memoryWithoutUpdate.getString(EndingKeys.MEM_EXECUTIVE_CORE_TYPE) ?: ""
        result["cargo.held"] = StoryCargo.getOrCreate().heldAssetKeys.sorted().joinToString()
        val infinite = InfiniteBountyState.getOrCreate()
        result["infinite.slots"] = infinite.slots.joinToString("|") { "${it.key}:${it.generation}:${it.quotedReward}" }
        result["infinite.pending"] = infinite.pendingDelivery.sorted().joinToString()
        result["infinite.bills"] = infinite.bills.joinToString("|") { "${it.code}:${it.amount}" }
        val entities = sector.starSystems.flatMap { it.allEntities }.filter {
            it.id?.startsWith("astd_") == true && it !is com.fs.starfarer.api.campaign.CampaignFleetAPI
        }
        result["world.ids"] = entities.map { it.id }.sorted().joinToString()
        for (entity in entities) {
            result["entity.${entity.id}.binding"] = "${entity.containingLocation?.id}:${entity.faction?.id}"
            val market = entity.market ?: continue
            result["market.${market.id}.binding"] = "${market.primaryEntity?.id}:${market.factionId}:${market.isPlayerOwned}"
            result["market.${market.id}.conditions"] = market.conditions.map { it.id }.sorted().joinToString()
        }
        result["player.location"] = sector.playerFleet.containingLocation.id
        for (core in listOf(Commodities.GAMMA_CORE, Commodities.BETA_CORE, Commodities.ALPHA_CORE, Commodities.OMEGA_CORE)) {
            result["cargo.$core"] = sector.playerFleet.cargo.getCommodityQuantity(core).toString()
        }
        for (member in sector.playerFleet.fleetData.membersListCopy.filter { !it.isFighterWing }) {
            result["ship.${member.id}.commandSpeed"] = member.stats.maxSpeed.getPercentStatMod(EndingKeys.COMMAND_MOD_ID)?.value.toString()
            result["ship.${member.id}.commandPeak"] = member.stats.peakCRDuration.getFlatBonus(EndingKeys.COMMAND_MOD_ID)?.value.toString()
        }
        for (market in sector.economy.marketsCopy.filter { it.isPlayerOwned }) {
            result["market.${market.id}.adminStability"] = market.stability.getFlatStatMod(EndingKeys.ADMIN_MOD_ID)?.value.toString()
            result["market.${market.id}.adminAccess"] = market.accessibilityMod.getFlatBonus(EndingKeys.ADMIN_MOD_ID)?.value.toString()
            result["market.${market.id}.adminDefense"] = market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD)
                .getMultBonus(EndingKeys.ADMIN_MOD_ID)?.value.toString()
        }
        // 世界入口由星体ID查找，而非误把getStarSystem(name)当作按ID查询。
        for (star in listOf(StoryWorldIds.MAIN_STAR, StoryWorldIds.STARFALL_STAR, StoryWorldIds.ASTER_STAR)) {
            result["world.$star"] = sector.getEntityById(star)?.starSystem?.id ?: "absent"
        }
        return result
    }
}
