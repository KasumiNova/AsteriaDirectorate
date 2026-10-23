package cn.kasuminova.astd.impl.ui

import cn.kasuminova.astd.internal.i18n.I18n

object HullmodBackgroundStyleConfig {

    const val FIELD_STYLE: String = "astd_hullmod_bg_style"

    const val STYLE_CORNER_PULSE: Int = 0

    const val STYLE_PRISM_LATTICE: Int = 1

    const val STYLE_COUNT: Int = 2

    const val DEFAULT_STYLE: Int = STYLE_CORNER_PULSE

    data class Style(val id: Int, val nameI18nKey: String)

    val STYLES: List<Style> = listOf(
        Style(STYLE_CORNER_PULSE, "settings.hullmod_bg.style.option.corner_pulse"),
        Style(STYLE_PRISM_LATTICE, "settings.hullmod_bg.style.option.prism_lattice"),
    )

    @Volatile
    var activeStyle: Int = DEFAULT_STYLE
        private set

    fun styleDisplayNames(): List<String> = STYLES.map { I18n[I18n.Categories.MOD, it.nameI18nKey] }

    fun defaultDisplayName(): String = I18n[I18n.Categories.MOD, STYLES.first { it.id == DEFAULT_STYLE }.nameI18nKey]

    fun applySelectedName(selected: String): Boolean {
        val hit = STYLES.firstOrNull { I18n[I18n.Categories.MOD, it.nameI18nKey] == selected }
        activeStyle = hit?.id ?: DEFAULT_STYLE
        return hit != null
    }
}
