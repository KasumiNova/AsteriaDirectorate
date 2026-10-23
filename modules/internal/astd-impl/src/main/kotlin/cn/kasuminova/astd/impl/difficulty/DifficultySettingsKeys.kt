package cn.kasuminova.astd.impl.difficulty

import cn.kasuminova.astd.internal.i18n.I18n

object DifficultySettingsKeys {

    const val MOD_ID: String = "asteria_directorate"

    const val FIELD_TIER: String = "astd_difficulty_tier"

    const val FIELD_PLAYER_TIER: String = "astd_difficulty_player_tier"

    const val FIELD_CUSTOM_SCALE: String = "astd_difficulty_custom_scale"

    const val DEFAULT_SCALE: Float = 2.0f

    data class Tier(val nameI18nKey: String, val scale: Float)

    val TIERS: List<Tier> = listOf(
        Tier("settings.difficulty.tier.name.dusk", 1.0f),
        Tier("settings.difficulty.tier.name.blade", 2.0f),
        Tier("settings.difficulty.tier.name.expedition", 3.0f),
        Tier("settings.difficulty.tier.name.dawn", 5.0f),
    )

    const val CUSTOM_NAME_KEY: String = "settings.difficulty.tier.name.custom"

    fun tierDisplayNames(): List<String> = TIERS.map { I18n[I18n.Categories.MOD, it.nameI18nKey] } +
            I18n[I18n.Categories.MOD, CUSTOM_NAME_KEY]

    fun presetTierDisplayNames(): List<String> = TIERS.map { I18n[I18n.Categories.MOD, it.nameI18nKey] }

    fun customDisplayName(): String = I18n[I18n.Categories.MOD, CUSTOM_NAME_KEY]

    data class ResolvedTier(val scale: Float, val displayName: String, val matched: Boolean)

    fun resolveTier(selected: String, customScale: Float): ResolvedTier {
        TIERS.firstOrNull { I18n[I18n.Categories.MOD, it.nameI18nKey] == selected }
            ?.let { return ResolvedTier(it.scale, selected, matched = true) }
        if (selected == customDisplayName()) {
            return ResolvedTier(customScale.coerceIn(1f, 5f), selected, matched = true)
        }
        return ResolvedTier(DEFAULT_SCALE, I18n[I18n.Categories.MOD, "settings.difficulty.tier.name.blade"], matched = false)
    }
}
