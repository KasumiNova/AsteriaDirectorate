package cn.kasuminova.astd.impl.ui

import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 船插 Tooltip 背景渲染风格的 LunaLib 设置持有（field id / 风格常量 / 当前生效值的单一持有处）。
 *
 * 动机：设置注册（[cn.kasuminova.astd.impl.difficulty.DifficultySettingsRegistrar]）、
 * 显示名解析与渲染侧（astd-ui 的 ASTDHullModTooltipBackground）必须使用同一份风格定义，
 * 避免多处各写一份显示名/常量导致读取失配。
 * 显示名经 i18n 读取，读取回显按显示字符串精确匹配；未命中回退默认风格并由调用方告警。
 * 渲染侧每次渲染直接读取 [activeStyle]，设置保存后新帧即时生效。
 */
object HullmodBackgroundStyleConfig {

    /** 背景风格 radio 的 field id。 */
    const val FIELD_STYLE: String = "astd_hullmod_bg_style"

    /** 主风格：角标脉冲（k3-01）。 */
    const val STYLE_CORNER_PULSE: Int = 0

    /** 备选风格：棱镜栅格（deepseek-02）。 */
    const val STYLE_PRISM_LATTICE: Int = 1

    /** 可选风格数量（渲染侧 program 缓存容量）。 */
    const val STYLE_COUNT: Int = 2

    /** 默认风格：未设置或显示名未命中时的回退值。 */
    const val DEFAULT_STYLE: Int = STYLE_CORNER_PULSE

    /**
     * 一个可选风格。
     *
     * @property id 风格编号（渲染侧 shaderProgramIds 缓存下标）
     * @property nameI18nKey 显示名的 i18n key（settings.hullmod_bg.style.option.*）
     */
    data class Style(val id: Int, val nameI18nKey: String)

    /** 全部可选风格（radio 选项顺序即此顺序）。 */
    val STYLES: List<Style> = listOf(
        Style(STYLE_CORNER_PULSE, "settings.hullmod_bg.style.option.corner_pulse"),
        Style(STYLE_PRISM_LATTICE, "settings.hullmod_bg.style.option.prism_lattice"),
    )

    /** 当前生效风格：渲染侧每次渲染读取；由 LunaLib 设置注册/回调刷新。 */
    @Volatile
    var activeStyle: Int = DEFAULT_STYLE
        private set

    /** 全部 radio 选项的显示名，供注册与精确匹配。 */
    fun styleDisplayNames(): List<String> = STYLES.map { I18n[I18n.Categories.MOD, it.nameI18nKey] }

    /** 默认风格的显示名（radio 默认值入参）。 */
    fun defaultDisplayName(): String = I18n[I18n.Categories.MOD, STYLES.first { it.id == DEFAULT_STYLE }.nameI18nKey]

    /**
     * 由选中的显示名解析风格并刷新 [activeStyle]。
     *
     * @return 显示名是否命中可选风格；未命中时已回退 [DEFAULT_STYLE]，调用方应告警
     */
    fun applySelectedName(selected: String): Boolean {
        val hit = STYLES.firstOrNull { I18n[I18n.Categories.MOD, it.nameI18nKey] == selected }
        activeStyle = hit?.id ?: DEFAULT_STYLE
        return hit != null
    }
}
