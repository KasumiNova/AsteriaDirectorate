package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.BountyFidConfigGen
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.BaseCampaignPlugin
import com.fs.starfarer.api.campaign.BattleCreationPlugin
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.CampaignPlugin
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.BattleCreationContext
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.EngagementResultAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl
import com.fs.starfarer.api.impl.combat.BattleCreationPluginImpl
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.mission.MissionDefinitionAPI
import com.fs.starfarer.combat.CombatEngine
import org.apache.log4j.Logger
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

/** 已创建的真实生涯战斗：部署 reserves，以伤害击毁敌舰，确认实际损失后才请求原版返回。 */
class CampaignBattleDriver(
    private val bountyKey: String,
    expectedEnemyIds: Set<String>,
    private val onBattleEntered: () -> Unit = {},
    private val onEnemiesDestroyed: () -> Unit = {},
) : BaseEveryFrameCombatPlugin() {
    private val log = Logger.getLogger(CampaignBattleDriver::class.java)
    private lateinit var engine: CombatEngineAPI
    private val expectedIds = expectedEnemyIds.toMutableSet()
    private val observedShips = linkedSetOf<ShipAPI>()
    private var deploymentStarted = false
    private var elapsed = 0f
    private var lastDamage = 0f
    private var lastReport = 0f
    private var startedAt = 0L

    var enteredBattle = false
        private set
    var enemiesDestroyed = false
        private set
    var failure: Throwable? = null
        private set
    val destroyedMemberIds: Set<String>
        get() = engine.getFleetManager(FleetSide.ENEMY).destroyedCopy.mapTo(linkedSetOf()) { it.id }

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        require(expectedIds.isNotEmpty()) { "No campaign enemy ship IDs for $bountyKey" }
        startedAt = System.nanoTime()
        engine.setDoNotEndCombat(true)
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (failure != null || enemiesDestroyed) return
        try {
            advanceBattle(amount)
        } catch (error: Throwable) {
            failure = error
            log.error("[ASTD-CampaignAutomation] bounty combat failed key=$bountyKey", error)
        }
    }

    private fun advanceBattle(amount: Float) {
        check(System.nanoTime() - startedAt < 180_000_000_000L) { "Combat timeout: $bountyKey" }
        check(engine.isInCampaign && !engine.isInCampaignSim) { "Not a real campaign battle: $bountyKey" }
        check(!engine.isCombatOver) { "Combat ended before all enemy losses were confirmed: $bountyKey" }
        if (!enteredBattle) {
            enteredBattle = true
            onBattleEntered()
            log.info("[ASTD-CampaignAutomation] real combat entered key=$bountyKey")
        }
        if (engine.isPaused) return
        if (!deploymentStarted) {
            deploymentStarted = true
            deployReserves(FleetSide.PLAYER)
            val player = engine.getFleetManager(FleetSide.PLAYER)
            val flagship = player.deployedCopy.first { it.isFlagship && !it.isFighterWing }
            engine.setPlayerShipExternal(checkNotNull(player.getShipFor(flagship)))
        }
        deployReserves(FleetSide.ENEMY)
        val enemy = engine.getFleetManager(FleetSide.ENEMY)
        for (deployed in enemy.allEverDeployedCopy) {
            if (!deployed.member.isFighterWing) expectedIds += deployed.member.id
        }
        observedShips += engine.ships.filter { it.originalOwner == 1 && !it.isFighter }
        for (ship in engine.ships.filter { it.originalOwner == 0 && engine.isShipAlive(it) }) {
            ship.mutableStats.hullDamageTakenMult.modifyMult(PROTECTION_ID, 0f)
            ship.mutableStats.armorDamageTakenMult.modifyMult(PROTECTION_ID, 0f)
        }
        elapsed += amount
        if (elapsed < 4f || elapsed - lastDamage < 0.2f) return
        lastDamage = elapsed
        val source = checkNotNull(engine.playerShip) { "Missing deployed player damage source" }
        check(engine.isShipAlive(source)) { "Player damage source destroyed" }
        val previousLosses = destroyedMemberIds
        for (ship in observedShips.sortedBy { !it.isStationModule }) {
            if (!engine.isEntityInPlay(ship)) continue
            val member = ship.fleetMember
            if (!ship.isStationModule && member != null && member.id in previousLosses) continue
            engine.applyDamage(
                ship, ship.location, max(1_000_000f, ship.maxHitpoints * 1000f),
                DamageType.ENERGY, 0f, true, false, source, false,
            )
        }
        val liveEnemies = engine.ships.any { it.originalOwner == 1 && !it.isFighter && engine.isShipAlive(it) }
        val losses = destroyedMemberIds
        if (expectedIds.all { it in losses } && enemy.reservesCopy.isEmpty() && !liveEnemies) {
            enemiesDestroyed = true
            onEnemiesDestroyed()
            log.info("[ASTD-CampaignAutomation] destroyed key=$bountyKey ids=${losses.joinToString()}")
            engine.setDoNotEndCombat(false)
            engine.endCombat(0.5f, FleetSide.PLAYER)
        } else if (elapsed - lastReport >= 5f) {
            lastReport = elapsed
            log.info("[ASTD-CampaignAutomation] combat pending key=$bountyKey remaining=${expectedIds - losses} reserves=${enemy.reservesCopy.size}")
        }
    }

    private fun deployReserves(side: FleetSide) {
        val manager = engine.getFleetManager(side)
        val reserves = manager.reservesCopy.toList()
        if (reserves.isEmpty()) return
        manager.isSuppressDeploymentMessages = true
        manager.isCanForceShipsToEngageWhenBattleClearlyLost = true
        manager.getTaskManager(false).setPreventFullRetreat(true)
        for ((index, member) in reserves.withIndex()) {
            if (side == FleetSide.ENEMY && !member.isFighterWing) expectedIds += member.id
            val x = (index % 12 - 6) * 500f
            val y = if (side == FleetSide.PLAYER) -6000f else 1000f + (index / 12) * 500f
            val ship = manager.spawnFleetMember(member, Vector2f(x, y), if (side == FleetSide.PLAYER) 90f else 270f, 0f)
            check(ship != null || member.isFighterWing) { "FleetManager failed to deploy ${member.id}" }
        }
        log.info("[ASTD-CampaignAutomation] deployed key=$bountyKey side=$side reserves=${reserves.size}")
    }

    private companion object {
        const val PROTECTION_ID = "astd_campaign_automation_player"
    }
}

