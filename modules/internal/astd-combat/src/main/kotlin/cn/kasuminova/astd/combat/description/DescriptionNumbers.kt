package cn.kasuminova.astd.combat.description

import java.math.BigDecimal
import java.math.RoundingMode

internal object DescriptionNumbers {
    fun number(value: Float): String {
        require(value.isFinite()) { "Non-finite description value: $value" }
        return BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun percent(value: Float): String = percentPoints(value * 100f)
    fun percentPoints(value: Float): String = "${number(value)}%"
    fun signedPercent(value: Float): String {
        val text = number(value * 100f)
        return (if (text != "0" && !text.startsWith('-')) "+" else "") + text + "%"
    }
    fun distance(value: Float): String = "${number(value)}su"
}
