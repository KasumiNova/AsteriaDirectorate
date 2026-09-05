package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18nUi
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import kotlin.math.roundToInt

/**
 * 剧情市场状况基类：统一的难度系数缩放与本地化 tooltip 输出。
 *
 * 动机：docs/story/03、07 定义的四个剧情状况（菀星行政部遗址 / 星坠工程部遗址 /
 * 视界动力 / 紫菀科研部遗址）数值全部随固有缩放系数 k_s
 * （[DifficultyTuningImpl.fixedScale]，k=1 取文档下限、k=5 取文档上限）分段线性缩放。
 * 各状况插件就地声明三锚点 [ScalingEntry]，缩放与显示格式化集中在基类，
 * 避免各处重复实现。
 */
abstract class StoryConditionBase : BaseMarketConditionPlugin() {

    /** 状况显示名：stat 修改来源说明与 stat 修改 id 共用。 */
    protected val conditionName: String
        get() = condition.name

    /** 按当前 k_s 计算缩放值。 */
    protected fun scaled(entry: ScalingEntry): Float = DifficultyTuningImpl.value(entry)

    /** 按当前 k_s 计算缩放值并四舍五入为整数（稳定性、设施数量等整数语义数值）。 */
    protected fun scaledInt(entry: ScalingEntry): Int = scaled(entry).roundToInt()

    /** 添加一条效果说明行（本地化 + 高亮参数，基础色为常规文本色）。 */
    protected fun addEffectLine(tooltip: TooltipMakerAPI, key: String, vararg vars: Pair<String, Any?>) {
        I18nUi.addPara(tooltip, I18n.Categories.MOD, key, 10f, Misc.getTextColor(), *vars)
    }

    companion object {
        /** 小数倍率 → 带符号百分数文本（"+25%"、"-15%"），供 tooltip 高亮显示。 */
        fun formatPercent(fraction: Float): String {
            val pct = (fraction * 100f).roundToInt()
            return if (pct >= 0) "+$pct%" else "$pct%"
        }

        /** 整数值 → 带符号文本（"+2"），供 tooltip 高亮显示。 */
        fun formatSignedInt(value: Int): String = if (value >= 0) "+$value" else "$value"
    }
}
