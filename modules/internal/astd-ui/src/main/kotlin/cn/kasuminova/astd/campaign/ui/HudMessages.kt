package cn.kasuminova.astd.campaign.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.MessageDisplayAPI
import java.awt.Color

/**
 * 战役/战斗 UI 右侧“消息栏”的 DSL 入口。
 *
 * 说明：
 * - 战役侧：通过 [com.fs.starfarer.api.campaign.CampaignUIAPI.getMessageDisplay] 投递消息。
 * - 该消息栏本身带有原版淡入淡出与堆叠逻辑；API 不暴露自定义 fade 时长。
 */
object HudMessages {

    /**
     * 战役右侧消息栏：纯文本。
     */
    @JvmStatic
    fun campaign(text: String) {
        messageDisplayOrNull()?.addMessage(text)
    }

    /**
     * 战役右侧消息栏：纯文本 + 颜色。
     */
    @JvmStatic
    fun campaign(text: String, color: Color) {
        messageDisplayOrNull()?.addMessage(text, color)
    }

    /**
     * 战役右侧消息栏：基础色 + 高亮一段文本。
     */
    @JvmStatic
    fun campaign(text: String, baseColor: Color, highlightText: String, highlightColor: Color) {
        messageDisplayOrNull()?.addMessage(text, baseColor, highlightText, highlightColor)
    }

    /**
     * 战役右侧消息栏：基础色 + 高亮两段文本。
     */
    @JvmStatic
    fun campaign(
        text: String,
        baseColor: Color,
        highlightText1: String,
        highlightText2: String,
        highlightColor1: Color,
        highlightColor2: Color,
    ) {
        Global.getSector()?.campaignUI?.addMessage(text, baseColor, highlightText1, highlightText2, highlightColor1, highlightColor2)
    }

    @JvmStatic
    fun removeCampaign(exactText: String) {
        messageDisplayOrNull()?.removeMessage(exactText)
    }

    private fun messageDisplayOrNull(): MessageDisplayAPI? =
        Global.getSector()?.campaignUI?.messageDisplay
}
