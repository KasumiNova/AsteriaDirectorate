package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.story.BountyTerminalDataSource
import cn.kasuminova.astd.campaign.story.StoryCargo
import cn.kasuminova.astd.campaign.story.StorySites
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BattleCreationContext
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator

/**
 * 一张主线赏金单的真实生涯闭环检查：接取、遭遇、战斗、MagicBounty 成功、资产回收、
 * 终端核销和一次性奖励。它不直接写入任何胜利状态；所有状态均从原版/MagicLib/战役
 * 业务状态读取，舰船击毁由 [CampaignBattleDriver] 通过 CombatEngineAPI 完成。
 */
class CampaignBountyChecks(
    private val run: CampaignRun,
    private val bountyKey: String = MainBounties.defs.first().key,
) : CampaignCheck {

    private val log: Logger = Global.getLogger(CampaignBountyChecks::class.java)
    private val terminal = BountyTerminalDataSource()
    private var acceptCooldown = 0f
    private var settlementRequested = false
    private var callbackAccepted = false
    private var magicSucceededObserved = false
    private var driver: CampaignBattleDriver? = null
    private var baselineCredits: Float? = null
    private var baselineLedgerAmount: Long? = null
    private var completed = false

    /** UI/外部驱动可在真实接取已被观察后接收通知，不会改变任务状态。 */
    var onAccepted: (() -> Unit)? = null

    /** UI/外部驱动可在自动请求核销前接收通知，不会替代 requestSettlement。 */
    var onBeforeSettlement: (() -> Unit)? = null

    override fun advance(amount: Float): Boolean {
        if (completed) return true
        val sector = Global.getSector()
        if (sector == null) {
            check("accepted", false, "sector unavailable")
            return false
        }

        val state = BountyState.getOrCreate()
        val active = activeBounty()
        val magicMemory = sector.memoryWithoutUpdate.getBoolean("\$astd_battle_$bountyKey")
        val accepted = isAccepted(active) || magicSucceededObserved || magicMemory
        check("accepted", accepted, if (accepted) "MagicBounty stage=${active?.stage}" else "waiting for real DataSource acceptance")
        if (!accepted) {
            run.stage("bounty_acceptance")
            acceptCooldown -= amount
            if (acceptCooldown <= 0f) {
                acceptCooldown = ACCEPT_RETRY_INTERVAL
                terminal.acceptWorkOrder(bountyKey)
            }
            return false
        }

        if (!callbackAccepted) {
            callbackAccepted = true
            baselineCredits = sector.playerFleet?.cargo?.credits?.get()
            baselineLedgerAmount = state.ledgerEntries.sumOf { it.amount }
            onAccepted?.invoke()
        }

        val patched = bountyKey in state.patchedBountyKeys
        run.detail("fleetPatched", patched)
        if (!patched) {
            run.stage("bounty_fleet_patch")
            return false
        }

        val engine = Global.getCombatEngine()
        if (engine != null && isTargetBattle(engine.context, active)) {
            ensureDriver(engine)
            run.stage("bounty_battle")
            check("enteredBattle", driver?.enteredBattle == true, "real BattleCreationContext accepted")
            check("enemiesDestroyed", driver?.enemiesDestroyed == true, "CombatEngine destruction result pending")
            if (driver?.enemiesDestroyed != true) return false
        } else {
            check("enteredBattle", false, "waiting for real bounty FleetInteractionDialog battle")
            check("enemiesDestroyed", false, "waiting for real combat result")
            return false
        }

        val magicSucceeded = activeBounty()?.stage == ActiveBounty.Stage.Succeeded
        check("magicSucceeded", magicSucceeded, "MagicBounty listener stage=${activeBounty()?.stage}")
        if (!magicSucceeded) return false

        val defeated = bountyKey in state.defeatedBountyKeys
        if (!defeated && bountyKey !in state.succeededBountyKeys) {
            run.stage("bounty_listener")
            run.detail("defeated", false)
            return false
        }

        val requiresAsset = StorySites.requiresAsset(bountyKey)
        val hasAsset = StoryCargo.getOrCreate().hasAsset(bountyKey)
        val assetCollected = if (requiresAsset) {
            hasAsset
        } else {
            !hasAsset
        }
        val assetDetail = if (requiresAsset) {
            if (hasAsset) "required asset is held" else "waiting for real asset collection UI"
        } else {
            "not-required; no asset record is expected"
        }
        check("assetCollected", assetCollected, assetDetail)
        if (!assetCollected) return false

        if (bountyKey !in state.succeededBountyKeys && !settlementRequested) {
            onBeforeSettlement?.invoke()
            settlementRequested = terminal.requestSettlement(bountyKey)
            run.detail("settlementRequested", settlementRequested)
            if (!settlementRequested) {
                run.stage("bounty_settlement_request")
                return false
            }
        }

        val settled = bountyKey in state.succeededBountyKeys
        check("settled", settled, "BountyCampaignManager settlement=${if (settled) "completed" else "pending"}")
        if (!settled) return false

        val credits = sector.playerFleet?.cargo?.credits?.get()
        val creditsDelta = if (credits != null && baselineCredits != null) credits - baselineCredits!! else 0f
        val ledgerBefore = baselineLedgerAmount ?: 0L
        val ledgerAfter = state.ledgerEntries.sumOf { it.amount }
        val rewardGranted = creditsDelta > 0f && ledgerAfter > ledgerBefore
        check(
            "rewardGranted",
            rewardGranted,
            "creditsDelta=$creditsDelta ledgerDelta=${ledgerAfter - ledgerBefore}",
        )

        // 结清后再次请求必须被业务层拒绝；若第二次成功则会重复奖励，闭环失败。
        val duplicateSettlement = terminal.requestSettlement(bountyKey)
        val noDuplicateReward = !duplicateSettlement && bountyKey !in state.settlementRequests
        if (noDuplicateReward) {
            run.check("noDuplicateReward", true, "second request accepted=false")
        } else {
            run.observe("noDuplicateReward", false, "second request accepted=$duplicateSettlement")
        }
        run.detail("noDuplicateReward", noDuplicateReward)

        val required = listOf(
            "accepted",
            "enteredBattle",
            "enemiesDestroyed",
            "magicSucceeded",
            "assetCollected",
            "settled",
            "rewardGranted",
        )
        completed = required.all { run.evidence[it] == true } && noDuplicateReward
        if (completed) {
            run.stage("bounty_closed")
            log.info("[ASTD-CampaignAutomation] bounty closed key=$bountyKey")
        }
        return completed
    }

    private fun ensureDriver(engine: com.fs.starfarer.api.combat.CombatEngineAPI) {
        if (driver != null) return
        val expectedEnemyIds = activeBounty()?.fleet?.fleetData?.membersListCopy
            ?.filterNot { it.isFighterWing }
            ?.mapTo(linkedSetOf()) { it.id }
            ?: emptySet()
        val newDriver = CampaignBattleDriver(
            bountyKey = bountyKey,
            expectedEnemyIds = expectedEnemyIds,
            onBattleEntered = { run.detail("battleContext", "Campaign BattleCreationContext") },
            onEnemiesDestroyed = { run.detail("combatResult", "all enemy FleetManager members destroyed") },
        )
        driver = newDriver
        engine.addPlugin(newDriver)
    }

    private fun activeBounty(): ActiveBounty? = try {
        MagicBountyCoordinator.getInstance().getActiveBounty(bountyKey)
    } catch (t: Throwable) {
        log.warn("[ASTD-CampaignAutomation] MagicBounty coordinator unavailable key=$bountyKey", t)
        null
    }

    private fun isAccepted(active: ActiveBounty?): Boolean = when (active?.stage) {
        ActiveBounty.Stage.Accepted,
        ActiveBounty.Stage.Succeeded,
        -> true
        else -> false
    }

    private fun isTargetBattle(context: BattleCreationContext?, active: ActiveBounty?): Boolean {
        if (context == null || active == null || !context.playerFleet.isValidPlayerFleet) return false
        val targetIds = active.fleet.fleetData.membersListCopy.mapTo(HashSet()) { it.id }
        return context.otherFleet?.fleetData?.membersListCopy?.any { it.id in targetIds } == true
    }

    private fun check(key: String, condition: Boolean, detail: String) {
        if (condition) {
            run.check(key, true, detail)
        } else {
            run.observe(key, false, detail)
        }
        run.evidence[key] = condition
        run.detail(key, detail)
    }

    companion object {
        private const val ACCEPT_RETRY_INTERVAL = 1f
    }
}
