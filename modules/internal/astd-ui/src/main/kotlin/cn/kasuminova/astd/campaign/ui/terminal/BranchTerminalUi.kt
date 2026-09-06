package cn.kasuminova.astd.campaign.ui.terminal

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.Fonts
import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.ui.PositionAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.ui.LazyFont

/**
 * 分局终端全屏 UI 入口与对话代理（doc 00；原型 `tools/mod-ui-preview/`）。
 *
 * 布局（近全屏）：
 * - 顶栏：局名 + mini 徽记 + 三 tab（原版 area checkbox）+ 日期/清算进度（第三章起）；
 * - 主体：左列表栏（批次分组/状态章）+ 右详情栏（文书卡/阅读器/账户明细）；
 * - 底部操作条：主操作按钮（按选中单状态切换）+ 托管小字 + 关闭终端。
 *
 * 特效由根面板插件 [BranchTerminalPlugin] 每帧驱动（本类持有全部时间线时钟并调度
 * [TerminalEffect]）；文书卡全息面板底/电子签章/印戳见 [HoloDocPlugin]。
 */
object BranchTerminalUi {

    /** 打开分局终端（近全屏自定义可视对话框，无确认/取消键与 Enter 快捷键，关闭只走终端自身的 Esc/关闭按钮）。 */
    fun open(dialog: InteractionDialogAPI, backend: BranchTerminalBackend, tab: TerminalTab) {
        val settings = Global.getSettings()
        val w = (settings.screenWidth * 0.94f).coerceAtMost(1800f)
        val h = (settings.screenHeight * 0.92f).coerceAtMost(1000f)
        dialog.showCustomVisualDialog(w, h, BranchTerminalDelegate(backend, tab, w, h))
    }
}

