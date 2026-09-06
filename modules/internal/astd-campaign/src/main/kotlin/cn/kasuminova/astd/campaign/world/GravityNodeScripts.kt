package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.story.BranchStationBackendImpl
import cn.kasuminova.astd.campaign.story.StorySites
import cn.kasuminova.astd.campaign.dialog.story.BranchStationDialog
import cn.kasuminova.astd.campaign.ui.terminal.BranchTerminalUi
import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18n.Categories
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.BaseCampaignPlugin
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.campaign.FleetAssignment
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.combat.EngagementResultAPI
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.util.Misc
import org.apache.log4j.Logger

/**
 * 生涯层引力节点机制（07 文档，ZW 工单线）。
 *
 * - 接触触发：玩家舰队接触引力节点 → 节点刷出动态护卫舰队（基准 300 FP × 难度系数 k_s）；
 *   每节点仅触发一次（[StoryWorldState.gravityNodesGuardTriggered]）；
 * - 拔除判定：节点实体被移除（打捞/摧毁）→ 记入 [StoryWorldState.gravityNodesPulled]；
 *   ZW 工单（MainBounties.KEY_ZW_0309）阶段 0~2 由拔除进度驱动——
 *   MainBountyBridge.tickPosted 周期性调用 MainlineProgression.syncNodePulledStage
 *   按 max 口径同步阶段（挂出前拔除同样计入），拔全后挂出阶段 3 核心守备战斗工单；
 * - 核心数据舱：未拔全 3 节点前交互被拒（[StoryWorldCampaignPlugin] 拦截交互，
 *   提示「有一股无形力量排斥」并引导拆节点）。
 *
 * 战斗内节点机制（增益/减成战场单位）不在本层，另由战斗任务实装。
 *
 * 事件视界免疫（视界动力状况的生涯层效果，与市场状况数值相互独立）：
 * [EventHorizonShieldScript] 动态为「拾光」1500su 范围内全部舰队挂/摘
 * [Tags.FLEET_IGNORES_CORONA]（StarCoronaTerrainPlugin.applyEffect 对该标签直接豁免，
 * 事件视界地形继承同一判定）。
 */
object GravityNodes {

    private val log: Logger = Global.getLogger(GravityNodes::class.java)

    /** 节点接触判定额外余量（su，实体半径之外）。 */
    const val CONTACT_MARGIN: Float = 50f

    /** 护卫舰队基准 FP（07 文档）。 */
    const val GUARD_BASE_FP: Float = 300f

    /** 护卫舰队 FP：基准 × 难度系数 k_s（轨一）。 */
    fun guardFleetPoints(tuning: DifficultyTuning): Float = GUARD_BASE_FP * tuning.fixedScale

    /** 已拔除节点数（ZW 工单阶段同步的查询入口，MainBountyBridge 消费）。 */
    fun pulledCount(): Int = StoryWorldState.getOrCreate().pulledNodeCount()

    /** 全部节点是否已拔除（核心数据舱解禁 / ZW 阶段四解锁判定）。 */
    fun allPulled(): Boolean = StoryWorldState.getOrCreate().allNodesPulled()

    /** 指定节点是否已拔除。 */
    fun isPulled(nodeId: String): Boolean = StoryWorldState.getOrCreate().isNodePulled(nodeId)

    /** 读档/每帧注册入口：挂两个生涯层脚本（transient，状态均在 persistentData）。 */
    fun installScripts(sector: SectorAPI) {
        sector.addTransientScript(GravityNodeWatchScript())
        sector.addTransientScript(EventHorizonShieldScript())
    }

