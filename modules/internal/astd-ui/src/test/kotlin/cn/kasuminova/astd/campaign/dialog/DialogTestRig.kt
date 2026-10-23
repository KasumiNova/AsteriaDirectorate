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

data class ShownOption(
    val text: String,
    val id: String?,
    val tooltip: String?,
    var enabled: Boolean = true,
)

/**
 * 对话插件单测桩。
 * I18n 在无游戏环境下回退为 "category:key"，断言基于键名子串。
 */
class DialogTestRig {

    val text: TextPanelAPI = mock(TextPanelAPI::class.java)
    val options: OptionPanelAPI = mock(OptionPanelAPI::class.java)
    val dialog: InteractionDialogAPI = mock(InteractionDialogAPI::class.java)

    val paras = mutableListOf<String>()

    val shown = mutableListOf<ShownOption>()

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
        doAnswer { inv -> history += inv.getArgument<Float>(0); null }.`when`(label).opacity = anyFloat()
        return label
    }

    fun runUntil(plugin: InteractionDialogPlugin, maxFrames: Int = 4000, predicate: () -> Boolean): Boolean {
        var frames = 0
        while (!predicate() && frames++ < maxFrames) {
            plugin.advance(0.05f)
        }
        return predicate()
    }

    fun awaitOption(plugin: InteractionDialogPlugin, idPrefix: String, maxFrames: Int = 4000): Boolean =
        runUntil(plugin, maxFrames) { shown.any { it.id?.startsWith(idPrefix) == true } }

    /** optionText 传 null：跳过回显里的 Global.getSettings() 取色。 */
    fun select(plugin: InteractionDialogPlugin, id: String) {
        plugin.optionSelected(null, id)
    }

    fun shownIds(): List<String?> = shown.map { it.id }

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

    class Probe {
        var goto: String? = null
        var closed: Boolean? = null
    }
}
