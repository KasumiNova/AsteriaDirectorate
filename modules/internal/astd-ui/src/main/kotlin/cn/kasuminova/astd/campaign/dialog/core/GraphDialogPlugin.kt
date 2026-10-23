package cn.kasuminova.astd.campaign.dialog.core

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.combat.EngagementResultAPI

class GraphDialogPlugin(
    private val graph: DialogGraph,
    private val closeOnEscapeOptionId: String? = null,
    private val closeOnEscapeText: String = I18n[I18n.Categories.MOD, "dialog.core.leave"],
    /**
     * 非 null 时由调用方接管关闭动作（替代 dismiss/dismissAsCancel），
     * 用于酒馆事件（PortsideBarEvent）等需把控制权交还宿主插件的场景。
     */
    private val onClose: ((asCancel: Boolean) -> Unit)? = null,
) : InteractionDialogPlugin {

    private lateinit var dialog: InteractionDialogAPI
    private lateinit var ctx: DialogContext

    private var currNodeId: String = graph.startNodeId
    private var currNode: DialogNode = graph.requireNode(graph.startNodeId)

    private val optionIndex = LinkedHashMap<String, DialogOptionSpec>()

    private var optionsDirty: Boolean = true

    /**
     * 关闭后必须停止本插件的全部面板写操作：onClose 宿主钩子
     * （如 BarEventDialogPlugin.endEvent → BarCMD.showOptions）会同步重建宿主选项面板，
     * 此后的选项刷新会 clearOptions 抹掉宿主刚添加的选项。
     */
    private var closed: Boolean = false

    /** 面板当前是否展示锁定态的跳过选项（null 表示尚未刷新）；避免逐帧重建选项。 */
    private var optionsShownLocked: Boolean? = null

    override fun init(dialog: InteractionDialogAPI) {
        this.dialog = dialog

        val target: SectorEntityToken? = dialog.interactionTarget
        val text = dialog.textPanel
        val options = dialog.optionPanel
        val memoryMap = this.getMemoryMap()

        val sessionState = LinkedHashMap<String, Any?>()
        val queue = TimedTextQueue(text)

        this.ctx = DialogContext(
            dialog = dialog,
            target = target,
            text = text,
            options = options,
            memoryMap = memoryMap,
            sessionState = sessionState,
            textQueue = queue,
        ).also { c ->
            c.requestOptionsRefresh = { optionsDirty = true }
            c.requestGoto = { id -> gotoInternal(id) }
            c.requestClose = { asCancel -> closeInternal(asCancel) }
        }

        if (closeOnEscapeOptionId != null) {
            dialog.setOptionOnEscape(closeOnEscapeText, closeOnEscapeOptionId)
        }

        currNode.onEnter(ctx)
        refreshOptionsIfNeeded()
    }

    override fun optionSelected(optionText: String?, optionData: Any?) {
        if (closed) return

        ctx.addOptionSelectedEcho(optionText)

        val id = optionData as? String ?: return

        // Escape 键允许随时离开，包括文本逐条输出期间。
        if (closeOnEscapeOptionId != null && id == closeOnEscapeOptionId) {
            closeInternal(asCancel = false)
            return
        }

        if (isOptionsLocked()) {
            if (id == OPTION_SKIP) {
                ctx.textQueue.flush()
                optionsDirty = true
                refreshOptionsIfNeeded()
            }
            return
        }

        val spec = optionIndex[id] ?: return
        execute(spec.action)
        refreshOptionsIfNeeded()
    }

    override fun optionMousedOver(optionText: String?, optionData: Any?) {
        // tooltip 在 refreshOptionsIfNeeded 的 addOption 调用中传入。
    }

    override fun advance(amount: Float) {
        if (closed) return

        // 缩放同时作用于 TextPanel 内部动画与延迟文本队列。
        val scaled = amount * DIALOG_TIME_SCALE

        dialog.textPanel.advance(scaled)

        val emitted = ctx.textQueue.advance(scaled)
        if (emitted > 0) {
            optionsDirty = true
        }

        // 节点计时使用未缩放的 amount，不受文本节奏缩放影响。
        currNode.onAdvance(ctx, amount)

        // onAdvance 可能已触发关闭且宿主钩子同步重建了选项面板，此后禁止再做选项刷新。
        if (closed) return

        // 队列播完或自动跳转到新播报节点时必须刷新，否则锁定态选项会一直滞留。
        val locked = isOptionsLocked()
        if (optionsDirty && (!locked || optionsShownLocked != true)) {
            refreshOptionsIfNeeded()
        }
    }

    override fun backFromEngagement(battleResult: EngagementResultAPI?) {
    }

    override fun getContext(): Any? = null

    override fun getMemoryMap(): Map<String, MemoryAPI> {
        // key 约定参考 rules.csv / BaseCommandPlugin 的 memoryMap；仅提供常用项，缺失的不放入。
        val map = LinkedHashMap<String, MemoryAPI?>()

        val sector = com.fs.starfarer.api.Global.getSector()
        map["global"] = sector?.memoryWithoutUpdate
        map["player"] = sector?.playerFleet?.memoryWithoutUpdate
        map["local"] = dialog.interactionTarget?.memoryWithoutUpdate
        map["entity"] = dialog.interactionTarget?.memoryWithoutUpdate
        map["market"] = dialog.interactionTarget?.market?.memoryWithoutUpdate

        return map.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(LinkedHashMap())
    }

    private fun gotoInternal(nodeId: String) {
        if (nodeId == currNodeId) return

        currNode.onLeave(ctx)
        ctx.textQueue.clear()

        currNodeId = nodeId
        currNode = graph.requireNode(nodeId)

        optionsDirty = true
        currNode.onEnter(ctx)
    }

    private fun closeInternal(asCancel: Boolean) {
        if (closed) return
        closed = true
        val handler = onClose
        if (handler != null) {
            handler(asCancel)
            return
        }
        if (asCancel) {
            dialog.dismissAsCancel()
        } else {
            dialog.dismiss()
        }
    }

    private fun execute(action: DialogAction) {
        when (action) {
            is DialogAction.Goto -> gotoInternal(action.nodeId)
            is DialogAction.Close -> closeInternal(action.asCancel)
            is DialogAction.Run -> {
                action.block(ctx)
                action.then?.let { execute(it) }
            }
        }
    }

    private fun refreshOptionsIfNeeded() {
        if (closed || !optionsDirty) return
        optionsDirty = false

        val locked = isOptionsLocked()
        optionsShownLocked = locked
        val specs = if (locked) {
            buildLockedOptions()
        } else {
            currNode.buildOptions(ctx)
        }

        optionIndex.clear()
        dialog.optionPanel.clearOptions()

        for (spec in specs) {
            optionIndex[spec.id] = spec
            dialog.optionPanel.addOption(spec.text, spec.id, spec.tooltip)
            dialog.optionPanel.setEnabled(spec.id, spec.enabled)
            if (spec.shortcut != null) {
                dialog.optionPanel.setShortcut(spec.id, spec.shortcut, spec.ctrl, spec.alt, spec.shift, false)
            }
        }
    }

    private fun isOptionsLocked(): Boolean {
        if (!ctx.textQueue.hasPending) return false
        return currNode.lockOptionsWhileTextQueueActive(ctx)
    }

    private fun buildLockedOptions(): List<DialogOptionSpec> {
        if (!currNode.showSkipWhileLocked(ctx)) return emptyList()
        return listOf(
            DialogOptionSpec(
                id = OPTION_SKIP,
                text = I18n[I18n.Categories.MOD, "dialog.core.skip"],
                action = DialogAction.Run({ it.textQueue.flush() })
            )
        )
    }

    companion object {
        private const val OPTION_SKIP = "__graph_dialog_skip__"

        /** 对话文本整体时间缩放，TextPanel 推入动画与行间延迟统一按该系数放慢。 */
        private const val DIALOG_TIME_SCALE: Float = 0.5f
    }
}