    /** 生成并指派某节点的护卫舰队（「不接舷」：依托节点防守，不主动贴近玩家）。 */
    private fun spawnGuard(system: StarSystemAPI, node: SectorEntityToken, player: CampaignFleetAPI) {
        val fp = guardFleetPoints(DifficultyTuningImpl)
        val params = FleetParamsV3(
            node.location,
            Factions.REMNANTS,
            null,
            FleetTypes.PATROL_LARGE,
            fp, 0f, 0f, 0f, 0f, 0f, 0f,
        )
        params.ignoreMarketFleetSizeMult = true
        params.onlyApplyFleetSizeToCombatShips = true

        val fleet = FleetFactoryV3.createFleet(params)
        if (fleet == null) {
            log.error("[ASTD] 引力节点护卫舰队生成失败：${node.id}")
            return
        }
        fleet.setName(I18n.t(Categories.MOD, "world.aster.node.guard_fleet_name"))
        fleet.memoryWithoutUpdate.set("\$astd_gravity_node_guard", node.id)
        fleet.memoryWithoutUpdate.set("\$doNotGetSidetracked", true)

        system.spawnFleet(node, 0f, 0f, fleet)
        fleet.clearAssignments()
        fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, node, 3650f)
        log.info("[ASTD] 引力节点 ${node.id} 护卫舰队已生成：${fp.toInt()} FP（remnant）")
    }

    /** 节点监视脚本：接触触发护卫舰队 + 拔除状态检测。 */
    class GravityNodeWatchScript : EveryFrameScript {

        override fun advance(amount: Float) {
            val sector = Global.getSector() ?: return
            val star = sector.getEntityById(StoryWorldIds.ASTER_STAR) ?: return
            val system = star.containingLocation as? StarSystemAPI ?: return
            val state = StoryWorldState.getOrCreate()

            for (nodeId in StoryWorldIds.ASTER_NODE_IDS) {
                val node = system.getEntityById(nodeId)
                if (node == null) {
                    if (state.gravityNodesPulled.add(nodeId)) {
                        log.info("[ASTD] 引力节点已拔除：$nodeId（进度 ${state.pulledNodeCount()}/${StoryWorldIds.ASTER_NODE_IDS.size}）")
                    }
                    continue
                }
                if (nodeId in state.gravityNodesGuardTriggered) continue

                val player = sector.playerFleet ?: return
                if (player.containingLocation !== system) continue
                if (Misc.getDistance(player.location, node.location) <= node.radius + player.radius + CONTACT_MARGIN) {
                    state.gravityNodesGuardTriggered.add(nodeId)
                    spawnGuard(system, node, player)
                }
            }
        }

        override fun isDone(): Boolean = false

        override fun runWhilePaused(): Boolean = false
    }

    /** 视界动力：拾光 1500su 范围内舰队的事件视界免疫标签动态挂摘。 */
    class EventHorizonShieldScript : EveryFrameScript {

        /** 本脚本挂过标签的舰队 id（出范围/实体消失时摘除并复原）。 */
        private val shieldedFleetIds = LinkedHashSet<String>()

        override fun advance(amount: Float) {
            val sector = Global.getSector() ?: return
            val station = sector.getEntityById(StoryWorldIds.ASTER_STATION_SHIGUANG)
            if (station != null) {
                for (fleet in station.containingLocation.fleets) {
                    if (Misc.getDistance(station.location, fleet.location) > StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS) continue
                    if (shieldedFleetIds.add(fleet.id)) {
                        fleet.addTag(Tags.FLEET_IGNORES_CORONA)
                        fleet.memoryWithoutUpdate.set(MEM_SHIELDED, true)
                    }
                }
            }

            val iterator = shieldedFleetIds.iterator()
            while (iterator.hasNext()) {
                val fleetId = iterator.next()
                val fleet = sector.getEntityById(fleetId) as? CampaignFleetAPI
                val inRange = fleet != null && fleet.isAlive && station != null &&
                    fleet.containingLocation === station.containingLocation &&
                    Misc.getDistance(station.location, fleet.location) <= StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS
                if (!inRange) {
                    if (fleet != null && fleet.memoryWithoutUpdate.getBoolean(MEM_SHIELDED)) {
                        fleet.removeTag(Tags.FLEET_IGNORES_CORONA)
                        fleet.memoryWithoutUpdate.unset(MEM_SHIELDED)
                    }
                    iterator.remove()
                }
            }
        }

        override fun isDone(): Boolean = false

        override fun runWhilePaused(): Boolean = false

        companion object {
            /** 舰队 memory：事件视界免疫标签由本模组挂载（摘除时只摘自己挂的）。 */
            const val MEM_SHIELDED: String = "\$astd_event_horizon_shielded"
        }
    }
}