/** 终端对话框代理（控件树装配 + 特效调度）。 */
class BranchTerminalDelegate(
    private val backend: BranchTerminalBackend,
    initialTab: TerminalTab,
    private val panelW: Float,
    private val panelH: Float,
) : CustomVisualDialogDelegate {

    // ─── 按钮 id ───

    private data class TabButtonId(val tab: TerminalTab)
    private data class OrderRowButtonId(val key: String)
    private data class ArchiveRowButtonId(val id: String)
    private enum class ActionButtonId { PRIMARY, REPLAY_RECEIPT, CLOSE, RECEIPT_CLOSE, NARRATIVE_CLOSE }

    // 第五章结局事务卡按钮 id
    private data class ArchivalDocButtonId(val choice: ArchivalChoice)
    private data class TradeFactionButtonId(val factionId: String)
    private data class ExecutorSpecButtonId(val spec: ExecutorSpec)
    private object SignButtonId
    private data class ShipPickButtonId(val memberId: String)
    private data class MarketPickButtonId(val marketId: String)

    private data class PendingEffect(var remaining: Float, val effect: TerminalEffect)

    /** 一条待逐行打印的文本行（标签 + 全文；打印推进时按前缀 setText）。 */
    private data class PrintTarget(val label: LabelAPI, val fullText: String)

    private val controller = TerminalController(backend, initialTab)

    /** 根面板特效插件（扫描线/glitch/开机场/震屏/输入）。 */
    val rootPlugin = BranchTerminalPlugin(this)

    /**
     * 按钮事件转发插件（无状态，全部承载按钮的面板共用同一实例）：
     * 原版按钮事件不冒泡，topPanel/detailPanel/bottomPanel/行面板/回执面板各挂一个，
     * 把 buttonPressed 转发到 [onButton]。
     */
    private val buttonRelay = ButtonRelayPlugin(::onButton)

    private lateinit var callbacks: CustomVisualDialogDelegate.DialogCallbacks
    private lateinit var panel: CustomPanelAPI

    private lateinit var topPanel: CustomPanelAPI
    private lateinit var listPanel: CustomPanelAPI
    private lateinit var detailPanel: CustomPanelAPI
    private lateinit var bottomPanel: CustomPanelAPI

    /** 各区域已装配组件（重建时移除）。 */
    private val topComponents = mutableListOf<UIComponentAPI>()
    private val listComponents = mutableListOf<UIComponentAPI>()
    private val detailComponents = mutableListOf<UIComponentAPI>()
    private val bottomComponents = mutableListOf<UIComponentAPI>()

    // ─── 特效时钟（根插件每帧读取） ───

    /** 待机呼吸时钟（常驻累计秒）。 */
    var time: Float = 0f
        private set

    /** 开机场经过秒数。 */
    var bootT: Float = 0f
        private set

    private var bootDone: Boolean = false
    private var bootSweepSoundFired = false
    private var bootEmblemSoundFired = false

    /** 开机场是否仍在播放（未跳过且未播完）。 */
    val bootActive: Boolean get() = !bootDone

    /** 盖章动效经过秒数（无动效为 NaN）。 */
    var stampT: Float = Float.NaN
        private set

    private var stampKind: StampKind = StampKind.ACCEPTED
    private var stampSeed: Int = 0

    /** 闪现 glitch 经过秒数（无 glitch 为 NaN）。 */
    var glitchT: Float = Float.NaN
        private set

    private var glitchSpec: GlitchSpec? = null

    /** glitch 相位种子（多次闪现抖动序列不同）。 */
    var glitchSeed: Int = 0
        private set

    /** 工单行状态章（key → 标签 + 原始文案/颜色；glitch 瞬替目标查找用，列表重建时重填）。 */
    private data class OrderStatusLabel(val label: LabelAPI, val text: String, val color: java.awt.Color)

    private val orderStatusLabels = HashMap<String, OrderStatusLabel>()

    /** glitch「目标状态：现役？」瞬替目标行工单 key（控制器在核销后快照中选定的已核销行）。 */
    private var glitchTargetKey: String? = null

    /** 当前被瞬替的状态章（glitch 自愈时恢复原文案/颜色）。 */
    private var glitchSwapped: OrderStatusLabel? = null

    // ─── 打印状态 ───

    private var printer: LinePrinter? = null
    private var printTargets: List<PrintTarget> = emptyList()
    private var printedTexts: MutableList<String> = mutableListOf()

    // ─── 回执状态 ───

    private var receiptOverlay: CustomPanelAPI? = null
    private var receiptPrinter: LinePrinter? = null
    private var receiptTargets: List<PrintTarget> = emptyList()
    private var receiptTexts: MutableList<String> = mutableListOf()
    private var receiptAmountLabel: LabelAPI? = null
    private var receiptAmountPrefix: String = ""
    private var receiptAmountTarget: Int = 0
    private var receiptRollT: Float = Float.NaN

    // ─── 叙事回执链状态（签署/签发演出；关闭当前页弹出下一页） ───

    private var narrativeOverlay: CustomPanelAPI? = null
    private var narrativeQueue: MutableList<NarrativeReceiptView> = mutableListOf()
    private var narrativePrinter: LinePrinter? = null
    private var narrativeTargets: List<PrintTarget> = emptyList()
    private var narrativeTexts: MutableList<String> = mutableListOf()
    private var narrativeAmountLabel: LabelAPI? = null
    private var narrativeAmountPrefix: String = ""
    private var narrativeAmountTarget: Int = 0
    private var narrativeRollT: Float = Float.NaN

    // ─── 特效调度 ───

    private val pending = mutableListOf<PendingEffect>()

    // 开机场文案（渲染期每帧读取，打开时解析一次）
    private val bootEmblem: String = I18n[TerminalStyle.CAT, "ui.terminal.boot.emblem"]
    private val bootSubtitle: String = I18n[TerminalStyle.CAT, "ui.terminal.boot.subtitle"]
    private val bootSkipHint: String = I18n[TerminalStyle.CAT, "ui.terminal.boot.skip"]

    fun bootEmblemText(): String = bootEmblem
    fun bootSubtitleText(): String = bootSubtitle
    fun bootSkipHintText(): String = bootSkipHint

    private val mainH: Float
        get() = panelH - TerminalStyle.PAD * 2 - TerminalStyle.TOP_BAR_H - TerminalStyle.BOTTOM_BAR_H - TerminalStyle.GAP * 2
    private val detailW: Float
        get() = panelW - TerminalStyle.PAD * 2 - TerminalStyle.LIST_W - TerminalStyle.GAP

    // ─── CustomVisualDialogDelegate ───

    override fun init(panel: CustomPanelAPI, callbacks: CustomVisualDialogDelegate.DialogCallbacks) {
        this.panel = panel
        this.callbacks = callbacks

        val contentW = panelW - TerminalStyle.PAD * 2
        topPanel = panel.createCustomPanel(contentW, TerminalStyle.TOP_BAR_H, buttonRelay)
        panel.addComponent(topPanel).inTL(TerminalStyle.PAD, TerminalStyle.PAD)

        listPanel = panel.createCustomPanel(TerminalStyle.LIST_W, mainH, BaseCustomUIPanelPlugin())
        panel.addComponent(listPanel).inTL(TerminalStyle.PAD, TerminalStyle.PAD + TerminalStyle.TOP_BAR_H + TerminalStyle.GAP)

        detailPanel = panel.createCustomPanel(detailW, mainH, buttonRelay)
        panel.addComponent(detailPanel).inTL(
            TerminalStyle.PAD + TerminalStyle.LIST_W + TerminalStyle.GAP,
            TerminalStyle.PAD + TerminalStyle.TOP_BAR_H + TerminalStyle.GAP,
        )

        bottomPanel = panel.createCustomPanel(contentW, TerminalStyle.BOTTOM_BAR_H, buttonRelay)
        panel.addComponent(bottomPanel).inBL(TerminalStyle.PAD, TerminalStyle.PAD)

        buildTopbar()
        buildListPane()
        buildDetailPane()
        buildBottomBar()
    }

    /** 终端底色已自带暗纹理，不叠加原版噪点。 */
    override fun getNoiseAlpha(): Float = 0f

    /** 帧推进由根面板插件 [BranchTerminalPlugin.advance] 驱动（面板在 UI 树内照常收 advance）。 */
    override fun advance(amount: Float) {}

    override fun reportDismissed(option: Int) {}

    override fun getCustomPanelPlugin() = rootPlugin

    // ─── 帧推进（根插件 advance 调用） ───

    fun tick(amount: Float) {
        if (amount <= 0f) return
        time += amount

        if (bootActive) {
            val prev = bootT
            bootT += amount
            if (!bootSweepSoundFired && bootT >= BootTimeline.SWEEP_START) {
                bootSweepSoundFired = true
                TerminalStyle.play(TerminalSound.BOOT_SWEEP)
            }
            if (!bootEmblemSoundFired && bootT >= BootTimeline.EMBLEM_START) {
                bootEmblemSoundFired = true
                TerminalStyle.play(TerminalSound.BOOT_EMBLEM)
            }
            if (BootTimeline.finished(bootT)) bootDone = true
        }

        if (!stampT.isNaN()) {
            stampT += amount
            // 震屏经根面板对齐偏移施加：setXAlignOffset/setYAlignOffset 每帧驱动的实际观感
            // 待集成测试阶段实机验证；若无效需改渲染层整体平移（render 期对全部直绘加偏移）
            val pos = rootPlugin.panelPos
            if (pos != null) {
                val (dx, dy) = StampTimeline.shakeOffset(stampT, stampSeed)
                pos.setXAlignOffset(dx)
                pos.setYAlignOffset(dy)
            }
            if (stampT > StampTimeline.SETTLE + 0.1f) {
                stampT = Float.NaN
                rootPlugin.panelPos?.setXAlignOffset(0f)
                rootPlugin.panelPos?.setYAlignOffset(0f)
            }
        }

        if (!glitchT.isNaN()) {
            glitchT += amount
            if (!GlitchTimeline.active(glitchT)) endGlitch()
        }

        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.remaining -= amount
            if (p.remaining <= 0f) {
                it.remove()
                execute(p.effect)
            }
        }

        if (!bootActive) tickPrinter(amount)
        tickReceipt(amount)
        tickNarrative(amount)
    }

    /** 跳过开机场（任意键/点击）。 */
    fun skipBoot() {
        if (!bootActive) return
        bootDone = true
        TerminalStyle.play(TerminalSound.SELECT)
    }

    /** Esc：叙事/回执开启时先收 overlay，否则关闭终端。 */
    fun onEscape() {
        if (narrativeOverlay != null) {
            closeNarrative()
        } else if (receiptOverlay != null) {
            closeReceipt()
        } else if (pending.none { it.effect.blocksClose }) {
            TerminalStyle.play(TerminalSound.CLOSE)
            callbacks.dismissDialog()
        }
        // else：结算成功 → 回执弹出的窗口内暂吞关闭（Esc/关闭按钮同路），
        // 保证「盖章 → 回执打印」叙事链在终端内完整（HUD 回执兜底不受影响）
    }

    /** 按钮路由（各承载按钮面板的 [ButtonRelayPlugin] 转发至此）。 */
    fun onButton(id: Any?) {
        when (id) {
            is TabButtonId -> applyEffects(controller.selectTab(id.tab))
            is OrderRowButtonId -> applyEffects(controller.selectOrder(id.key))
            is ArchiveRowButtonId -> applyEffects(controller.selectArchive(id.id))
            ActionButtonId.PRIMARY -> applyEffects(controller.pressPrimary())
            ActionButtonId.REPLAY_RECEIPT -> applyEffects(controller.pressReplayReceipt())
            ActionButtonId.CLOSE -> onEscape()
            ActionButtonId.RECEIPT_CLOSE -> closeReceipt()
            ActionButtonId.NARRATIVE_CLOSE -> closeNarrative()
            is ArchivalDocButtonId -> applyEffects(controller.selectArchivalDoc(id.choice))
            is TradeFactionButtonId -> applyEffects(controller.selectTradeFaction(id.factionId))
            SignButtonId -> applyEffects(controller.confirmSign())
            is ExecutorSpecButtonId -> applyEffects(controller.chooseExecutorSpec(id.spec))
            is ShipPickButtonId -> applyEffects(controller.assignShip(id.memberId))
            is MarketPickButtonId -> applyEffects(controller.appointMarket(id.marketId))
        }
    }

    private fun applyEffects(effects: List<TerminalEffect>) {
        for (effect in effects) {
            if (effect.delaySeconds <= 0f) execute(effect) else pending += PendingEffect(effect.delaySeconds, effect)
        }
    }

    private fun execute(effect: TerminalEffect) {
        when (effect) {
            is TerminalEffect.PlaySound -> TerminalStyle.play(effect.sound)
            is TerminalEffect.RefreshTopbar -> buildTopbar()
            is TerminalEffect.RebuildList -> {
                buildListPane()
                buildBottomBar()
            }
            is TerminalEffect.ReprintDetail -> buildDetailPane()
            is TerminalEffect.StampSlam -> {
                stampT = 0f
                stampKind = effect.kind
                stampSeed++
            }
            is TerminalEffect.ShowReceipt -> openReceipt(effect.receipt, effect.glitchSpec)
            is TerminalEffect.ShowNarrative -> openNarrative(effect.pages)
            is TerminalEffect.GlitchFx -> startGlitch(effect.spec, effect.targetOrderKey)
        }
    }

    // ─── glitch ───

    private fun startGlitch(spec: GlitchSpec, targetOrderKey: String?) {
        glitchT = 0f
        glitchSpec = spec
        glitchSeed++
        glitchTargetKey = targetOrderKey
        if (spec == GlitchSpec.TARGET_STATUS) swapGlitchLabel()
    }

    private fun swapGlitchLabel() {
        // 目标行不在当前列表（如 glitch 期间切 tab）时放弃文字瞬替，仅保留噪点/撕裂
        val target = glitchTargetKey?.let(orderStatusLabels::get) ?: return
        glitchSwapped = target
        target.label.setText(I18n[TerminalStyle.CAT, "ui.terminal.glitch.target_status"])
        target.label.setColor(TerminalStyle.glitchRed)
    }

    private fun endGlitch() {
        glitchT = Float.NaN
        glitchSwapped?.let {
            it.label.setText(it.text)
            it.label.setColor(it.color)
        }
        glitchSwapped = null
        glitchTargetKey = null
        glitchSpec = null
    }

    // ─── 逐行打印推进 ───

    private fun tickPrinter(amount: Float) {
        val pr = printer ?: return
        val events = pr.advance(amount)
        for (event in events) {
            when (event) {
                is LinePrinter.Event.LineStarted -> TerminalStyle.play(TerminalSound.PRINT_LINE)
                is LinePrinter.Event.JamLineErased -> printTargets.getOrNull(event.index)?.label?.setText("")
                LinePrinter.Event.AllDone -> {}
            }
        }
        for (i in printTargets.indices) {
            val text = pr.visibleText(i)
            if (text != printedTexts[i]) {
                printedTexts[i] = text
                printTargets[i].label.setText(text)
            }
        }
        if (pr.done) printer = null
    }

    private fun tickReceipt(amount: Float) {
        if (receiptOverlay == null) return
        val pr = receiptPrinter
        if (pr != null && !pr.done) {
            val events = pr.advance(amount)
            for (event in events) {
                when (event) {
                    is LinePrinter.Event.LineStarted -> TerminalStyle.play(TerminalSound.PRINT_LINE)
                    is LinePrinter.Event.JamLineErased -> receiptTargets.getOrNull(event.index)?.label?.setText("")
                    LinePrinter.Event.AllDone -> {
                        // 明细打印完毕 → 金额数字滚动到位
                        receiptRollT = 0f
                        TerminalStyle.play(TerminalSound.RECEIPT_ROLL)
                    }
                }
            }
            for (i in receiptTargets.indices) {
                val text = pr.visibleText(i)
                if (text != receiptTexts[i]) {
                    receiptTexts[i] = text
                    receiptTargets[i].label.setText(text)
                }
            }
            return
        }
        if (!receiptRollT.isNaN()) {
            receiptRollT += amount
            val rolled = ReceiptRoll.amountAt(receiptAmountTarget, receiptRollT)
            receiptAmountLabel?.setText(receiptAmountPrefix + Misc.getDGSCredits(rolled.toFloat()))
            if (ReceiptRoll.done(receiptRollT)) receiptRollT = Float.NaN
        }
    }

    // ─── 顶栏 ───

    private fun buildTopbar() {
        clearComponents(topPanel, topComponents)
        val view = controller.view()
        val snapshot = backend.snapshot()

        // mini 徽记
        val emblemPanel = topPanel.createCustomPanel(34f, 34f, MiniEmblemPlugin())
        topPanel.addComponent(emblemPanel).inTL(0f, 7f)
        topComponents += emblemPanel

        val titleTT = topPanel.createUIElement(360f, TerminalStyle.TOP_BAR_H, false)
        titleTT.addPara(I18n[TerminalStyle.CAT, "ui.terminal.topbar.title"], TerminalStyle.tealBright, 13f)
        topPanel.addUIElement(titleTT).inTL(46f, 0f)
        topComponents += titleTT

        // tab 栏
        var prevTab: TooltipMakerAPI? = null
        for (tab in TerminalTab.entries) {
            val tabTT = topPanel.createUIElement(TerminalStyle.TAB_W, TerminalStyle.TAB_H, false)
            val tabName = when (tab) {
                TerminalTab.ORDERS -> I18n[TerminalStyle.CAT, "ui.terminal.tab.orders"]
                TerminalTab.ARCHIVES -> I18n[TerminalStyle.CAT, "ui.terminal.tab.archives"]
                TerminalTab.ACCOUNT -> I18n[TerminalStyle.CAT, "ui.terminal.tab.account"]
            }
            val button = tabTT.addAreaCheckbox(
                tabName, TabButtonId(tab),
                TerminalStyle.teal, TerminalStyle.tealDark, TerminalStyle.tealBright,
                TerminalStyle.TAB_W, TerminalStyle.TAB_H, 0f,
            )
            button.isChecked = tab == view.tab
            val pos = topPanel.addUIElement(tabTT)
            if (prevTab == null) pos.inTL(420f, 9f) else pos.rightOfTop(prevTab, 6f)
            prevTab = tabTT
            topComponents += tabTT
        }

        // 右侧：日期 + 清算序列进度（第三章起常驻）
        val rightTT = topPanel.createUIElement(460f, TerminalStyle.TOP_BAR_H, false)
        val date = rightTT.addPara(snapshot.currentDate, TerminalStyle.gray, 4f)
        date.setAlignment(Alignment.TR)
        if (view.showLiquidationTopbar) {
            val progress = rightTT.addPara(
                I18n.t(
                    TerminalStyle.CAT, "ui.terminal.topbar.liquidation",
                    "progress" to formatProgress(snapshot.liquidationProgress),
                ),
                TerminalStyle.orange, 2f,
            )
            progress.setAlignment(Alignment.TR)
        }
        topPanel.addUIElement(rightTT).inTR(0f, 4f)
        topComponents += rightTT
    }

    // ─── 列表栏 ───

    private fun buildListPane() {
        clearComponents(listPanel, listComponents)
        orderStatusLabels.clear()
        val view = controller.view()
        val tt = listPanel.createUIElement(TerminalStyle.LIST_W - 8f, mainH - 8f, true)
        listPanel.addUIElement(tt).inTL(4f, 4f)
        listComponents += tt

        when (view.tab) {
            TerminalTab.ORDERS -> buildOrderList(tt, view)
            TerminalTab.ARCHIVES -> buildArchiveList(tt, view)
            TerminalTab.ACCOUNT -> buildLedgerList(tt, view)
        }

        // 列表重建恰逢 glitch 激活：瞬替目标重绑到重建后的新标签
        if (!glitchT.isNaN() && glitchSpec == GlitchSpec.TARGET_STATUS) swapGlitchLabel()
    }

    private fun buildOrderList(tt: TooltipMakerAPI, view: TerminalViewData) {
        if (view.batches.isEmpty()) {
            tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.empty.orders"], TerminalStyle.gray, 10f)
            return
        }
        for (batch in view.batches) {
            tt.addSectionHeading(
                I18n.t(
                    TerminalStyle.CAT, "ui.terminal.batch.header",
                    "name" to I18n[TerminalStyle.CAT, "story.account.group.${batch.groupId}"],
                    "done" to batch.settled,
                    "total" to batch.total,
                ),
                TerminalStyle.teal, TerminalStyle.tealDark, Alignment.TL, 8f,
            )
            for (order in batch.orders) {
                addOrderRow(tt, order, order.key == view.selectedOrder?.key)
            }
        }
    }

    private fun addOrderRow(tt: TooltipMakerAPI, order: TerminalOrderView, selected: Boolean) {
        val rowW = TerminalStyle.LIST_W - 24f
        val rowPanel = listPanel.createCustomPanel(rowW, TerminalStyle.ROW_H, buttonRelay)

        val checkTT = rowPanel.createUIElement(rowW, TerminalStyle.ROW_H, false)
        val button = checkTT.addAreaCheckbox(
            "", OrderRowButtonId(order.key),
            TerminalStyle.teal, TerminalStyle.tealDark, TerminalStyle.tealBright,
            rowW, TerminalStyle.ROW_H, 0f,
        )
        button.isChecked = selected
        rowPanel.addUIElement(checkTT).inTL(0f, 0f)

        val statusColor = statusColor(order.status)
        val textColor = if (order.status == OrderStatus.SETTLED) TerminalStyle.gray else TerminalStyle.text
        val tier = if (order.dangerOmitted) {
            I18n[TerminalStyle.CAT, "ui.terminal.tier.omitted"]
        } else {
            I18n[TerminalStyle.CAT, "ui.terminal.tier.${order.threatTier}"]
        }
        val summary = order.summary

        val textTT = rowPanel.createUIElement(rowW - TerminalStyle.STATUS_W - 14f, TerminalStyle.ROW_H, false)
        textTT.addPara("$tier  ${order.serial}　$summary", 0f, TerminalStyle.orange, tier).setColor(textColor)
        rowPanel.addUIElement(textTT).inTL(8f, 4f)

        val statusTT = rowPanel.createUIElement(TerminalStyle.STATUS_W, TerminalStyle.ROW_H, false)
        val statusText = statusText(order.status)
        val statusLabel = statusTT.addPara(statusText, statusColor, 4f)
        statusLabel.setAlignment(Alignment.MID)
        rowPanel.addUIElement(statusTT).inTR(4f, 0f)

        orderStatusLabels[order.key] = OrderStatusLabel(statusLabel, statusText, statusColor)

        tt.addCustom(rowPanel, 3f)
    }

    private fun buildArchiveList(tt: TooltipMakerAPI, view: TerminalViewData) {
        if (view.archiveLayers.isEmpty()) {
            tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.empty.archives"], TerminalStyle.gray, 10f)
            return
        }
        // 档案室已归档只读（公开/交易选；封存选保留全部访问权，不显示本行）
        if (view.ending.snapshot.archivesReadOnly) {
            tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.archive.readonly"], TerminalStyle.orange, 6f)
        }
        for (layer in view.archiveLayers) {
            tt.addSectionHeading(
                I18n.t(
                    TerminalStyle.CAT, "ui.terminal.archive.layer_header",
                    "layer" to layer.layer,
                    "unlocked" to layer.unlocked,
                    "total" to layer.total,
                ),
                TerminalStyle.teal, TerminalStyle.tealDark, Alignment.TL, 8f,
            )
            for (entry in layer.entries) {
                addArchiveRow(tt, entry, entry.id == view.selectedArchive?.id)
            }
        }
    }

    private fun addArchiveRow(tt: TooltipMakerAPI, entry: ArchiveEntryView, selected: Boolean) {
        val rowW = TerminalStyle.LIST_W - 24f
        val rowPanel = listPanel.createCustomPanel(rowW, TerminalStyle.ROW_H, buttonRelay)
        val index = I18n.t(
            TerminalStyle.CAT, "ui.terminal.archive.index",
            "index" to "%02d".format(entry.indexInLayer),
        )
        val title = I18n[TerminalStyle.CAT, "story.archive.${entry.id}.title"]

        if (entry.unlocked) {
            val checkTT = rowPanel.createUIElement(rowW, TerminalStyle.ROW_H, false)
            val button = checkTT.addAreaCheckbox(
                "", ArchiveRowButtonId(entry.id),
                TerminalStyle.teal, TerminalStyle.tealDark, TerminalStyle.tealBright,
                rowW, TerminalStyle.ROW_H, 0f,
            )
            button.isChecked = selected
            rowPanel.addUIElement(checkTT).inTL(0f, 0f)
        }

        val textColor = if (entry.unlocked) TerminalStyle.text else TerminalStyle.gray
        val textTT = rowPanel.createUIElement(rowW - TerminalStyle.STATUS_W - 14f, TerminalStyle.ROW_H, false)
        val label = textTT.addPara("$index  $title", textColor, 0f)
        if (!entry.unlocked) label.italicize()
        rowPanel.addUIElement(textTT).inTL(8f, 4f)

        val statusTT = rowPanel.createUIElement(TerminalStyle.STATUS_W, TerminalStyle.ROW_H, false)
        val statusLabel = statusTT.addPara(
            I18n[TerminalStyle.CAT, if (entry.unlocked) "ui.terminal.archive.status.read" else "ui.terminal.archive.status.locked"],
            if (entry.unlocked) TerminalStyle.teal else TerminalStyle.gray,
            4f,
        )
        statusLabel.setAlignment(Alignment.MID)
        rowPanel.addUIElement(statusTT).inTR(4f, 0f)

        tt.addCustom(rowPanel, 3f)
    }

    private fun buildLedgerList(tt: TooltipMakerAPI, view: TerminalViewData) {
        tt.addSectionHeading(
            I18n.t(TerminalStyle.CAT, "ui.terminal.ledger.header", "count" to view.account.ledger.size),
            TerminalStyle.teal, TerminalStyle.tealDark, Alignment.TL, 8f,
        )
        if (view.account.ledger.isEmpty()) {
            tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.empty.ledger"], TerminalStyle.gray, 10f)
            return
        }
        for (line in view.account.ledger) {
            val title = ledgerLineTitle(line)
            val kindText = I18n[TerminalStyle.CAT, when (line.kind) {
                LedgerKind.ORDER -> "ui.terminal.ledger.kind.order"
                LedgerKind.GROUP_BONUS -> "ui.terminal.ledger.kind.bonus"
            }]
            tt.addPara("$title　$kindText", TerminalStyle.text, 4f).setHighlight(title)
        }
    }

    // ─── 详情栏 ───

    private fun buildDetailPane() {
        clearComponents(detailPanel, detailComponents)
        printer = null
        printTargets = emptyList()
        printedTexts = mutableListOf()
        val view = controller.view()
        when (view.tab) {
            TerminalTab.ORDERS -> buildOrderDoc(view)
            TerminalTab.ARCHIVES -> buildArchiveReader(view)
            TerminalTab.ACCOUNT -> buildAccountDetail(view)
        }
        // 首帧立即应用打印态（不推进时间：行首打印音事件留给 tick 触发；
        // 开机场遮罩期间 tick 不推进打印，遮罩结束后从头开始）
        val pr = printer
        if (pr != null) {
            for (i in printTargets.indices) {
                val text = pr.visibleText(i)
                printedTexts[i] = text
                printTargets[i].label.setText(text)
            }
        }
    }

    private fun buildOrderDoc(view: TerminalViewData) {
        val order = view.selectedOrder
        if (order == null) {
            val tt = detailPanel.createUIElement(detailW - 48f, mainH - 16f, false)
            tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.empty.orders"], TerminalStyle.gray, 10f)
            detailPanel.addUIElement(tt).inTL(24f, 8f)
            detailComponents += tt
            return
        }

        val cardW = TerminalStyle.CARD_MAX_W.coerceAtMost(detailW - 48f)
        val cardH = mainH - 16f
        val cardPlugin = HoloDocPlugin(
            cardW, cardH,
            animatedStamp = {
                val t = stampT
                if (!t.isNaN() && !StampTimeline.settled(t)) stampKind to t else null
            },
            staticStamp = {
                when (controller.view().selectedOrder?.status) {
                    OrderStatus.ACTIVE, OrderStatus.AWAITING_SETTLEMENT -> StampKind.ACCEPTED
                    OrderStatus.SETTLED -> StampKind.SETTLED
                    else -> null
                }
            },
        )
        val cardPanel = detailPanel.createCustomPanel(cardW, cardH, cardPlugin)
        detailPanel.addComponent(cardPanel).inTL((detailW - cardW) / 2f, 8f)
        detailComponents += cardPanel

        val cardTT = cardPanel.createUIElement(
            cardW - TerminalStyle.CARD_PAD_X * 2,
            cardH - TerminalStyle.CARD_PAD_Y * 2,
            true,
        )
        cardPanel.addUIElement(cardTT).inTL(TerminalStyle.CARD_PAD_X, TerminalStyle.CARD_PAD_Y)

        // 签发抬头 + 编号校验行（bounty 表定稿 desc 的首两行即抬头/编号，正文自第 3 行起）
        val head = cardTT.addPara(I18n[TerminalStyle.CAT, "ui.terminal.doc.head"], TerminalStyle.text, 4f)
        head.setAlignment(Alignment.MID)
        val dangerText = if (order.dangerOmitted) {
            I18n[TerminalStyle.CAT_BOUNTY, "danger.omitted"]
        } else {
            I18n[TerminalStyle.CAT_BOUNTY, "danger.${order.threatTier}"]
        }
        val seam = cardTT.addPara(
            I18n.t(
                TerminalStyle.CAT, "ui.terminal.doc.seam",
                "serial" to order.serial,
                "danger" to dangerText,
            ),
            TerminalStyle.gray, 8f,
        )
        seam.setAlignment(Alignment.MID)
        cardTT.addSpacer(8f)

        val descKey = "main.${order.i18nId}.desc"
        val descText = if (order.descVars.isEmpty()) {
            I18n[TerminalStyle.CAT_BOUNTY, descKey]
        } else {
            I18n.t(TerminalStyle.CAT_BOUNTY, descKey, *order.descVars.map { it.toPair() }.toTypedArray())
        }
        val descLines = descText.split("\n")
        val targets = mutableListOf<PrintTarget>()

        fun printedLine(text: String, color: java.awt.Color, pad: Float = 6f) {
            val label = cardTT.addPara(text, color, pad)
            targets += PrintTarget(label, text)
        }

        for (line in descLines.drop(2)) {
            printedLine(line, if (line.startsWith("——")) TerminalStyle.gray else TerminalStyle.text)
        }
        if (order.affixIds.isNotEmpty()) {
            // 无限赏金：追加条款按生成时锁定的固定词缀表具名（编目号写死在 affix.<id>.clause 文案里）
            for (affixId in order.affixIds) {
                printedLine(I18n[TerminalStyle.CAT_BOUNTY, "affix.$affixId.clause"], TerminalStyle.orange)
            }
        } else {
            for (i in 1..order.clauseCount) {
                printedLine(I18n[TerminalStyle.CAT_BOUNTY, "main.${order.i18nId}.clause.$i"], TerminalStyle.orange)
            }
        }
        if (order.multiStage) {
            val stageKey = if (order.stageIndex < order.nodeDrivenStages) {
                "ui.terminal.doc.stage_node_driven"
            } else {
                "ui.terminal.doc.stage"
            }
            printedLine(
                I18n.t(
                    TerminalStyle.CAT, stageKey,
                    "stage" to (order.stageIndex + 1),
                    "stageCount" to order.stageCount,
                ),
                TerminalStyle.orange,
            )
        }
        if (order.status == OrderStatus.AWAITING_SETTLEMENT && !order.hasRequiredItem && order.requiredItemId != null) {
            printedLine(
                I18n.t(
                    TerminalStyle.CAT, "ui.terminal.doc.deliverable_missing",
                    "item" to I18n[TerminalStyle.CAT, "story.item.name.${order.requiredItemId}"],
                ),
                TerminalStyle.orange,
            )
        }
        if (order.reward != null) {
            printedLine(
                I18n.t(TerminalStyle.CAT, "ui.terminal.doc.reward", "reward" to Misc.getDGSCredits(order.reward.toFloat())),
                TerminalStyle.orange,
            )
        } else {
            printedLine(I18n[TerminalStyle.CAT, "ui.terminal.doc.reward_unquoted"], TerminalStyle.orange)
        }
        printedLine(I18n[TerminalStyle.CAT, "ui.terminal.doc.footer"], TerminalStyle.gray, 10f)

        printer = LinePrinter(targets.map { it.fullText })
        printTargets = targets
        printedTexts = MutableList(targets.size) { "" }
    }

    private fun buildArchiveReader(view: TerminalViewData) {
        val entry = view.selectedArchive
        val tt = detailPanel.createUIElement(detailW - 48f, mainH - 16f, true)
        detailPanel.addUIElement(tt).inTL(24f, 8f)
        detailComponents += tt

        if (entry == null) {
            tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.empty.archives"], TerminalStyle.gray, 10f)
            return
        }

        tt.addPara(I18n[TerminalStyle.CAT, "story.archive.${entry.id}.title"], TerminalStyle.teal, 4f)
        tt.addPara(
            I18n.t(
                TerminalStyle.CAT, "ui.terminal.archive.meta",
                "layer" to entry.layer,
                "index" to "%03d".format(entry.indexInLayer),
                "date" to view.account.currentDate,
            ),
            TerminalStyle.gray, 8f,
        )
        tt.addSectionHeading("", TerminalStyle.gray, TerminalStyle.tealDark, Alignment.MID, 4f)
        tt.addSpacer(6f)

        val bodyLines = I18n[TerminalStyle.CAT, "story.archive.${entry.id}.body"].split("\n")
            .map { if (it.isEmpty()) "　" else it }
        val targets = bodyLines.map { line ->
            val label = tt.addPara(line, TerminalStyle.text, 3f)
            PrintTarget(label, line)
        }
        printer = LinePrinter(bodyLines)
        printTargets = targets
        printedTexts = MutableList(targets.size) { "" }
    }

    private fun buildAccountDetail(view: TerminalViewData) {
        val account = view.account
        val tt = detailPanel.createUIElement(detailW - 48f, mainH - 16f, true)
        detailPanel.addUIElement(tt).inTL(24f, 8f)
        detailComponents += tt

        // 第五章「归档」结局事务卡（档案处置签署 / 执行官签发与任命；未进入结局流程时不出现）
        buildEndingCard(tt, view.ending)

        // 账户头：编号 / 等级徽记 / 注册日期 / 已核销数 / 登记批注
        tt.addSectionHeading(
            I18n[TerminalStyle.CAT, "ui.terminal.account.head"],
            TerminalStyle.teal, TerminalStyle.tealDark, Alignment.TL, 6f,
        )
        tt.addPara(
            I18n.t(
                TerminalStyle.CAT, "ui.terminal.account.field.id",
                "id" to I18n[TerminalStyle.CAT, "ui.terminal.account.id_value"],
            ),
            TerminalStyle.text, 6f,
        ).setHighlight(I18n[TerminalStyle.CAT, "ui.terminal.account.id_value"])
        val levelText = I18n[TerminalStyle.CAT, "ui.terminal.account.level.${account.contractorLevel.coerceIn(0, 5)}"]
        tt.addPara(
            I18n.t(TerminalStyle.CAT, "ui.terminal.account.field.level", "level" to levelText),
            TerminalStyle.text, 4f,
        ).setHighlight(levelText)
        tt.addPara(
            I18n.t(
                TerminalStyle.CAT, "ui.terminal.account.field.registered",
                "date" to I18n[TerminalStyle.CAT, "ui.terminal.account.registered_value"],
            ),
            TerminalStyle.text, 4f,
        )
        tt.addPara(
            I18n.t(TerminalStyle.CAT, "ui.terminal.account.field.settled", "count" to account.settledCount),
            TerminalStyle.text, 4f,
        ).setHighlight(account.settledCount.toString())
        tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.account.note"], TerminalStyle.gray, 8f)
        tt.addSpacer(8f)

        // 履约流水账（编号 / 金额 / 批注，借贷分列观感：金额列橙黄）
        val tableW = detailW - 48f
        tt.beginTable(
            TerminalStyle.teal, TerminalStyle.tealDark, TerminalStyle.tealBright, 20f,
            I18n[TerminalStyle.CAT, "ui.terminal.ledger.col.id"], 250f,
            I18n[TerminalStyle.CAT, "ui.terminal.ledger.col.amount"], 130f,
            I18n[TerminalStyle.CAT, "ui.terminal.ledger.col.note"], tableW - 380f,
        )
        for (line in account.ledger) {
            tt.addRow(
                TerminalStyle.text, ledgerLineTitle(line),
                TerminalStyle.orange, Misc.getDGSCredits(line.amount.toFloat()),
                TerminalStyle.gray, ledgerLineNote(line),
            )
        }
        tt.addTable(I18n[TerminalStyle.CAT, "ui.terminal.empty.ledger"], 0, 10f)
        tt.addPara(
            I18n.t(TerminalStyle.CAT, "ui.terminal.ledger.total", "amount" to Misc.getDGSCredits(account.totalPayout.toFloat())),
            TerminalStyle.text, 6f,
        ).setHighlight(Misc.getDGSCredits(account.totalPayout.toFloat()))
    }

    // ─── 第五章「归档」结局事务卡（账户 tab 顶部） ───

    /** 事务卡入口：按 [EndingStage] 呈现签署/签发/任命待办或完成态。 */
    private fun buildEndingCard(tt: TooltipMakerAPI, ending: EndingView) {
        if (ending.stage == EndingStage.NONE) return
        tt.addSectionHeading(
            I18n[TerminalStyle.CAT, "ui.terminal.ending.head"],
            TerminalStyle.orange, TerminalStyle.tealDark, Alignment.TL, 6f,
        )
        when (ending.stage) {
            EndingStage.AWAITING_SIGN -> buildSignSection(tt, ending)
            EndingStage.AWAITING_EXECUTOR_SPEC -> buildExecutorSpecSection(tt)
            EndingStage.AWAITING_COMMAND_SHIP -> buildCommandShipSection(tt, ending)
            EndingStage.AWAITING_ADMIN_MARKET -> buildAdminMarketSection(tt, ending)
            EndingStage.COMPLETE -> buildEndingCompleteSection(tt, ending)
            EndingStage.NONE -> Unit
        }
        tt.addSpacer(10f)
    }

    /** 档案处置签署：三文书选择 → 选中文书全文 + 效果说明 →（交易选）候选报价函 → 签署按钮。 */
    private fun buildSignSection(tt: TooltipMakerAPI, ending: EndingView) {
        val rowW = detailW - 64f
        tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.ending.sign.hint"], TerminalStyle.gray, 4f)
        for (choice in ArchivalChoice.entries) {
            val docId = choice.name.lowercase()
            val row = tt.addAreaCheckbox(
                I18n[TerminalStyle.CAT, "ending.doc.$docId.title"], ArchivalDocButtonId(choice),
                TerminalStyle.teal, TerminalStyle.tealDark, TerminalStyle.tealBright,
                rowW, 22f, 4f,
            )
            row.isChecked = ending.selectedDoc == choice
        }

        val doc = ending.selectedDoc ?: return
        val docId = doc.name.lowercase()
        tt.addSpacer(6f)
        tt.addPara(I18n[TerminalStyle.CAT, "ending.doc.$docId.title"], TerminalStyle.teal, 4f)
        for (line in I18n[TerminalStyle.CAT, "ending.doc.$docId.body"].split("\n")) {
            tt.addPara(line, TerminalStyle.text, 3f)
        }
        for (line in I18n[TerminalStyle.CAT, "ending.doc.$docId.effects"].split("\n")) {
            tt.addPara(line, TerminalStyle.orange, 3f)
        }

        if (doc == ArchivalChoice.TRADE) {
            tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.ending.trade.candidates_head"], TerminalStyle.gray, 6f)
            for (candidate in ending.snapshot.tradeCandidates) {
                val label = I18n.t(
                    TerminalStyle.CAT, "ui.terminal.ending.trade.candidate",
                    "faction" to candidate.factionName,
                    "quote" to Misc.getDGSCredits(candidate.quote.toFloat()),
                )
                val row = tt.addAreaCheckbox(
                    label, TradeFactionButtonId(candidate.factionId),
                    TerminalStyle.teal, TerminalStyle.tealDark, TerminalStyle.tealBright,
                    rowW, 22f, 3f,
                )
                row.isChecked = ending.selectedTradeFaction == candidate.factionId
            }
        }

        val canSign = doc != ArchivalChoice.TRADE || ending.selectedTradeFaction != null
        val button = tt.addButton(
            I18n[TerminalStyle.CAT, "ui.terminal.ending.sign.button"], SignButtonId,
            TerminalStyle.teal, TerminalStyle.tealDark, Alignment.MID, CutStyle.ALL, 200f, 30f, 8f,
        )
        button.isEnabled = canSign
    }

    /** 执行官签发：特化二选一（选定不可更改）。 */
    private fun buildExecutorSpecSection(tt: TooltipMakerAPI) {
        tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.ending.executor.intro"], TerminalStyle.text, 4f)
        for (spec in ExecutorSpec.entries) {
            val specId = spec.name.lowercase()
            tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.ending.executor.${specId}_desc"], TerminalStyle.gray, 3f)
            tt.addButton(
                I18n[TerminalStyle.CAT, "ui.terminal.ending.executor.$specId"], ExecutorSpecButtonId(spec),
                TerminalStyle.teal, TerminalStyle.tealDark, Alignment.MID, CutStyle.ALL, 240f, 30f, 6f,
            )
        }
    }

    /** 指挥舰指定（战斗特化；旗舰默认置顶并带标记）。 */
    private fun buildCommandShipSection(tt: TooltipMakerAPI, ending: EndingView) {
        val rowW = detailW - 64f
        tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.ending.command.hint"], TerminalStyle.gray, 4f)
        val ships = ending.snapshot.ships.sortedByDescending { it.flagship }
        for (ship in ships) {
            val label = if (ship.flagship) {
                I18n.t(TerminalStyle.CAT, "ui.terminal.ending.command.flagship", "ship" to ship.shipName)
            } else {
                ship.shipName
            }
            tt.addButton(
                label, ShipPickButtonId(ship.memberId),
                TerminalStyle.text, TerminalStyle.gray, Alignment.TL, CutStyle.ALL, rowW, 26f, 3f,
            )
        }
    }

    /** 市场管理官任命（行政特化）。 */
    private fun buildAdminMarketSection(tt: TooltipMakerAPI, ending: EndingView) {
        val rowW = detailW - 64f
        tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.ending.admin.hint"], TerminalStyle.gray, 4f)
        for (market in ending.snapshot.markets) {
            tt.addButton(
                market.marketName, MarketPickButtonId(market.marketId),
                TerminalStyle.text, TerminalStyle.gray, Alignment.TL, CutStyle.ALL, rowW, 26f, 3f,
            )
        }
    }

    /** 完成态：签署结果与任命状态行。 */
    private fun buildEndingCompleteSection(tt: TooltipMakerAPI, ending: EndingView) {
        val snapshot = ending.snapshot
        val choiceId = snapshot.archivalChoice?.name?.lowercase() ?: return
        tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.ending.complete.choice.$choiceId"], TerminalStyle.text, 4f)
        when (snapshot.executorSpec) {
            ExecutorSpec.COMBAT -> tt.addPara(
                I18n.t(TerminalStyle.CAT, "ui.terminal.ending.complete.command_ship", "ship" to (snapshot.commandShipName ?: "")),
                TerminalStyle.text, 3f,
            )
            ExecutorSpec.ADMIN -> tt.addPara(
                I18n.t(TerminalStyle.CAT, "ui.terminal.ending.complete.admin_market", "market" to (snapshot.adminMarketName ?: "")),
                TerminalStyle.text, 3f,
            )
            null -> Unit
        }
    }

    // ─── 底部操作条 ───

    private fun buildBottomBar() {
        clearComponents(bottomPanel, bottomComponents)
        val view = controller.view()

        // 主操作区（仅工单 tab）
        if (view.tab == TerminalTab.ORDERS) {
            val order = view.selectedOrder
            val action = TerminalDataMapper.primaryActionOf(order)
            var prev: TooltipMakerAPI? = null

            fun actionButton(text: String, id: ActionButtonId, enabled: Boolean, primary: Boolean) {
                val bt = bottomPanel.createUIElement(170f, 34f, false)
                val button = bt.addButton(
                    text, id,
                    if (primary) TerminalStyle.teal else TerminalStyle.text,
                    if (primary) TerminalStyle.tealDark else TerminalStyle.gray,
                    Alignment.MID, CutStyle.ALL, 170f, 34f, 0f,
                )
                button.isEnabled = enabled
                val pos = bottomPanel.addUIElement(bt)
                if (prev == null) pos.inBL(0f, 6f) else pos.rightOfMid(prev, 10f)
                prev = bt
                bottomComponents += bt
            }

            when {
                action == TerminalAction.ACCEPT ->
                    actionButton(I18n[TerminalStyle.CAT, "ui.terminal.action.accept"], ActionButtonId.PRIMARY, true, true)
                action == TerminalAction.TRACK ->
                    actionButton(I18n[TerminalStyle.CAT, "ui.terminal.action.track"], ActionButtonId.PRIMARY, true, true)
                action == TerminalAction.SETTLE ->
                    actionButton(I18n[TerminalStyle.CAT, "ui.terminal.action.settle"], ActionButtonId.PRIMARY, true, true)
                order?.status == OrderStatus.SETTLED -> {
                    actionButton(I18n[TerminalStyle.CAT, "ui.terminal.action.settled"], ActionButtonId.PRIMARY, false, false)
                    actionButton(I18n[TerminalStyle.CAT, "ui.terminal.action.replay_receipt"], ActionButtonId.REPLAY_RECEIPT, true, false)
                }
            }
        }

        // 右侧：托管小字（常驻）+ 关闭终端
        val closeTT = bottomPanel.createUIElement(170f, 34f, false)
        closeTT.addButton(
            I18n[TerminalStyle.CAT, "ui.terminal.action.close"], ActionButtonId.CLOSE,
            TerminalStyle.text, TerminalStyle.gray, Alignment.MID, CutStyle.ALL, 170f, 34f, 0f,
        )
        bottomPanel.addUIElement(closeTT).inBR(0f, 6f)
        bottomComponents += closeTT

        val custodyTT = bottomPanel.createUIElement(280f, TerminalStyle.BOTTOM_BAR_H, false)
        val custody = custodyTT.addPara(I18n[TerminalStyle.CAT, "ui.terminal.bottom.custody"], TerminalStyle.gray, 16f)
        custody.setAlignment(Alignment.TR)
        bottomPanel.addUIElement(custodyTT).inBR(180f, 0f)
        bottomComponents += custodyTT
    }

    // ─── 回执打印 ───

    private fun openReceipt(receipt: ReceiptView, glitchSpec: GlitchSpec?) {
        closeReceipt()

        val lines = mutableListOf<String>()
        lines += I18n.t(TerminalStyle.CAT, "ui.terminal.receipt.line.order", "serial" to receipt.serial)
        lines += I18n.t(
            TerminalStyle.CAT, "ui.terminal.receipt.line.name",
            // 无限赏金工单名含 %serial% 命名变量；主线工单名无占位符，多传变量不影响解析
            "name" to I18n.t(TerminalStyle.CAT_BOUNTY, "main.${receipt.i18nId}.name", "serial" to receipt.serial),
        )
        lines += I18n.t(TerminalStyle.CAT, "ui.terminal.receipt.line.date", "date" to receipt.date)
        lines += I18n[TerminalStyle.CAT, "ui.terminal.receipt.line.check"]
        if (receipt.groupBonusId != null && receipt.groupBonusAmount > 0) {
            lines += I18n.t(
                TerminalStyle.CAT, "ui.terminal.receipt.line.bonus",
                "group" to I18n[TerminalStyle.CAT, "story.account.group.${receipt.groupBonusId}"],
                "amount" to Misc.getDGSCredits(receipt.groupBonusAmount.toFloat()),
            )
        }
        if (receipt.chapterCleared != null) {
            lines += I18n.t(
                TerminalStyle.CAT, "ui.terminal.receipt.line.chapter",
                "chapter" to receipt.chapterCleared,
                "progress" to formatProgress(receipt.liquidationProgress),
            )
        }

        // 三章末 glitch：打印队列混入半行卡死工单（打印至半截后自愈抹除）
        val jammed = mutableSetOf<Int>()
        if (glitchSpec == GlitchSpec.HALF_LINE) {
            val jamIndex = lines.size.coerceAtMost(2)
            lines.add(jamIndex, I18n[TerminalStyle.CAT, "ui.terminal.glitch.half_line"])
            jammed += jamIndex
        }

        val total = receipt.payout + receipt.groupBonusAmount
        receiptAmountPrefix = I18n[TerminalStyle.CAT, "ui.terminal.receipt.line.amount"]
        receiptAmountTarget = total

        val recH = 120f + lines.size * 22f + 30f + 56f
        val overlay = panel.createCustomPanel(panelW, panelH, ReceiptOverlayPlugin())
        panel.addComponent(overlay).inTL(0f, 0f)
        panel.bringComponentToTop(overlay)
        receiptOverlay = overlay

        val recPanel = overlay.createCustomPanel(
            TerminalStyle.RECEIPT_W, recH,
            ReceiptPanelPlugin(TerminalStyle.RECEIPT_W, recH, ::onButton),
        )
        overlay.addComponent(recPanel).inBMid(TerminalStyle.BOTTOM_BAR_H + 90f)

        val tt = recPanel.createUIElement(TerminalStyle.RECEIPT_W - 48f, recH - 60f, true)
        recPanel.addUIElement(tt).inTL(24f, 14f)

        val title = tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.receipt.title"], TerminalStyle.teal, 2f)
        title.setAlignment(Alignment.MID)
        tt.addSectionHeading("", TerminalStyle.gray, TerminalStyle.tealDark, Alignment.MID, 4f)
        tt.addSpacer(6f)

        val targets = lines.map { line ->
            PrintTarget(tt.addPara(line, TerminalStyle.text, 3f), line)
        }
        // 金额行：前缀先出，数字滚动由 tickReceipt 驱动
        val amountLabel = tt.addPara(receiptAmountPrefix, TerminalStyle.text, 3f)
        receiptAmountLabel = amountLabel

        val foot = tt.addPara(I18n[TerminalStyle.CAT, "ui.terminal.receipt.foot"], TerminalStyle.gray, 12f)
        foot.setAlignment(Alignment.MID)

        val closeTT = recPanel.createUIElement(120f, 30f, false)
        closeTT.addButton(
            I18n[TerminalStyle.CAT, "ui.terminal.receipt.accept"], ActionButtonId.RECEIPT_CLOSE,
            TerminalStyle.teal, TerminalStyle.tealDark, Alignment.MID, CutStyle.ALL, 120f, 30f, 0f,
        )
        recPanel.addUIElement(closeTT).inBMid(8f)

        receiptPrinter = LinePrinter(lines, jammedLines = jammed)
        receiptTargets = targets
        receiptTexts = MutableList(targets.size) { "" }
        receiptRollT = Float.NaN

        // 首帧应用打印态（不推进时间：行首打印音事件留给 tick 触发）
        val pr = receiptPrinter
        if (pr != null) {
            for (i in receiptTargets.indices) {
                val text = pr.visibleText(i)
                receiptTexts[i] = text
                receiptTargets[i].label.setText(text)
            }
        }
    }

    private fun closeReceipt() {
        val overlay = receiptOverlay ?: return
        panel.removeComponent(overlay)
        receiptOverlay = null
        receiptPrinter = null
        receiptTargets = emptyList()
        receiptAmountLabel = null
        receiptRollT = Float.NaN
        TerminalStyle.play(TerminalSound.TAB)
    }

    // ─── 叙事回执链（签署/签发演出） ───

    private fun tickNarrative(amount: Float) {
        if (narrativeOverlay == null) return
        val pr = narrativePrinter
        if (pr != null && !pr.done) {
            val events = pr.advance(amount)
            for (event in events) {
                when (event) {
                    is LinePrinter.Event.LineStarted -> TerminalStyle.play(TerminalSound.PRINT_LINE)
                    is LinePrinter.Event.JamLineErased -> narrativeTargets.getOrNull(event.index)?.label?.setText("")
                    LinePrinter.Event.AllDone -> {
                        if (narrativeAmountTarget > 0) {
                            narrativeRollT = 0f
                            TerminalStyle.play(TerminalSound.RECEIPT_ROLL)
                        }
                    }
                }
            }
            for (i in narrativeTargets.indices) {
                val text = pr.visibleText(i)
                if (text != narrativeTexts[i]) {
                    narrativeTexts[i] = text
                    narrativeTargets[i].label.setText(text)
                }
            }
            return
        }
        if (!narrativeRollT.isNaN()) {
            narrativeRollT += amount
            val rolled = ReceiptRoll.amountAt(narrativeAmountTarget, narrativeRollT)
            narrativeAmountLabel?.setText(narrativeAmountPrefix + Misc.getDGSCredits(rolled.toFloat()))
            if (ReceiptRoll.done(narrativeRollT)) narrativeRollT = Float.NaN
        }
    }

    /** 打开叙事回执链（pages 按序弹出；空列表为空操作）。 */
    private fun openNarrative(pages: List<NarrativeReceiptView>) {
        closeNarrativeOverlay()
        narrativeQueue = pages.toMutableList()
        if (narrativeQueue.isEmpty()) return
        openNarrativePage(narrativeQueue.removeAt(0))
    }

    /** 关闭当前叙事页；队列尚有下一页时立即弹出。 */
    private fun closeNarrative() {
        if (narrativeOverlay == null) return
        closeNarrativeOverlay()
        TerminalStyle.play(TerminalSound.TAB)
        if (narrativeQueue.isNotEmpty()) {
            openNarrativePage(narrativeQueue.removeAt(0))
        }
    }

    private fun closeNarrativeOverlay() {
        val overlay = narrativeOverlay ?: return
        panel.removeComponent(overlay)
        narrativeOverlay = null
        narrativePrinter = null
        narrativeTargets = emptyList()
        narrativeAmountLabel = null
        narrativeRollT = Float.NaN
    }

    private fun openNarrativePage(page: NarrativeReceiptView) {
        val lines = page.lines.map { line ->
            val text = if (line.vars.isEmpty()) {
                I18n[TerminalStyle.CAT, line.key]
            } else {
                I18n.t(TerminalStyle.CAT, line.key, *line.vars.map { it.toPair() }.toTypedArray())
            }
            text to line.dim
        }

        narrativeAmountPrefix = if (page.amount > 0) I18n[TerminalStyle.CAT, page.amountKey] else ""
        narrativeAmountTarget = page.amount

        val recH = 120f + lines.size * 22f + 30f + if (page.amount > 0) 56f else 0f
        val overlay = panel.createCustomPanel(panelW, panelH, ReceiptOverlayPlugin())
        panel.addComponent(overlay).inTL(0f, 0f)
        panel.bringComponentToTop(overlay)
        narrativeOverlay = overlay

        val recPanel = overlay.createCustomPanel(
            TerminalStyle.RECEIPT_W, recH,
            ReceiptPanelPlugin(TerminalStyle.RECEIPT_W, recH, ::onButton),
        )
        overlay.addComponent(recPanel).inBMid(TerminalStyle.BOTTOM_BAR_H + 90f)

        val tt = recPanel.createUIElement(TerminalStyle.RECEIPT_W - 48f, recH - 60f, true)
        recPanel.addUIElement(tt).inTL(24f, 14f)

        val title = tt.addPara(I18n[TerminalStyle.CAT, page.titleKey], TerminalStyle.teal, 2f)
        title.setAlignment(Alignment.MID)
        tt.addSectionHeading("", TerminalStyle.gray, TerminalStyle.tealDark, Alignment.MID, 4f)
        tt.addSpacer(6f)

        val targets = lines.map { (text, dim) ->
            PrintTarget(tt.addPara(text, if (dim) TerminalStyle.gray else TerminalStyle.text, 3f), text)
        }
        if (page.amount > 0) {
            narrativeAmountLabel = tt.addPara(narrativeAmountPrefix, TerminalStyle.text, 3f)
        }

        val foot = tt.addPara(I18n[TerminalStyle.CAT, "ending.receipt.foot"], TerminalStyle.gray, 12f)
        foot.setAlignment(Alignment.MID)

        val closeTT = recPanel.createUIElement(120f, 30f, false)
        closeTT.addButton(
            I18n[TerminalStyle.CAT, "ending.receipt.accept"], ActionButtonId.NARRATIVE_CLOSE,
            TerminalStyle.teal, TerminalStyle.tealDark, Alignment.MID, CutStyle.ALL, 120f, 30f, 0f,
        )
        recPanel.addUIElement(closeTT).inBMid(8f)

        narrativePrinter = LinePrinter(lines.map { it.first })
        narrativeTargets = targets
        narrativeTexts = MutableList(targets.size) { "" }
        narrativeRollT = Float.NaN

        // 首帧应用打印态（不推进时间：行首打印音事件留给 tick 触发）
        val pr = narrativePrinter
        if (pr != null) {
            for (i in narrativeTargets.indices) {
                val text = pr.visibleText(i)
                narrativeTexts[i] = text
                narrativeTargets[i].label.setText(text)
            }
        }
    }

    // ─── 工具 ───

    private fun clearComponents(parent: CustomPanelAPI, components: MutableList<UIComponentAPI>) {
        for (component in components) parent.removeComponent(component)
        components.clear()
    }

    private fun statusColor(status: OrderStatus): java.awt.Color = when (status) {
        OrderStatus.AVAILABLE -> TerminalStyle.orange
        OrderStatus.ACTIVE, OrderStatus.AWAITING_SETTLEMENT -> TerminalStyle.teal
        OrderStatus.SETTLED -> TerminalStyle.gray
    }

    private fun statusText(status: OrderStatus): String = when (status) {
        OrderStatus.AVAILABLE -> I18n[TerminalStyle.CAT, "ui.terminal.status.available"]
        OrderStatus.ACTIVE, OrderStatus.AWAITING_SETTLEMENT -> I18n[TerminalStyle.CAT, "ui.terminal.status.active"]
        OrderStatus.SETTLED -> I18n[TerminalStyle.CAT, "ui.terminal.status.settled"]
    }

    private fun ledgerLineTitle(line: TerminalLedgerLine): String = when (line.kind) {
        LedgerKind.ORDER -> line.title
        LedgerKind.GROUP_BONUS -> I18n[TerminalStyle.CAT, "story.account.group.${line.title}"]
    }

    private fun ledgerLineNote(line: TerminalLedgerLine): String = when (line.kind) {
        LedgerKind.ORDER -> I18n[TerminalStyle.CAT, "ui.terminal.ledger.note.order"]
        LedgerKind.GROUP_BONUS -> I18n[TerminalStyle.CAT, "ui.terminal.ledger.note.bonus"]
    }

    /** 清算序列进度显示格式（与 MainBountyBridge.formatProgress 口径一致：一位小数百分比）。 */
    private fun formatProgress(progress: Float): String = "%.1f%%".format(progress)
}

