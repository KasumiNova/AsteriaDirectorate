package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.BountyRewards
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.story.EndingKeys
import cn.kasuminova.astd.campaign.story.EndingRuntimeScript
import cn.kasuminova.astd.campaign.story.EndingSettlement
import cn.kasuminova.astd.campaign.story.EndingState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.Stats
import kotlin.math.abs

/** 核心真实规格、奖励入舱和两种执行官修正；只在复制的测试存档设置分支前置条件。 */
class CampaignCoreChecks(private val run: CampaignRun) : CampaignCheck {
    private var complete = false

    override fun advance(amount: Float): Boolean {
        if (complete) return true
        val sector = checkNotNull(Global.getSector())
        val fleet = checkNotNull(sector.playerFleet)
        sector.isPaused = true
        run.stage("core.specs_and_rewards")
        val coreIds = listOf(Commodities.GAMMA_CORE, Commodities.BETA_CORE, Commodities.ALPHA_CORE, Commodities.OMEGA_CORE)
        for (id in coreIds) {
            val spec = Global.getSettings().getCommoditySpec(id)
            run.check("spec.$id", spec.id == id && spec.name.isNotBlank() && spec.iconName.isNotBlank() &&
                spec.hasTag(Commodities.TAG_AI_CORE), "Loaded $id name=${spec.name} icon=${spec.iconName}")
            Global.getSettings().getSprite(spec.iconName)
        }
        run.check("coreSpecs", coreIds.all { run.evidence["spec.$it"] == true }, "All four actual core commodity specs resolve")
        val cargo = fleet.cargo
        val before = cargo.stacksCopy.filter { it.isCommodityStack }.associate { it.commodityId to it.size }
        val weaponsBefore = cargo.weapons.associate { it.item to it.count }
        val loot = BountyRewards.grantSideLoot("astd_automation_core_reward", 5, 5f, 0x41535444L)
        for ((id, quantity) in loot.commodities) {
            run.check("reward.$id", close(cargo.getCommodityQuantity(id) - (before[id] ?: 0f), quantity.toFloat()),
                "Actual cargo delta for $id equals reported grant $quantity")
        }
        for ((id, quantity) in loot.weapons) {
            run.check("reward.weapon.$id", cargo.getNumWeapons(id) - (weaponsBefore[id] ?: 0f).toInt() == quantity,
                "Actual weapon cargo delta equals reported grant")
        }
        run.check("rewardCargo", loot.commodities[Commodities.GAMMA_CORE] == 3 &&
            loot.commodities[Commodities.ALPHA_CORE] == 1 && cargo.getCommodityQuantity(Commodities.OMEGA_CORE) == (before[Commodities.OMEGA_CORE] ?: 0f),
            "High-tier grant yields gamma/alpha without unintended omega; beta follows seeded reward roll")

        run.stage("core.combat_branch")
        // 一次场景分别验证互斥奖励分支；只重置测试副本的选择前置，实际签发/效果走生产入口。
        val state = BountyState.getOrCreate()
        val ending = EndingState.getOrCreate()
        run.check("core.notIssuedBeforeArchive", !EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_COMBAT),
            "Unarchived fixture cannot receive core")
        state.infiniteContractor = true
        run.detail("fixture.coreEligibility", "Explicit infiniteContractor prerequisite, not an archival progression assertion")
        val primary = fleet.flagship ?: Global.getFactory().createFleetMember(FleetMemberType.SHIP, "wolf_Assault").also {
            fleet.fleetData.addFleetMember(it)
        }
        val second = Global.getFactory().createFleetMember(FleetMemberType.SHIP, "wolf_Assault")
        fleet.fleetData.addFleetMember(second)
        run.check("core.combatIssued", EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_COMBAT), "Production core signing accepted")
        run.check("core.commandAssigned", EndingSettlement.assignCommandShip(primary), "Production command ship assignment")
        val runtime = EndingRuntimeScript()
        runtime.advance(1.1f)
        checkCommand(primary, true)
        checkCommand(second, false)
        run.check("core.combatDuplicate", !EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_ADMIN) &&
            !EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_COMBAT), "Neither duplicate nor alternative can replace issued core")
        run.check("core.commandReassigned", EndingSettlement.assignCommandShip(second), "Command reassignment accepted")
        runtime.advance(1.1f)
        checkCommand(primary, false)
        checkCommand(second, true)
        runtime.advance(1.1f)
        checkCommand(second, true)
        run.check("combatCore", ending.commandShipId == second.id && state.executiveCoreIssued &&
            sector.memoryWithoutUpdate.getString(EndingKeys.MEM_EXECUTIVE_CORE_TYPE) == EndingKeys.CORE_TYPE_COMBAT,
            "Combat branch modifies only assigned member; repeated maintenance does not stack")

        run.stage("core.admin_branch")
        ending.executiveCoreType = ""
        ending.commandShipId = ""
        state.executiveCoreIssued = false
        sector.memoryWithoutUpdate.unset(EndingKeys.MEM_EXECUTIVE_CORE_TYPE)
        run.detail("fixture.adminBranch", "Independent issuance fixture after combat validation; not a player-facing respec")
        val market = createMarket("astd_automation_admin_market", true)
        val neutral = createMarket("astd_automation_neutral_market", false)
        run.check("core.adminIssued", EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_ADMIN), "Production administrative core signing")
        runtime.advance(1.1f)
        checkAdmin(market, true)
        checkAdmin(neutral, false)
        checkCommand(primary, false)
        checkCommand(second, false)
        runtime.advance(1.1f)
        checkAdmin(market, true)
        market.isPlayerOwned = false
        runtime.advance(1.1f)
        checkAdmin(market, false)
        market.isPlayerOwned = true
        runtime.advance(1.1f)
        checkAdmin(market, true)
        run.check("adminCore", ending.executiveCoreType == EndingKeys.CORE_TYPE_ADMIN && state.executiveCoreIssued,
            "Administrative stat changes match, do not stack, and clear after ownership loss")
        run.check("noDuplicate", !EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_ADMIN) &&
            !EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_COMBAT), "Issued administrative core rejects both subsequent choices")
        complete = true
        // persisted 由 CampaignSaveChecks 在第二个进程真实读档后补充。
        return true
    }

    private fun checkCommand(member: com.fs.starfarer.api.fleet.FleetMemberAPI, active: Boolean) {
        val speed = member.stats.maxSpeed.getPercentStatMod(EndingKeys.COMMAND_MOD_ID)?.value
        val peak = member.stats.peakCRDuration.getFlatBonus(EndingKeys.COMMAND_MOD_ID)?.value
        val range = member.stats.energyWeaponRangeBonus.getPercentBonus(EndingKeys.COMMAND_MOD_ID)?.value
        run.check("core.command.${member.id}", if (active) speed == 15f && peak == 60f && range == 15f
            else speed == null && peak == null && range == null, "active=$active speed=$speed peak=$peak range=$range")
    }

    private fun checkAdmin(market: MarketAPI, active: Boolean) {
        val stability = market.stability.getFlatStatMod(EndingKeys.ADMIN_MOD_ID)?.value
        val access = market.accessibilityMod.getFlatBonus(EndingKeys.ADMIN_MOD_ID)?.value
        val defense = market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD).getMultBonus(EndingKeys.ADMIN_MOD_ID)?.value
        run.check("core.admin.${market.id}", if (active) stability == 1f && access == 0.10f && defense == 1.25f
            else stability == null && access == null && defense == null, "active=$active stability=$stability access=$access defense=$defense")
    }

    private fun createMarket(id: String, player: Boolean): MarketAPI {
        val sector = Global.getSector()
        require(sector.economy.getMarket(id) == null) { "Test market already exists: $id" }
        val entity = sector.playerFleet.containingLocation.addCustomEntity(id, id, "station_side06", Factions.INDEPENDENT)
        entity.setLocation(sector.playerFleet.location.x + 800f, sector.playerFleet.location.y + if (player) 0f else 400f)
        val market = Global.getFactory().createMarket(id, id, 3)
        market.primaryEntity = entity
        entity.market = market
        market.factionId = if (player) Factions.PLAYER else Factions.INDEPENDENT
        market.isPlayerOwned = player
        sector.economy.addMarket(market, false)
        return market
    }

    private fun close(actual: Float, expected: Float): Boolean = abs(actual - expected) < 0.01f
}