/**
 * 剧情世界战役插件：剧情实体交互路由。
 *
 * - 核心数据舱：未拔全引力节点时拒绝进入（力场排斥，07 文档）；
 * - 分局空间站：入口对话（entry/menu）→「接入分局终端」打开全屏终端 UI
 *   （[BranchTerminalUi]，数据/动作由 [BranchStationBackendImpl] 注入）；
 * - 遗址站/预留站/引力节点：三态文本 + 回收托管资产（[StorySites] 注册表）。
 *
 * 注册语义：transient 插件，每次 onGameLoad 经 `sector.registerPlugin` 重注册；
 * 原版 ModAndPluginData.addPlugin 对同 id 先移除旧实例再添加（0.98a 字节码核实），
 * 且 0.98a CampaignPlugin 接口无 getPriorityVersion() 版本位——同 id 覆盖即正确写法，
 * 插件无状态，替换无残留。
 */
class StoryWorldCampaignPlugin : BaseCampaignPlugin() {

    override fun getId(): String = "astd_story_world"

    override fun isTransient(): Boolean = true

    override fun pickInteractionDialogPlugin(interactionTarget: SectorEntityToken): PluginPick<InteractionDialogPlugin>? {
        // 核心数据舱：未拔全节点前力场排斥（优先级高于站点通用交互）
        if (interactionTarget.id == StoryWorldIds.ASTER_CORE_VAULT &&
            !StoryWorldState.getOrCreate().allNodesPulled()
        ) {
            return PluginPick(CoreVaultRepelDialogPlugin(), PickPriority.MOD_SET)
        }
        // 分局空间站：模组总入口（entry/menu 对话 → 全屏终端 UI）
        if (interactionTarget.id == StoryWorldIds.MAIN_STATION_BRANCH) {
            val backend = BranchStationBackendImpl()
            return PluginPick(
                BranchStationDialog.createPlugin(backend) { dialog, tab ->
                    BranchTerminalUi.open(dialog, backend, tab)
                },
                PickPriority.MOD_SET,
            )
        }
        // 遗址站/预留站/引力节点：三态文本 + 回收托管资产
        StorySites.createPlugin(interactionTarget)?.let {
            return PluginPick(it, PickPriority.MOD_SET)
        }
        return null
    }
}

/** 核心数据舱排斥对话：显示力场排斥提示，仅提供离开选项。 */
class CoreVaultRepelDialogPlugin : InteractionDialogPlugin {

    private lateinit var dialog: InteractionDialogAPI

    override fun init(dialog: InteractionDialogAPI) {
        this.dialog = dialog
        dialog.textPanel.addPara(I18n.t(Categories.MOD, "world.aster.core_vault.repel"))
        dialog.optionPanel.addOption(OPT_LEAVE, I18n[Categories.MOD, "dialog.core.leave"])
    }

    override fun optionSelected(text: String?, optionData: Any?) {
        if (optionData == OPT_LEAVE) {
            dialog.dismiss()
        }
    }

    override fun optionMousedOver(optionText: String?, optionData: Any?) {}

    override fun advance(amount: Float) {}

    override fun backFromEngagement(battleOutcome: EngagementResultAPI?) {}

    override fun getContext(): Any? = null

    override fun getMemoryMap(): MutableMap<String, MemoryAPI> = mutableMapOf()

    companion object {
        private const val OPT_LEAVE: String = "astd_core_vault_leave"
    }
}
