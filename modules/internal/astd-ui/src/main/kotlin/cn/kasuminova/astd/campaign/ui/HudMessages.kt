package cn.kasuminova.astd.campaign.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.MessageDisplayAPI
import java.awt.Color

/**
 * 战役右侧消息栏入口。
 * 消息栏自带原版淡入淡出与堆叠逻辑，API 不暴露自定义 fade 时长。
 */
object HudMessages {

    @JvmStatic
    fun campaign(text: String) {
        messageDisplayOrNull()?.addMessage(text)
    }

    @JvmStatic
    fun campaign(text: String, color: Color) {
        messageDisplayOrNull()?.addMessage(text, color)
    }

    @JvmStatic
    fun campaign(text: String, baseColor: Color, highlightText: String, highlightColor: Color) {
        messageDisplayOrNull()?.addMessage(text, baseColor, highlightText, highlightColor)
    }

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
