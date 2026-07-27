package cn.kasuminova.astd.impl.difficulty

import cn.kasuminova.astd.internal.i18n.I18n

/**
 * LunaLib 设置项的 field id 常量与"档位显示名 ↔ 系数"映射（单一持有处）。
 *
 * 动机：设置注册（[DifficultySettingsRegistrar]）与系数解析（[DifficultyTuningImpl]）
 * 必须使用同一份档位定义，避免两处各写一份显示名导致读取失配。
 * 显示名经 i18n 读取，读取回显按显示字符串精确匹配；未命中由调用方回退默认档。
 */
object DifficultySettingsKeys {

    /** 模组 id（LunaSettings 的 ModID 入参）。 */
    const val MOD_ID: String = "asteria_directorate"

    /** 档位 radio 的 field id。 */
    const val FIELD_TIER: String = "astd_difficulty_tier"

    /** 自定义系数滑条的 field id。 */
    const val FIELD_CUSTOM_SCALE: String = "astd_difficulty_custom_scale"

    /** 默认档位（砺刃 2.0）：未设置或显示名未命中时的回退值。 */
    const val DEFAULT_SCALE: Float = 2.0f

    /**
     * 一个预设档位。
     *
     * @property nameI18nKey 显示名的 i18n key（settings.difficulty.tier.name.*）
     * @property scale 该档对应的固有缩放系数
     */
    data class Tier(val nameI18nKey: String, val scale: Float)

    /** 预设档位（radio 选项顺序即此顺序）。 */
    val TIERS: List<Tier> = listOf(
        Tier("settings.difficulty.tier.name.dusk", 1.0f),
        Tier("settings.difficulty.tier.name.blade", 2.0f),
        Tier("settings.difficulty.tier.name.expedition", 3.0f),
        Tier("settings.difficulty.tier.name.dawn", 5.0f),
    )

    /** “自定义”档显示名的 i18n key：选中它时改读自定义滑条。 */
    const val CUSTOM_NAME_KEY: String = "settings.difficulty.tier.name.custom"

    /** 全部 radio 选项的显示名（预设档 + 自定义），供注册与精确匹配。 */
    fun tierDisplayNames(): List<String> = TIERS.map { I18n[I18n.Categories.MOD, it.nameI18nKey] } +
        I18n[I18n.Categories.MOD, CUSTOM_NAME_KEY]

    /** 自定义档显示名。 */
    fun customDisplayName(): String = I18n[I18n.Categories.MOD, CUSTOM_NAME_KEY]

    /**
     * 档位解析结果。
     *
     * @property scale 解析出的固有缩放系数
     * @property displayName 生效档位的显示名
     * @property matched 显示名是否命中了预设档或自定义档；未命中时调用方应告警
     */
    data class ResolvedTier(val scale: Float, val displayName: String, val matched: Boolean)

    /**
     * 由选中的显示名解析系数：预设档按表取系数；自定义档取滑条值（封顶 [1, 5]）；
     * 未命中回退默认档（砺刃 2.0）并标记 matched=false。
     * 空选中（首次运行未设置）同样走未命中回退。
     */
    fun resolveTier(selected: String, customScale: Float): ResolvedTier {
        TIERS.firstOrNull { I18n[I18n.Categories.MOD, it.nameI18nKey] == selected }
            ?.let { return ResolvedTier(it.scale, selected, matched = true) }
        if (selected == customDisplayName()) {
            return ResolvedTier(customScale.coerceIn(1f, 5f), selected, matched = true)
        }
        return ResolvedTier(DEFAULT_SCALE, I18n[I18n.Categories.MOD, "settings.difficulty.tier.name.blade"], matched = false)
    }
}
