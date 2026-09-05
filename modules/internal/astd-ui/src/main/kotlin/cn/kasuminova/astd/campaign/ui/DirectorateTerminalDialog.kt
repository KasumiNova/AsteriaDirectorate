package cn.kasuminova.astd.campaign.ui

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18nUi
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.campaign.CustomDialogDelegate
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.PositionAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 模组总 UI（分局终端）入口。
 *
 * 原版 campaign UI 风格的全屏自定义对话框：顶部 tab 栏（工单/档案室/账户），
 * 左列表 + 右详情双栏，底部操作条，「关闭终端」经对话框取消按钮（Esc 可退出——
 * 终端是玩家的地盘，与代办对话的不可退出相反）。
 *
 * 状态读取完全经 [DirectorateTerminalDataSource]；数据源必须由 campaign 侧经
 * [DirectorateTerminalBackends.install] 明确注入——未注入时 [DirectorateTerminalBackends.get]
 * 记录错误并抛异常，终端拒绝以假数据打开。
 */
object DirectorateTerminal {

    /**
     * 终端默认尺寸：贴合原版全屏面板观感，随屏幕分辨率缩放并设上下限；
     * 小分辨率下收缩到屏幕内（留 16px 边距），由布局侧按紧凑模式适配。
     */
    fun preferredSize(): Pair<Float, Float> {
        val s = Global.getSettings()
        val maxW = s.screenWidth - 16f
        val maxH = s.screenHeight - 16f
        val w = (s.screenWidth * 0.80f).coerceIn(minOf(900f, maxW), minOf(1560f, maxW))
        val h = (s.screenHeight * 0.82f).coerceIn(minOf(540f, maxH), minOf(960f, maxH))
        return w to h
    }

    /**
     * 在交互对话（如分局空间站对话）之上打开终端。
     *
     * 注：0.98a 的 `CampaignUIAPI` 不暴露 `showCustomDialog`，自定义对话框必须依托
     * 一个活动的交互对话（`InteractionDialogAPI.showCustomDialog`），
     * 这与设计文档一致——终端入口即分局空间站对话选项。
     *
     * @return 终端委托实例，调用方可持有以触发剧情事件呈现（如 [DirectorateTerminalDelegate.triggerGlitch]）
     */
    fun showOn(
        dialog: InteractionDialogAPI,
        tab: TerminalTab = TerminalTab.WORK_ORDERS,
        source: DirectorateTerminalDataSource = DirectorateTerminalBackends.get(),
    ): DirectorateTerminalDelegate {
        val (w, h) = preferredSize()
        val delegate = DirectorateTerminalDelegate(tab, source)
        dialog.showCustomDialog(w, h, delegate)
        return delegate
    }
}

/**
 * 终端对话框委托：持有当前 tab / 选中项 / 待签文书，按钮事件驱动重建内容区。
 */
