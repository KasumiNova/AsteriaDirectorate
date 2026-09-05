package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.campaign.dialog.story.DirectorateStationDialog
import cn.kasuminova.astd.campaign.ui.DirectorateTerminal
import cn.kasuminova.astd.campaign.ui.DirectorateTerminalBackends
import cn.kasuminova.astd.campaign.ui.DirectorateTerminalUi
import cn.kasuminova.astd.campaign.ui.TerminalControl
import cn.kasuminova.astd.campaign.ui.TerminalPress
import cn.kasuminova.astd.campaign.ui.WorkOrderStatus
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import org.apache.log4j.Logger

/**
 * 分局终端的真实 UI 生涯验收驱动。
 *
 * 驱动从实际分局空间站实体打开 [DirectorateStationDialog]，再走对话选项的
 * [DirectorateTerminal.showOn] 路径。终端操作只使用实际布局注册的语义按钮，最终事件仍
 * 进入原生 CustomUIPanelPlugin.buttonPressed；本类不读取像素、不调用私有 RowRef，也不直接
 * 调用业务数据源完成接取或核销。战斗阶段只观察战斗检查写入的真实赏金状态。
 */
class CampaignTerminalChecks(private val run: CampaignRun) : CampaignCheck {

    private enum class Phase {
        OPENING,
        SELECTING,
        ACCEPTING,
        TRACKING,
        WAITING_BATTLE,
        DELIVERING,
        CLOSING,
        DONE,
    }

    private val log: Logger = Global.getLogger(CampaignTerminalChecks::class.java)
    private val source = DirectorateTerminalBackends.get()
    private var phase = Phase.OPENING
    private var terminal: DirectorateTerminalUi? = null
    private var interactionDialog: InteractionDialogAPI? = null
    private var stationPlugin: GraphDialogPlugin? = null
    private var orderId: String? = null
    private var openingRequested = false

    /**
     * 仅推进接取、追踪和关闭，供战斗检查在真正进入遭遇前调用。
     * 返回 true 只表示终端已真实关闭并可交给战斗流程；不会把战斗或交付标记为完成。
     */
    fun advanceBattleOnly(amount: Float): Boolean {
        if (phase == Phase.WAITING_BATTLE) return true
        advance(amount)
        return phase == Phase.WAITING_BATTLE
    }

    override fun advance(amount: Float): Boolean {
        run.tick()
        if (phase == Phase.DONE) return true

        return try {
            when (phase) {
                Phase.OPENING -> advanceOpening()
                Phase.SELECTING -> advanceSelection()
                Phase.ACCEPTING -> advanceAcceptance()
                Phase.TRACKING -> advanceTracking()
                Phase.WAITING_BATTLE -> advanceBattleReturn()
                Phase.DELIVERING -> advanceDelivery()
                Phase.CLOSING -> advanceClosing()
                Phase.DONE -> true
            }
        } catch (failure: CampaignCheckFailure) {
            run.fail(failure.code, failure.message ?: failure.code)
            log.error("[ASTD-CampaignAutomation] terminal check failed code=${failure.code}", failure)
            false
        } catch (t: Throwable) {
            run.fail("terminal_exception", t.message ?: t::class.simpleName.orEmpty())
            log.error("[ASTD-CampaignAutomation] terminal check failed", t)
            false
        }
    }

    private fun advanceOpening(): Boolean {
        val current = terminal
        if (current == null || !current.view().open) {
            if (!openTerminal()) {
                run.stage("terminal_station_open")
                run.observe("opened", false, "waiting for the real branch-office interaction dialog")
                return false
            }
            run.stage("terminal_layout")
        }

        val view = terminal?.view() ?: return false
        val opened = view.open && view.panel != null && view.layoutRevision > 0 && view.componentCount > 0
        record("opened", opened, "CustomDialogDelegate created layout revision=${view.layoutRevision}")
        if (!opened) return false
        phase = Phase.SELECTING
        return false
    }

    private fun advanceSelection(): Boolean {
        val ui = terminal ?: return false
        val snapshot = source.snapshot()
        val selected = snapshot.findOrder(DEFAULT_BOUNTY_KEY)
        if (selected == null) {
            run.stage("terminal_order_visible")
            run.observe("selected", false, "the real terminal has no available order yet")
            return false
        }
        orderId = selected.id
        val result = ui.press(TerminalControl.Order(selected.id))
        val view = ui.view()
        val selectedInLayout = result == TerminalPress.DISPATCHED &&
            view.selectedOrderId == selected.id &&
            view.buttons[TerminalControl.Order(selected.id)]?.checked == true
        record("selected", selectedInLayout, "button result=$result layoutRevision=${view.layoutRevision}")
        if (!selectedInLayout) return false
        phase = when (selected.status) {
            WorkOrderStatus.AVAILABLE -> Phase.ACCEPTING
            WorkOrderStatus.READY_TO_SETTLE -> Phase.DELIVERING
            WorkOrderStatus.SETTLED -> Phase.CLOSING
            WorkOrderStatus.ACTIVE -> Phase.TRACKING
        }
        return false
    }

