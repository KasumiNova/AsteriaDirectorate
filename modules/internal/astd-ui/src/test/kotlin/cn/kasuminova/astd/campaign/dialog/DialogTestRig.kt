package cn.kasuminova.astd.campaign.dialog

import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.core.TimedTextQueue
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.campaign.OptionPanelAPI
import com.fs.starfarer.api.campaign.TextPanelAPI
import com.fs.starfarer.api.ui.LabelAPI
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.IdentityHashMap

/** 选项面板上一行选项的快照（addOption + setEnabled 重放结果）。 */
data class ShownOption(
    val text: String,
    val id: String?,
    val tooltip: String?,
    var enabled: Boolean = true,
)

/**
 * 对话插件单测桩：mock InteractionDialogAPI/TextPanelAPI/OptionPanelAPI，
 * 记录输出段落、选项面板快照、Label 透明度序列与 dismiss 调用。
 *
 * 说明：I18n 在无游戏环境下回退为 "category:key"，因此断言基于键名子串。
 */
class DialogTestRig {

    val text: TextPanelAPI = mock(TextPanelAPI::class.java)
    val options: OptionPanelAPI = mock(OptionPanelAPI::class.java)
    val dialog: InteractionDialogAPI = mock(InteractionDialogAPI::class.java)

    /** 已输出段落文本（按输出顺序）。 */
    val paras = mutableListOf<String>()

    /** 当前选项面板快照。 */
    val shown = mutableListOf<ShownOption>()

    /** 每个 Label 的 setOpacity 调用序列（淡入淡出断言用）。 */
    val opacityHistory = IdentityHashMap<LabelAPI, MutableList<Float>>()

    var dismissed = false
    var dismissedAsCancel = false

    init {
        `when`(text.addPara(anyString())).thenAnswer { inv ->
            paras += inv.getArgument<String>(0)
            newLabel()
        }
        `when`(text.addPara(anyString(), any())).thenAnswer { inv ->
            paras += inv.getArgument<String>(0)
            newLabel()
        }
        doAnswer { shown.clear(); null }.`when`(options).clearOptions()
        doAnswer { inv ->
            shown += ShownOption(inv.getArgument(0), inv.getArgument(1) as? String, inv.getArgument(2) as? String)
            null
        }.`when`(options).addOption(anyString(), any(), any())
        doAnswer { inv ->
            val id = inv.getArgument<String>(0)
            shown.firstOrNull { it.id == id }?.enabled = inv.getArgument(1)
            null
        }.`when`(options).setEnabled(anyString(), anyBoolean())
        `when`(dialog.textPanel).thenReturn(text)
        `when`(dialog.optionPanel).thenReturn(options)
        `when`(dialog.interactionTarget).thenReturn(null)
        doAnswer { dismissed = true; null }.`when`(dialog).dismiss()
        doAnswer { dismissedAsCancel = true; null }.`when`(dialog).dismissAsCancel()
    }

    private fun newLabel(): LabelAPI {
        val label = mock(LabelAPI::class.java)
        val history = mutableListOf<Float>()
        opacityHistory[label] = history
        doAnswer { inv -> history += inv.getArgument<Float>(0); null }.`when`(label).setOpacity(anyFloat())
        return label
    }

    /** 推进对话直到满足条件；帧数耗尽返回 false。 */
    fun runUntil(plugin: InteractionDialogPlugin, maxFrames: Int = 4000, predicate: () -> Boolean): Boolean {
        var frames = 0
        while (!predicate() && frames++ < maxFrames) {
            plugin.advance(0.05f)
        }
        return predicate()
    }

    /** 推进直到选项面板出现指定前缀的选项 id。 */
    fun awaitOption(plugin: InteractionDialogPlugin, idPrefix: String, maxFrames: Int = 4000): Boolean =
        runUntil(plugin, maxFrames) { shown.any { it.id?.startsWith(idPrefix) == true } }

    /** 点选选项（optionText 传 null：跳过回显里的 Global.getSettings() 取色）。 */
    fun select(plugin: InteractionDialogPlugin, id: String) {
        plugin.optionSelected(null, id)
    }

    fun shownIds(): List<String?> = shown.map { it.id }

    /** 构造一个脱离插件、可直接驱动节点的 DialogContext（图结构探针用）。 */
    fun newProbeContext(): Pair<DialogContext, Probe> {
        val probe = Probe()
        val ctx = DialogContext(
            dialog = dialog,
            target = null,
            text = text,
            options = options,
            memoryMap = emptyMap(),
            sessionState = LinkedHashMap(),
            textQueue = TimedTextQueue(text),
        )
        ctx.requestGoto = { probe.goto = it }
        ctx.requestClose = { probe.closed = it }
        return ctx to probe
    }

    /** 图结构探针捕获：节点内部 goto / close 请求。 */
    class Probe {
        var goto: String? = null
        var closed: Boolean? = null
    }
}
