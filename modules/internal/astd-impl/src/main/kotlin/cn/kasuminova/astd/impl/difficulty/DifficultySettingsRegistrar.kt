package cn.kasuminova.astd.impl.difficulty

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.impl.campaign.BountySettingsImpl
import cn.kasuminova.astd.impl.combat.DualModeSettingsImpl
import cn.kasuminova.astd.impl.ui.HullmodBackgroundStyleConfig
import cn.kasuminova.astd.internal.i18n.I18n
import lunalib.lunaSettings.LunaSettings
import lunalib.lunaSettings.LunaSettingsListener

object DifficultySettingsRegistrar {

    private val category get() = I18n.Categories.MOD

    fun register() {
        val keys = DifficultySettingsKeys
        val tierNames = keys.tierDisplayNames()
        val defaultTierName = I18n[category, "settings.difficulty.tier.name.blade"]

        LunaSettings.SettingsCreator.addHeader(
            keys.MOD_ID,
            "astd_difficulty_header",
            I18n[category, "settings.difficulty.header"],
            "",
        )
        LunaSettings.SettingsCreator.addRadio(
            keys.MOD_ID,
            DifficultySettingsKeys.FIELD_TIER,
            I18n[category, "settings.difficulty.tier.fieldName"],
            I18n[category, "settings.difficulty.tier.tooltip"],
            defaultTierName,
            tierNames.joinToString(","),
            "",
        )
        LunaSettings.SettingsCreator.addDouble(
            keys.MOD_ID,
            DifficultySettingsKeys.FIELD_CUSTOM_SCALE,
            I18n[category, "settings.difficulty.custom.fieldName"],
            I18n[category, "settings.difficulty.custom.tooltip"],
            DifficultySettingsKeys.DEFAULT_SCALE.toDouble(),
            1.0,
            5.0,
            "",
        )
        LunaSettings.SettingsCreator.addRadio(
            keys.MOD_ID,
            DifficultySettingsKeys.FIELD_PLAYER_TIER,
            I18n[category, "settings.difficulty.player_tier.fieldName"],
            I18n[category, "settings.difficulty.player_tier.tooltip"],
            defaultTierName,
            keys.presetTierDisplayNames().joinToString(","),
            "",
        )
        LunaSettings.SettingsCreator.addBoolean(
            keys.MOD_ID,
            DualModeSettingsImpl.FIELD_FREE_AUTO_POINTS,
            I18n[category, "settings.dualmode.free_auto_points.fieldName"],
            I18n[category, "settings.dualmode.free_auto_points.tooltip"],
            DualModeSettingsImpl.DEFAULT_FREE_AUTO_POINTS,
            "",
        )
        LunaSettings.SettingsCreator.addRadio(
            keys.MOD_ID,
            HullmodBackgroundStyleConfig.FIELD_STYLE,
            I18n[category, "settings.hullmod_bg.style.fieldName"],
            I18n[category, "settings.hullmod_bg.style.tooltip"],
            HullmodBackgroundStyleConfig.defaultDisplayName(),
            HullmodBackgroundStyleConfig.styleDisplayNames().joinToString(","),
            "",
        )
        listOf("dusk", "blade", "expedition", "dawn").forEach { tier ->
            LunaSettings.SettingsCreator.addText(
                keys.MOD_ID,
                "astd_difficulty_desc_$tier",
                I18n[category, "settings.difficulty.tier.desc.$tier"],
                "",
            )
        }
        LunaSettings.SettingsCreator.addHeader(
            keys.MOD_ID,
            "astd_bounty_header",
            I18n[category, "settings.bounty.header"],
            "",
        )
        LunaSettings.SettingsCreator.addBoolean(
            keys.MOD_ID,
            BountySettingsImpl.FIELD_FILL_TO_FLEET_CAP,
            I18n[category, "settings.bounty.fill_to_fleet_cap.fieldName"],
            I18n[category, "settings.bounty.fill_to_fleet_cap.tooltip"],
            BountySettingsImpl.DEFAULT_FILL_TO_FLEET_CAP,
            "",
        )
        LunaSettings.SettingsCreator.refresh()

        applyCurrentSettings()
        LunaSettings.addListener(object : LunaSettingsListener {
            override fun settingsChanged(modID: String) {
                if (modID == DifficultySettingsKeys.MOD_ID) applyCurrentSettings()
            }
        })
    }

    private fun applyCurrentSettings() {
        val keys = DifficultySettingsKeys

        val selected = LunaSettings.getString(keys.MOD_ID, DifficultySettingsKeys.FIELD_TIER).orEmpty()
        val customScale = LunaSettings.getDouble(keys.MOD_ID, DifficultySettingsKeys.FIELD_CUSTOM_SCALE)
            ?.toFloat()
            ?: DifficultySettingsKeys.DEFAULT_SCALE
        val resolved = keys.resolveTier(selected, customScale)
        if (!resolved.matched && selected.isNotEmpty()) {
            AstdLog.logger.warn("[ASTD] 敌方难度档位显示名未命中：'$selected'，回退默认档（砺刃 2.0）")
        }
        DifficultyTuningImpl.applyResolvedScale(resolved.scale, resolved.displayName)

        val selectedPlayer = LunaSettings.getString(keys.MOD_ID, DifficultySettingsKeys.FIELD_PLAYER_TIER).orEmpty()
        val resolvedPlayer = keys.resolveTier(selectedPlayer, DifficultySettingsKeys.DEFAULT_SCALE)
        if (!resolvedPlayer.matched && selectedPlayer.isNotEmpty()) {
            AstdLog.logger.warn("[ASTD] 我方难度档位显示名未命中：'$selectedPlayer'，回退默认档（砺刃 2.0）")
        }
        DifficultyTuningImpl.applyResolvedPlayerScale(resolvedPlayer.scale, resolvedPlayer.displayName)

        val freeAutoPoints = LunaSettings.getBoolean(keys.MOD_ID, DualModeSettingsImpl.FIELD_FREE_AUTO_POINTS)
            ?: DualModeSettingsImpl.DEFAULT_FREE_AUTO_POINTS
        DualModeSettingsImpl.applyResolvedExempt(freeAutoPoints)

        val fillToFleetCap = LunaSettings.getBoolean(keys.MOD_ID, BountySettingsImpl.FIELD_FILL_TO_FLEET_CAP)
            ?: BountySettingsImpl.DEFAULT_FILL_TO_FLEET_CAP
        BountySettingsImpl.applyResolvedFillToFleetCap(fillToFleetCap)

        val bgStyleSelected = LunaSettings.getString(keys.MOD_ID, HullmodBackgroundStyleConfig.FIELD_STYLE).orEmpty()
        if (!HullmodBackgroundStyleConfig.applySelectedName(bgStyleSelected) && bgStyleSelected.isNotEmpty()) {
            AstdLog.logger.warn("[ASTD] 船插背景风格显示名未命中：'$bgStyleSelected'，回退默认风格（角标脉冲）")
        }
    }
}
