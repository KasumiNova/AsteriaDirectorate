package cn.kasuminova.astd.campaign.dialog.core

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.campaign.ui.HudMessages
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.OptionPanelAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.TextPanelAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import java.awt.Color

/**
 * 对话运行时上下文（模板可复用的“胶水层”）。
 */
class DialogContext internal constructor(
    val dialog: InteractionDialogAPI,
    val target: SectorEntityToken?,
    val text: TextPanelAPI,
    val options: OptionPanelAPI,
    val memoryMap: Map<String, MemoryAPI>,
    val sessionState: MutableMap<String, Any?>,
    val textQueue: TimedTextQueue,
) {

    internal var requestOptionsRefresh: () -> Unit = {}
    internal var requestGoto: (String) -> Unit = {}
    internal var requestClose: (Boolean) -> Unit = {}

    val globalMemory: MemoryAPI?
        get() = memoryMap["global"]

    val localMemory: MemoryAPI?
        get() = memoryMap["local"]

    val entityMemory: MemoryAPI?
        get() = memoryMap["entity"]

    val playerMemory: MemoryAPI?
        get() = memoryMap["player"]

    val marketMemory: MemoryAPI?
        get() = memoryMap["market"]

    fun markOptionsDirty() {
        requestOptionsRefresh()
    }

    fun goto(nodeId: String) {
        requestGoto(nodeId)
    }

    fun close(asCancel: Boolean = false) {
        requestClose(asCancel)
    }

    fun say(text: String, color: Color? = null) {
        if (color != null) {
            this.text.addPara(text, color)
        } else {
            this.text.addPara(text)
        }
    }

    fun sayI18n(rendered: I18n.Rendered, baseColor: Color? = null) {
        text.addRendered(rendered, baseColor)
    }

    fun sayI18n(category: I18n.Categories, key: String, baseColor: Color? = null, vararg vars: Pair<String, Any?>) {
        sayI18n(I18n.tr(category, key, *vars), baseColor)
    }

    fun enqueue(text: String, delay: Float, baseColor: Color? = null) {
        textQueue.enqueue(text, delay, baseColor)
        markOptionsDirty()
    }

    fun enqueueFading(
        text: String,
        delay: Float,
        fadeIn: Float = 0.2f,
        hold: Float = 0f,
        fadeOut: Float = 0f,
        maxOpacity: Float = 1f,
        baseColor: Color? = null,
    ) {
        textQueue.enqueueFading(text, delay, fadeIn, hold, fadeOut, maxOpacity, baseColor)
        markOptionsDirty()
    }

    fun enqueueI18n(rendered: I18n.Rendered, delay: Float, baseColor: Color? = null) {
        textQueue.enqueue(rendered, delay, baseColor)
        markOptionsDirty()
    }

    fun enqueueI18nFading(
        rendered: I18n.Rendered,
        delay: Float,
        fadeIn: Float = 0.2f,
        hold: Float = 0f,
        fadeOut: Float = 0f,
        maxOpacity: Float = 1f,
        baseColor: Color? = null,
    ) {
        textQueue.enqueueFading(rendered, delay, fadeIn, hold, fadeOut, maxOpacity, baseColor)
        markOptionsDirty()
    }

    fun enqueueI18n(category: I18n.Categories, key: String, delay: Float, baseColor: Color? = null, vararg vars: Pair<String, Any?>) {
        enqueueI18n(I18n.tr(category, key, *vars), delay, baseColor)
    }

    fun enqueueI18nFading(
        category: I18n.Categories,
        key: String,
        delay: Float,
        fadeIn: Float = 0.2f,
        hold: Float = 0f,
        fadeOut: Float = 0f,
        maxOpacity: Float = 1f,
        baseColor: Color? = null,
        vararg vars: Pair<String, Any?>,
    ) {
        enqueueI18nFading(I18n.tr(category, key, *vars), delay, fadeIn, hold, fadeOut, maxOpacity, baseColor)
    }

    /**
     * 战役右侧 HUD 消息栏（原版自带淡入淡出）。
     */
    fun hudMessage(text: String, color: Color? = null) {
        if (color != null) HudMessages.campaign(text, color) else HudMessages.campaign(text)
    }

    fun hudMessage(text: String, baseColor: Color, highlightText: String, highlightColor: Color) {
        HudMessages.campaign(text, baseColor, highlightText, highlightColor)
    }

    fun hudMessage(
        text: String,
        baseColor: Color,
        highlightText1: String,
        highlightText2: String,
        highlightColor1: Color,
        highlightColor2: Color,
    ) {
        HudMessages.campaign(text, baseColor, highlightText1, highlightText2, highlightColor1, highlightColor2)
    }

    fun hudMessageI18n(category: I18n.Categories, key: String, color: Color? = null, vararg vars: Pair<String, Any?>) {
        hudMessage(I18n.t(category, key, *vars), color)
    }

    fun addOptionSelectedEcho(optionText: String?) {
        if (optionText.isNullOrBlank()) return
        val c = Global.getSettings().getColor("buttonText")
        text.addPara(optionText, c)
    }
}