/** 顶栏 mini 徽记：teal 切角方框 + 「菀」字（原型 #emblem-mini；美术定稿前占位文字徽记）。 */
private class MiniEmblemPlugin : BaseCustomUIPanelPlugin() {

    private var pos: PositionAPI? = null
    private val font: LazyFont by lazy { LazyFont.loadFont(Fonts.INSIGNIA_LARGE) }

    /** 徽记字形缓存（文案定稿不变；createText 每帧重建会反复上传纹理）。 */
    private var glyph: LazyFont.DrawableString? = null

    override fun positionChanged(position: PositionAPI) {
        pos = position
    }

    override fun renderBelow(alphaMult: Float) {
        val p = pos ?: return
        TerminalGl.rectOutline(p.x, p.y, 34f, 34f, TerminalStyle.teal, 0.9f * alphaMult, 1f)
        TerminalGl.rect(p.x, p.y, 34f, 34f, TerminalStyle.teal, 0.08f * alphaMult)
        val g = glyph ?: font.createText(I18n[TerminalStyle.CAT, "ui.terminal.boot.emblem"], TerminalStyle.teal, 20f)
            .apply {
                anchor = LazyFont.TextAnchor.CENTER
                alignment = LazyFont.TextAlignment.CENTER
            }
            .also { glyph = it }
        g.draw(p.x + 17f, p.y + 17f)
    }
}
