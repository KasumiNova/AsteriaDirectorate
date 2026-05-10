package cn.kasuminova.astd.campaign.dialog.core

import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.combat.EngagementResultAPI
import java.util.LinkedHashMap
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 可复用的 InteractionDialogPlugin 模板：
 * - 以 [DialogGraph] 驱动（node/option/goto）
 * - 内置“延迟逐条输出”的 [TimedTextQueue]
 * - 内置“锁定选项 + 跳过”策略
 */
class GraphDialogPlugin(
    private val graph: DialogGraph,
    private val closeOnEscapeOptionId: String? = null,
    private val closeOnEscapeText: String = I18n[I18n.Categories.MOD, "dialog.core.leave"],
) : InteractionDialogPlugin {

    private lateinit var dialog: InteractionDialogAPI
    private lateinit var ctx: DialogContext

    private var currNodeId: String = graph.startNodeId
    private var currNode: DialogNode = graph.requireNode(graph.startNodeId)

    private val optionIndex = LinkedHashMap<String, DialogOptionSpec>()

    private var optionsDirty: Boolean = true

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

        // 首次进入
        currNode.onEnter(ctx)
        refreshOptionsIfNeeded()
    }

    override fun optionSelected(optionText: String?, optionData: Any?) {
        // 通常对话里会把选项文本回显到文本面板
        ctx.addOptionSelectedEcho(optionText)

        val id = optionData as? String ?: return

        // Escape 键：允许随时离开（即使正在逐条输出文本）。
        if (closeOnEscapeOptionId != null && id == closeOnEscapeOptionId) {
            closeInternal(asCancel = false)
            return
        }

        // 如果当前 node 在“文本队列输出期间锁定选项”，则只允许 skip 或显式允许的选项。
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
        // 需要 tooltip 的话，建议直接在 OptionPanel.addOption 时传 tooltip（见 refreshOptionsIfNeeded）
    }

    override fun advance(amount: Float) {
        // 先推进 TextPanel 内部动画（打字机效果等）
        dialog.textPanel.advance(amount)

        // 再推进“延迟逐条输出”
        val emitted = ctx.textQueue.advance(amount)
        if (emitted > 0) {
            optionsDirty = true
        }

        // 节点每帧逻辑
        currNode.onAdvance(ctx, amount)
    }

    override fun backFromEngagement(battleResult: EngagementResultAPI?) {
    }

    override fun getContext(): Any? = null

    override fun getMemoryMap(): Map<String, MemoryAPI> {
        // 约定参考：rules.csv / BaseCommandPlugin 的 memoryMap keys。
        // 这里我们只做“尽力提供常用的几个”。缺的 key 允许为 null。
        val map = LinkedHashMap<String, MemoryAPI?>()

        val sector = com.fs.starfarer.api.Global.getSector()
        map["global"] = sector?.memoryWithoutUpdate
        map["player"] = sector?.playerFleet?.memoryWithoutUpdate
        map["local"] = dialog.interactionTarget?.memoryWithoutUpdate
        map["entity"] = dialog.interactionTarget?.memoryWithoutUpdate
        map["market"] = dialog.interactionTarget?.market?.memoryWithoutUpdate

        // 清理 null
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
        if (!optionsDirty) return
        optionsDirty = false

        val locked = isOptionsLocked()
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
    }
}