class DirectorateTerminalDelegate(
    initialTab: TerminalTab,
    private val source: DirectorateTerminalDataSource,
) : CustomDialogDelegate, DirectorateTerminalUi {

    private companion object {
        const val CAT: String = "asteria_directorate"
        const val KEY: String = "ui.terminal."

        val ACTION_ACCEPT = TerminalControl.Action.ACCEPT
        val ACTION_TRACK = TerminalControl.Action.TRACK
        val ACTION_DELIVER = TerminalControl.Action.DELIVER
        val ACTION_SIGN_ENDING = TerminalControl.Action.SIGN_ENDING
        val ACTION_ENDING_BACK = TerminalControl.Action.ENDING_BACK

        val TEXT: Color = Misc.getTextColor()
        val BASE: Color = Misc.getBasePlayerColor()
        val BG: Color = Misc.getDarkPlayerColor()
        val BRIGHT: Color = Misc.getBrightPlayerColor()
        val HIGHLIGHT: Color = Misc.getHighlightColor()
        val NEGATIVE: Color = Misc.getNegativeHighlightColor()
        val GRAY: Color = Misc.getGrayColor()
    }

    /** 随分辨率适配的布局度量（紧凑模式服务小分辨率屏幕）。 */
    private data class Metrics(
        val compact: Boolean,
        val headerH: Float,
        val actionBarH: Float,
        val pad: Float,
        val tabW: Float,
        val tabH: Float,
        val orderRowH: Float,
        val archiveRowH: Float,
        val btnW: Float,
        val btnH: Float,
    )

    private val buttons = linkedMapOf<TerminalControl, com.fs.starfarer.api.ui.ButtonAPI>()
    private var callback: CustomDialogDelegate.CustomDialogCallback? = null
    private var closed = false
    private var closeRequested = false
    private var layoutRevision = 0L
    private var renderedFrames = 0L
    private var lastPress = TerminalPress.NOT_OPEN

    private var tab: TerminalTab = initialTab
    private var snapshot: TerminalSnapshot = source.snapshot()
    private var selectedOrderId: String? = null
    private var selectedArchiveId: String? = null

    /** 正在审阅的待签拟制文书（结局 id）；null = 账户常规视图。退出/切 tab 即搁置，不签署。 */
    private var reviewingEndingId: String? = null

    private lateinit var root: CustomPanelAPI
    private val components = mutableListOf<UIComponentAPI>()
    private val plugin = TerminalPlugin()

    /** 子面板共用的事件转发器：按钮事件汇总到根 plugin，特效只在根面板渲染。 */
    private val forwarder = object : BaseCustomUIPanelPlugin() {
        override fun buttonPressed(buttonId: Any?) = plugin.buttonPressed(buttonId)
    }

    override fun createCustomDialog(panel: CustomPanelAPI, callback: CustomDialogDelegate.CustomDialogCallback) {
        this.root = panel
        this.callback = callback
        closed = false
        closeRequested = false
        rebuild()
    }

    override fun hasCancelButton(): Boolean = true

    /** 原版可能为 null 使用默认确认文案；确认与取消均只关闭，不签署文书。 */
    override fun getConfirmText(): String? = null

    override fun getCancelText(): String = I18n[CAT, KEY + "close"]

    override fun customDialogConfirm() = customDialogCancel()

    /** 仅原生关闭回调更新关闭状态；待签文书留在 campaign 侧，不在关闭时签署。 */
    override fun customDialogCancel() {
        closed = true
        callback = null
        reviewingEndingId = null
        buttons.clear()
    }

    override fun getCustomPanelPlugin(): CustomUIPanelPlugin = plugin

    override fun view(): TerminalView = TerminalView(
        panel = if (::root.isInitialized) root else null,
        open = callback != null && !closed && layoutRevision > 0,
        closed = closed,
        layoutRevision = layoutRevision,
        renderedFrames = renderedFrames,
        componentCount = components.size,
        tab = tab,
        selectedOrderId = selectedOrderId,
        selectedOrderStatus = selectedOrderId?.let { snapshot.findOrder(it)?.status },
        selectedArchiveId = selectedArchiveId,
        reviewingEndingId = reviewingEndingId,
        buttons = buttons.mapValues { (_, button) ->
            TerminalButtonView(button.isEnabled, button.isChecked, button.position.width, button.position.height)
        },
    )

    override fun press(control: TerminalControl): TerminalPress {
        plugin.buttonPressed(control)
        return lastPress
    }

    override fun close(): Boolean {
        val currentCallback = callback ?: return false
        if (closed || closeRequested) return false
        closeRequested = true
        currentCallback.dismissCustomDialog(1)
        return true
    }

    /** 触发一次短促闪现 glitch（剧情系统事件驱动，<0.5s 自愈，系统不解释）。 */
    fun triggerGlitch() = plugin.effects.triggerGlitch()

    private fun register(control: TerminalControl, button: com.fs.starfarer.api.ui.ButtonAPI) {
        buttons[control] = button
    }

    private inner class TerminalPlugin : BaseCustomUIPanelPlugin() {
        val effects = TerminalScreenEffects()

        override fun positionChanged(position: PositionAPI) = effects.positionChanged(position)

        override fun advance(amount: Float) = effects.advance(amount)

        override fun render(alphaMult: Float) {
            renderedFrames++
            effects.render(alphaMult)
        }

        override fun processInput(events: List<InputEventAPI>) {
            for (event in events) {
                if (event.isConsumed) continue
                if (event.isMouseDownEvent || event.isKeyDownEvent) {
                    effects.skipBoot()
                }
            }
        }

        override fun buttonPressed(buttonId: Any?) {
            val button = (buttonId as? TerminalControl)?.let { buttons[it] }
            lastPress = when {
                callback == null || closed || closeRequested -> TerminalPress.NOT_OPEN
                button == null -> TerminalPress.NOT_PRESENT
                !button.isEnabled -> TerminalPress.DISABLED
                else -> TerminalPress.DISPATCHED
            }
            if (lastPress != TerminalPress.DISPATCHED) {
                Global.getLogger(DirectorateTerminalDelegate::class.java)
                    .info("ASTD terminal rejected control=$buttonId result=$lastPress revision=$layoutRevision")
                return
            }
            when (buttonId) {
                is TerminalControl.Tab -> {
                    tab = buttonId.tab
                    reviewingEndingId = null
                }
                is TerminalControl.Order -> selectedOrderId = buttonId.id
                is TerminalControl.Archive -> selectedArchiveId = buttonId.id
                is TerminalControl.Ending -> reviewingEndingId = buttonId.id
                ACTION_ACCEPT -> {
                    val order = selectedOrder()
                    if (order != null) {
                        if (source.acceptWorkOrder(order.id)) {
                            HudMessages.campaign(I18n.t(CAT, KEY + "hud.accepted", "code" to order.code), HIGHLIGHT)
                        } else {
                            HudMessages.campaign(I18n[CAT, KEY + "hud.action_failed"], NEGATIVE)
                        }
                    }
                    snapshot = source.snapshot()
                }
                ACTION_TRACK -> {
                    val order = selectedOrder()
                    if (order != null) {
                        if (source.trackWorkOrder(order.id)) {
                            HudMessages.campaign(I18n.t(CAT, KEY + "hud.tracked", "code" to order.code), HIGHLIGHT)
                        } else {
                            HudMessages.campaign(I18n[CAT, KEY + "hud.action_failed"], NEGATIVE)
                        }
                    }
                    snapshot = source.snapshot()
                }
                ACTION_DELIVER -> {
                    val order = selectedOrder()
                    if (order != null) {
                        if (source.requestSettlement(order.id)) {
                            HudMessages.campaign(I18n.t(CAT, KEY + "hud.settle", "code" to order.code), HIGHLIGHT)
                        } else {
                            HudMessages.campaign(I18n[CAT, KEY + "hud.action_failed"], NEGATIVE)
                        }
                    }
                    snapshot = source.snapshot()
                }
                ACTION_SIGN_ENDING -> {
                    val ending = reviewingEndingId?.let { id -> snapshot.endings.firstOrNull { it.id == id } }
                    if (ending != null) {
                        if (ending.available && source.chooseEnding(ending.id)) {
                            HudMessages.campaign(
                                I18n.t(CAT, KEY + "hud.ending", "title" to ending.title), HIGHLIGHT,
                            )
                        } else {
                            HudMessages.campaign(I18n[CAT, KEY + "hud.action_failed"], NEGATIVE)
                        }
                    }
                    reviewingEndingId = null
                    snapshot = source.snapshot()
                }
                ACTION_ENDING_BACK -> reviewingEndingId = null
                else -> return
            }
            rebuild()
        }
    }

    private fun selectedOrder(): WorkOrder? {
        val current = selectedOrderId?.let { snapshot.findOrder(it) }
        if (current != null) return current
        val first = snapshot.allOrders.firstOrNull() ?: return null
        selectedOrderId = first.id
        return first
    }

    private fun selectedArchive(): ArchiveEntry? {
        val current = selectedArchiveId?.let { snapshot.findArchive(it) }
        if (current != null && current.unlocked) return current
        val first = snapshot.archives.firstOrNull { it.unlocked } ?: return null
        selectedArchiveId = first.id
        return first
    }

    // --- 布局 ----------------------------------------------------------------

    private fun metrics(w: Float, h: Float): Metrics {
        val compact = w < 1080f || h < 620f
        return Metrics(
            compact = compact,
            headerH = if (compact) 56f else 68f,
            actionBarH = if (compact) 40f else 46f,
            pad = if (compact) 4f else 6f,
            tabW = if (compact) 104f else 132f,
            tabH = if (compact) 24f else 28f,
            orderRowH = if (compact) 40f else 46f,
            archiveRowH = if (compact) 26f else 30f,
            btnW = if (compact) 140f else 160f,
            btnH = if (compact) 26f else 30f,
        )
    }

    private fun rebuild() {
        for (c in components) root.removeComponent(c)
        components.clear()
        buttons.clear()

        val w = root.position.width
        val h = root.position.height
        val m = metrics(w, h)

        buildHeader(w, m)

        val bodyTop = m.headerH + m.pad
        val bodyH = h - bodyTop - m.pad
        when (tab) {
            TerminalTab.WORK_ORDERS -> buildWorkOrders(w, bodyTop, bodyH, m)
            TerminalTab.ARCHIVES -> buildArchives(w, bodyTop, bodyH, m)
            TerminalTab.ACCOUNT -> buildAccount(w, bodyTop, bodyH, m)
        }
        layoutRevision++
    }

    private fun add(component: UIComponentAPI, x: Float, y: Float) {
        root.addComponent(component).inTL(x, y)
        components += component
    }

    private fun buildHeader(w: Float, m: Metrics) {
        val header = root.createCustomPanel(w, m.headerH, forwarder)

        val titleEl = header.createUIElement(w * 0.5f, 26f, false)
        titleEl.addTitle(I18n[CAT, KEY + "title"])
        header.addUIElement(titleEl).inTL(0f, 0f)

        // 清算序列进度：第二章末之前（null）完全不显示；之后显示剧情脚本推进的百分比。
        val liquidation = snapshot.liquidationProgress
        if (liquidation != null) {
            val progEl = header.createUIElement(w * 0.5f, 24f, false)
            I18nUi.addPara(
                progEl, CAT, KEY + "liquidation.progress", 0f, TEXT,
                "progress" to Misc.getRoundedValueMaxOneAfterDecimal(liquidation) + "%",
            )
            header.addUIElement(progEl).inTR(0f, 2f)
        }

        var x = 0f
        val tabY = m.headerH - m.tabH - 4f
        for (t in TerminalTab.entries) {
            val el = header.createUIElement(m.tabW, m.tabH, false)
            val label = I18n[CAT, KEY + "tab." + t.name.lowercase()]
            val selected = t == tab
            val button = el.addButton(
                label, TerminalControl.Tab(t),
                if (selected) BRIGHT else TEXT,
                if (selected) BASE else BG,
                Alignment.MID, CutStyle.ALL,
                m.tabW, m.tabH, 0f,
            )
            register(TerminalControl.Tab(t), button)
            header.addUIElement(el).inTL(x, tabY)
            x += m.tabW + 8f
        }

        add(header, 0f, 0f)
    }

    // --- Tab 1：工单终端 ------------------------------------------------------

    private fun orderTierText(order: WorkOrder): String =
        if (order.threatTier == THREAT_TIER_UNRATED) {
            I18n[CAT, KEY + "threat.unrated"]
        } else {
            threatTierBadge(order.threatTier)
        }

    private fun buildWorkOrders(w: Float, bodyTop: Float, bodyH: Float, m: Metrics) {
        val leftW = w * if (m.compact) 0.40f else 0.36f
        val rightW = w - leftW - m.pad

        // 左栏：工单列表（按批次分组，组头带结清进度；行双行排版，不挤单行）。
        val left = root.createCustomPanel(leftW, bodyH, forwarder)
        val list = left.createUIElement(leftW - 8f, bodyH - 8f, true)
        if (snapshot.batches.isEmpty()) {
            list.addPara(I18n[CAT, KEY + "orders.empty"], GRAY, 4f)
        }
        for (batch in snapshot.batches) {
            list.addSectionHeading(
                I18n.t(
                    CAT, KEY + "batch.header",
                    "title" to batch.title,
                    "done" to batch.settledCount, "total" to batch.totalCount,
                ),
                if (batch.isCleared) BRIGHT else TEXT, BG, Alignment.MID, 6f,
            )
            for (order in batch.orders) {
                val control = TerminalControl.Order(order.id)
                val row = list.addAreaCheckbox(
                    I18n.t(
                        CAT, KEY + "order.row_multiline",
                        "code" to order.code,
                        "status" to I18n[CAT, KEY + "order.status." + order.status.name.lowercase()],
                        "tier" to orderTierText(order),
                        "title" to order.title,
                    ),
                    control, BASE, BG, BRIGHT, leftW - 16f, m.orderRowH, 4f,
                )
                row.isChecked = order.id == selectedOrder()?.id
                register(control, row)
            }
        }
        left.addUIElement(list).inTL(4f, 4f)
        add(left, 0f, bodyTop)

        // 右栏：文书卡片 + 底部操作条。
        val right = root.createCustomPanel(rightW, bodyH, forwarder)
        val docH = bodyH - m.actionBarH - m.pad
        val doc = right.createUIElement(rightW - 10f, docH, true)
        val order = selectedOrder()
        if (order == null) {
            doc.addPara(I18n[CAT, KEY + "doc.none"], GRAY, 4f)
        } else {
            doc.addSectionHeading(I18n[CAT, KEY + "doc.header"], TEXT, BG, Alignment.MID, 4f)
            I18nUi.addPara(doc, CAT, KEY + "doc.code", 6f, TEXT, "code" to order.code)
            if (order.threatTier == THREAT_TIER_UNRATED) {
                I18nUi.addPara(doc, CAT, KEY + "doc.threat_unrated", 2f, HIGHLIGHT)
            }
            I18nUi.addPara(doc, CAT, KEY + "doc.commission", 4f, TEXT, "text" to order.commission)
            if (order.clauses.isNotEmpty()) {
                doc.addPara(I18n[CAT, KEY + "doc.clauses"], TEXT, 6f)
                for (clause in order.clauses) {
                    I18nUi.addPara(doc, CAT, KEY + "doc.clause_item", 2f, TEXT, "text" to clause)
                }
            }
            I18nUi.addPara(
                doc, CAT, KEY + "doc.reward", 6f, TEXT,
                "amount" to Misc.getDGSCredits(order.reward.toFloat()),
            )
            if (order.issueDate.isNotBlank()) {
                I18nUi.addPara(doc, CAT, KEY + "doc.issue_date", 4f, TEXT, "date" to order.issueDate)
            }
            if (order.remark.isNotBlank()) {
                I18nUi.addPara(doc, CAT, KEY + "doc.remark", 4f, TEXT, "text" to order.remark)
            }
            if (order.targetSummary.isNotBlank()) {
                doc.addPara(order.targetSummary, GRAY, 6f)
            }
            doc.addPara(I18n[CAT, KEY + "doc.seal"], GRAY, 8f)
        }
        right.addUIElement(doc).inTL(5f, 5f)

        val bar = right.createCustomPanel(rightW, m.actionBarH, forwarder)
        val barEl = bar.createUIElement(rightW, m.actionBarH - 6f, false)
        when (order?.status) {
            WorkOrderStatus.AVAILABLE -> register(TerminalControl.Action.ACCEPT, barEl.addButton(
                I18n[CAT, KEY + "action.accept"], TerminalControl.Action.ACCEPT, TEXT, BG, Alignment.MID,
                CutStyle.ALL, m.btnW, m.btnH, 4f,
            ))
            WorkOrderStatus.ACTIVE -> register(TerminalControl.Action.TRACK, barEl.addButton(
                I18n[CAT, KEY + "action.track"], TerminalControl.Action.TRACK, TEXT, BG, Alignment.MID,
                CutStyle.ALL, m.btnW, m.btnH, 4f,
            ))
            WorkOrderStatus.READY_TO_SETTLE -> register(TerminalControl.Action.DELIVER, barEl.addButton(
                I18n[CAT, KEY + "action.deliver"], TerminalControl.Action.DELIVER, TEXT, BG, Alignment.MID,
                CutStyle.ALL, m.btnW, m.btnH, 4f,
            ))
            WorkOrderStatus.SETTLED ->
                barEl.addPara(I18n[CAT, KEY + "doc.stamp_settled"], HIGHLIGHT, 8f)
            null -> {}
        }
        bar.addUIElement(barEl).inTL(0f, 4f)
        right.addComponent(bar).inBL(0f, 0f)

        add(right, leftW + m.pad, bodyTop)
    }

    // --- Tab 2：档案室 ---------------------------------------------------------

    private fun buildArchives(w: Float, bodyTop: Float, bodyH: Float, m: Metrics) {
        val leftW = w * if (m.compact) 0.40f else 0.36f
        val rightW = w - leftW - m.pad

        val left = root.createCustomPanel(leftW, bodyH, forwarder)
        val list = left.createUIElement(leftW - 8f, bodyH - 8f, true)
        if (snapshot.archives.isEmpty()) {
            list.addPara(I18n[CAT, KEY + "archive.empty"], GRAY, 4f)
        }
        for ((layer, entries) in snapshot.archiveLayers) {
            list.addSectionHeading(
                I18n.t(CAT, KEY + "archive.layer", "layer" to layer), TEXT, BG, Alignment.MID, 6f,
            )
            for (entry in entries) {
                if (entry.unlocked) {
                    val control = TerminalControl.Archive(entry.id)
                    val row = list.addAreaCheckbox(
                        entry.title, control, BASE, BG, BRIGHT,
                        leftW - 16f, m.archiveRowH, 4f,
                    )
                    row.isChecked = entry.id == selectedArchive()?.id
                    register(control, row)
                } else {
                    // 未解锁：灰色存目条目（标题留存目，正文依保密条令不予展示），不可点。
                    list.addPara(
                        I18n.t(CAT, KEY + "archive.locked_row", "title" to entry.title), GRAY, 6f,
                    )
                }
            }
        }
        left.addUIElement(list).inTL(4f, 4f)
        add(left, 0f, bodyTop)

        val right = root.createCustomPanel(rightW, bodyH, forwarder)
        val reader = right.createUIElement(rightW - 10f, bodyH - 10f, true)
        val entry = selectedArchive()
        if (entry == null) {
            reader.addPara(I18n[CAT, KEY + "archive.none"], GRAY, 4f)
        } else {
            reader.addSectionHeading(entry.title, TEXT, BG, Alignment.MID, 4f)
            for (para in entry.body) {
                reader.addPara(para, TEXT, 6f)
            }
        }
        right.addUIElement(reader).inTL(5f, 5f)
        add(right, leftW + m.pad, bodyTop)
    }

    // --- Tab 3：承包商账户 -----------------------------------------------------

    private fun buildAccount(w: Float, bodyTop: Float, bodyH: Float, m: Metrics) {
        val reviewing = reviewingEndingId?.let { id -> snapshot.endings.firstOrNull { it.id == id } }
        if (reviewing != null) {
            buildEndingReview(w, bodyTop, bodyH, m, reviewing)
            return
        }

        val panel = root.createCustomPanel(w, bodyH, forwarder)
        val el = panel.createUIElement(w - 10f, bodyH - 10f, true)

        el.addSectionHeading(I18n[CAT, KEY + "account.section"], TEXT, BG, Alignment.MID, 2f)
        I18nUi.addPara(el, CAT, KEY + "account.id", 6f, TEXT, "id" to snapshot.contractorId)
        I18nUi.addPara(el, CAT, KEY + "account.level", 2f, TEXT, "level" to snapshot.contractorLevel)
        I18nUi.addPara(el, CAT, KEY + "account.cycle", 2f, TEXT, "cycle" to snapshot.registerCycle)

        el.addSectionHeading(I18n[CAT, KEY + "account.ledger"], TEXT, BG, Alignment.MID, 10f)
        if (snapshot.ledger.isEmpty()) {
            el.addPara(I18n[CAT, KEY + "account.ledger_empty"], GRAY, 4f)
        }
        for (entry in snapshot.ledger) {
            I18nUi.addPara(
                el, CAT, KEY + "account.ledger_row", 3f, TEXT,
                "code" to entry.orderCode,
                "date" to entry.date,
                "amount" to Misc.getDGSCredits(entry.amount.toFloat()),
                "note" to entry.note,
            )
        }

        if (snapshot.endings.isNotEmpty()) {
            el.addSectionHeading(I18n[CAT, KEY + "ending.section"], TEXT, BG, Alignment.MID, 10f)
            el.addPara(I18n[CAT, KEY + "ending.pick_hint"], GRAY, 2f)
            for (ending in snapshot.endings) {
                val control = TerminalControl.Ending(ending.id)
                val btn = el.addButton(
                    ending.title, control, TEXT, BG, Alignment.MID,
                    CutStyle.ALL, if (m.compact) 180f else 200f, if (m.compact) 24f else 28f, 4f,
                )
                btn.isEnabled = ending.available
                register(control, btn)
                if (!ending.available) {
                    el.addPara(I18n[CAT, KEY + "ending.unavailable"], GRAY, 2f)
                }
            }
        }

        // 角落常驻小字：托管声明。
        el.addPara(I18n[CAT, KEY + "account.trust_note"], GRAY, 12f)

        panel.addUIElement(el).inTL(5f, 5f)
        add(panel, 0f, bodyTop)
    }

    /**
     * 终局拟制文书审阅视图：先读文书，再明确确认签署；返回/关闭终端即搁置，不签署。
     */
    private fun buildEndingReview(w: Float, bodyTop: Float, bodyH: Float, m: Metrics, ending: EndingOption) {
        val panel = root.createCustomPanel(w, bodyH, forwarder)
        val docH = bodyH - m.actionBarH - m.pad
        val doc = panel.createUIElement(w - 10f, docH, true)

        doc.addSectionHeading(I18n[CAT, KEY + "ending.review_header"], HIGHLIGHT, BG, Alignment.MID, 2f)
        doc.addPara(ending.title, BRIGHT, 8f)
        doc.addPara(I18n[CAT, KEY + "ending.review_notice"], GRAY, 4f)
        for (para in ending.description.split('\n')) {
            if (para.isNotBlank()) {
                doc.addPara(para, TEXT, 6f)
            }
        }
        panel.addUIElement(doc).inTL(5f, 5f)

        val bar = panel.createCustomPanel(w, m.actionBarH, forwarder)
        val barEl = bar.createUIElement(w, m.actionBarH - 6f, false)
        val sign = barEl.addButton(
            I18n[CAT, KEY + "ending.confirm"], ACTION_SIGN_ENDING, BRIGHT, BG, Alignment.MID,
            CutStyle.ALL, m.btnW, m.btnH, 4f,
        )
        sign.isEnabled = ending.available
        register(ACTION_SIGN_ENDING, sign)
        val back = barEl.addButton(
            I18n[CAT, KEY + "ending.back"], ACTION_ENDING_BACK, TEXT, BG, Alignment.MID,
            CutStyle.ALL, m.btnW + 40f, m.btnH, 4f,
        )
        register(ACTION_ENDING_BACK, back)
        bar.addUIElement(barEl).inTL(0f, 4f)
        panel.addComponent(bar).inBL(0f, 0f)

        add(panel, 0f, bodyTop)
    }
}