internal class CampaignBountyEncounter(
    private val run: CampaignRun,
    private val bountyKey: String,
    private val target: CampaignFleetAPI,
) : FleetInteractionDialogPluginImpl(BountyFidConfigGen(bountyKey).createConfig()) {
    private val expectedIds = target.fleetData.membersListCopy.filter { !it.isFighterWing }.mapTo(linkedSetOf()) { it.id }
    val driver = CampaignBattleDriver(
        bountyKey, expectedIds,
        onBattleEntered = { run.check("enteredBattle", true, "Real campaign engine for $bountyKey") },
        onEnemiesDestroyed = { run.check("enemiesDestroyed", true, "All campaign ships and spawned defenders destroyed for $bountyKey") },
    )
    var returned = false
        private set
    var finished = false
        private set
    var failure: Throwable? = null
        private set
    private var pendingBattle = false
    private val pluginId = "astd_campaign_automation_battle_$bountyKey"
    private val creation = object : BattleCreationPluginImpl() {
        override fun initBattle(context: BattleCreationContext, loader: MissionDefinitionAPI) {
            check(context.otherFleet.fleetData.membersListCopy.map { it.id }.containsAll(expectedIds)) {
                "Original FID context omitted bounty ships: $bountyKey"
            }
            context.enemyDeployAll = true
            context.aiRetreatAllowed = false
            context.fightToTheLast = true
            context.initialNumSteps = 0f
            super.initBattle(context, loader)
        }

        override fun afterDefinitionLoad(engine: CombatEngineAPI) {
            super.afterDefinitionLoad(engine)
            // CombatEngineAPI 未公开部署窗开关；原版公共实现提供此方法，不需要反射或模拟按键。
            check(engine is CombatEngine) { "Unexpected campaign engine implementation" }
            engine.setShowDeploymentDialog(false)
            engine.addPlugin(driver)
        }
    }
    private val campaignPlugin = object : BaseCampaignPlugin() {
        override fun getId(): String = pluginId
        override fun pickBattleCreationPlugin(opponent: SectorEntityToken): PluginPick<BattleCreationPlugin>? {
            if (opponent !is CampaignFleetAPI) return null
            if (!opponent.fleetData.membersListCopy.map { it.id }.containsAll(expectedIds)) return null
            return PluginPick(creation, CampaignPlugin.PickPriority.HIGHEST)
        }
    }

    fun open(): Boolean {
        val sector = Global.getSector()
        sector.registerPlugin(campaignPlugin)
        val opened = sector.campaignUI.showInteractionDialog(this, target)
        if (!opened) sector.unregisterPlugin(pluginId)
        return opened
    }

    override fun backFromEngagement(result: EngagementResultAPI) {
        try {
            check(driver.enteredBattle && driver.enemiesDestroyed) { "Battle returned without real destruction evidence" }
            check(result.didPlayerWin()) { "Original engagement result was not a player victory" }
            val destroyed = result.loserResult.destroyed.mapTo(hashSetOf()) { it.id }
            check(destroyed.containsAll(expectedIds)) { "Original engagement result missing losses: ${expectedIds - destroyed}" }
            super.backFromEngagement(result)
            returned = true
            run.check("$bountyKey.engagementReturned", true, "Original FID processed destroyed IDs=${destroyed.joinToString()}")
        } catch (error: Throwable) {
            failure = error
            Logger.getLogger(CampaignBountyEncounter::class.java).error("[ASTD-CampaignAutomation] engagement callback failed", error)
        }
    }

    override fun advance(amount: Float) {
        super.advance(amount)
        if (finished || failure != null || driver.failure != null || pendingBattle && !returned) return
        try {
            if (!returned) {
                val option = listOf(OptionId.CONTINUE_INTO_BATTLE, OptionId.ENGAGE, OptionId.FORCE_ENGAGE, OptionId.INITIATE_BATTLE)
                    .firstOrNull { options.hasOption(it) } ?: return
                if (option == OptionId.CONTINUE_INTO_BATTLE) pendingBattle = true
                optionSelected(null, option)
            } else {
                val option = listOf(OptionId.CONTINUE_FROM_VICTORY_TRIGGERS, OptionId.RECOVERY_CONTINUE, OptionId.CONTINUE_LEAVE, OptionId.LEAVE)
                    .firstOrNull { options.hasOption(it) }
                when {
                    option != null -> optionSelected(null, option)
                    options.hasOption(OptionId.CONTINUE_LOOT) -> {
                        run.detail("$bountyKey.salvage", "generated; optional cargo declined through native LEAVE")
                        optionSelected(null, OptionId.LEAVE)
                    }
                    else -> return
                }
                if (cleanedUp) {
                    finished = true
                    Global.getSector().unregisterPlugin(pluginId)
                    run.check("$bountyKey.encounterClosed", true, "Native LEAVE applied after-battle effects and cleaned up battle")
                }
            }
        } catch (error: Throwable) {
            failure = error
            Logger.getLogger(CampaignBountyEncounter::class.java).error("[ASTD-CampaignAutomation] encounter automation failed", error)
        }
    }
}