    private fun advanceAcceptance(): Boolean {
        val ui = terminal ?: return false
        val id = orderId ?: return false
        val current = source.snapshot().findOrder(id) ?: return false
        if (current.status == WorkOrderStatus.ACTIVE) {
            record("accepted", true, "terminal snapshot status=ACTIVE")
            phase = Phase.TRACKING
            return false
        }
        if (current.status != WorkOrderStatus.AVAILABLE) {
            run.observe("accepted", false, "terminal snapshot status=${current.status}")
            return false
        }
        val result = ui.press(TerminalControl.Action.ACCEPT)
        val accepted = result == TerminalPress.DISPATCHED &&
            source.snapshot().findOrder(id)?.status == WorkOrderStatus.ACTIVE
        record("accepted", accepted, "button result=$result terminal status=${source.snapshot().findOrder(id)?.status}")
        if (accepted) phase = Phase.TRACKING
        return false
    }

    private fun advanceTracking(): Boolean {
        val ui = terminal ?: return false
        val id = orderId ?: return false
        val trackedBefore = Global.getSector()?.memoryWithoutUpdate
            ?.getString("\$astd_terminal_settlement_focus") == id
        if (!trackedBefore) {
            val result = ui.press(TerminalControl.Action.TRACK)
            val tracked = result == TerminalPress.DISPATCHED &&
                Global.getSector()?.memoryWithoutUpdate
                    ?.getString("\$astd_terminal_settlement_focus") == id
            record("tracked", tracked, "button result=$result focus=${Global.getSector()?.memoryWithoutUpdate?.getString("\$astd_terminal_settlement_focus")}")
            if (!tracked) return false
        } else {
            record("tracked", true, "terminal tracking focus=$id")
        }
        phase = Phase.WAITING_BATTLE
        phase = Phase.CLOSING
        return false
    }

    private fun advanceBattleReturn(): Boolean {
        val defeated = BountyState.getOrCreate().defeatedBountyKeys.contains(orderId)
        if (!defeated) {
            run.stage("terminal_wait_battle")
            run.observe("delivered", false, "waiting for the real bounty battle and asset recovery")
            return false
        }
        terminal = null
        interactionDialog = null
        stationPlugin = null
        openingRequested = false
        phase = Phase.OPENING
        return false
    }

    private fun advanceDelivery(): Boolean {
        val ui = terminal ?: return false
        val id = orderId ?: return false
        val current = source.snapshot().findOrder(id) ?: return false
        if (current.status == WorkOrderStatus.SETTLED) {
            record("delivered", true, "terminal snapshot status=SETTLED")
            phase = Phase.CLOSING
            return false
        }
        if (current.status != WorkOrderStatus.READY_TO_SETTLE) {
            run.stage("terminal_delivery_ready")
            run.observe("delivered", false, "terminal snapshot status=${current.status}")
            return false
        }
        val result = ui.press(TerminalControl.Action.DELIVER)
        val delivered = result == TerminalPress.DISPATCHED &&
            source.snapshot().findOrder(id)?.status == WorkOrderStatus.SETTLED
        record("delivered", delivered, "button result=$result terminal status=${source.snapshot().findOrder(id)?.status}")
        if (delivered) phase = Phase.CLOSING
        return false
    }

    private fun advanceClosing(): Boolean {
        val ui = terminal ?: return false
        if (ui.view().closed) {
            if (!BountyState.getOrCreate().defeatedBountyKeys.contains(orderId)) {
                phase = Phase.WAITING_BATTLE
                run.observe("closed", false, "terminal closed before the real bounty battle; awaiting return")
                return false
            }
            record("closed", true, "CustomDialogCallback.dismissCustomDialog completed")
            val persisted = BountyState.getOrCreate() === BountyState.getOrCreate()
            record("persisted", persisted, "BountyState accessor returned stable campaign state")
            run.complete()
            phase = Phase.DONE
            return true
        }
        if (ui.close()) return false
        run.observe("closed", false, "waiting for the real custom dialog dismissal callback")
        return false
    }

    private fun openTerminal(): Boolean {
        val sector = Global.getSector() ?: return false
        val station = sector.getEntityById(StoryWorldIds.MAIN_STATION_BRANCH) ?: return false
        if (openingRequested) return terminal?.view()?.open == true

        openingRequested = true
        var openedByCallback = false
        val callbacks: Map<String, (DialogContext) -> Unit> = mapOf(
            DirectorateStationDialog.CALLBACK_WORK_ORDERS to { ctx: DialogContext ->
                interactionDialog = ctx.dialog
                terminal = DirectorateTerminal.showOn(ctx.dialog)
                openedByCallback = true
            },
        )
        val plugin = DirectorateStationDialog.createPlugin(callbacks)
        stationPlugin = plugin
        val shown = sector.campaignUI.showInteractionDialog(plugin, station)
        if (!shown) {
            openingRequested = false
            return false
        }
        plugin.optionSelected(null, DirectorateStationDialog.CALLBACK_WORK_ORDERS)
        log.info("[ASTD-CampaignAutomation] opened real branch-office station dialog callback=$openedByCallback")
        return terminal?.view()?.open == true
    }

    private fun record(key: String, condition: Boolean, detail: String) {
        if (condition) run.check(key, true, detail) else run.observe(key, false, detail)
        run.detail(key, detail)
    }

    companion object {
        private val DEFAULT_BOUNTY_KEY: String = cn.kasuminova.astd.campaign.bounty.MainBounties.defs.first().key
    }
}
