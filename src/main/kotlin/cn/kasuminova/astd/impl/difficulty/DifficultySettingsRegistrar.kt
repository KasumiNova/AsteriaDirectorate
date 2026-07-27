package cn.kasuminova.astd.impl.difficulty

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.logger
import lunalib.lunaSettings.LunaSettings
import lunalib.lunaSettings.LunaSettingsListener

/**
 * LunaLib 设置注册器：负责把轨一（固有缩放系数）的玩家档注册进 LunaLib 设置界面，
 * 并在设置保存时把解析结果刷新进 [DifficultyTuningImpl]。
 *
 * 注册内容：
 * - 档位 radio：迟暮(1.0) / 砺刃(2.0，默认) / 远征(3.0) / 破晓(5.0) / 自定义；
 * - 自定义系数滑条：1.0~5.0 步进 0.1，仅在选中「自定义」档时生效；
 * - 四段档位描述文本（套 A 定稿文案）。
 *
 * 在 `AsteriaDirectoratePlugin.onApplicationLoad` 调用 [register]。
 */
object DifficultySettingsRegistrar {

    private val category get() = I18n.Categories.MOD

    /** 注册设置项并应用当前生效的系数。重复调用安全（LunaLib 侧按 field id 去重）。 */
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
        listOf("dusk", "blade", "expedition", "dawn").forEach { tier ->
            LunaSettings.SettingsCreator.addText(
                keys.MOD_ID,
                "astd_difficulty_desc_$tier",
                I18n[category, "settings.difficulty.tier.desc.$tier"],
                "",
            )
        }
        LunaSettings.SettingsCreator.refresh()

        applyCurrentSettings()
        LunaSettings.addListener(object : LunaSettingsListener {
            override fun settingsChanged(modID: String) {
                if (modID == DifficultySettingsKeys.MOD_ID) applyCurrentSettings()
            }
        })
    }

    /**
     * 读取当前设置并解析出系数，刷新 [DifficultyTuningImpl]。
     * 显示名未命中预设档且非自定义档时，回退默认档并打 warn 日志。
     */
    private fun applyCurrentSettings() {
        val keys = DifficultySettingsKeys
        val selected = LunaSettings.getString(keys.MOD_ID, DifficultySettingsKeys.FIELD_TIER).orEmpty()
        val customScale = LunaSettings.getDouble(keys.MOD_ID, DifficultySettingsKeys.FIELD_CUSTOM_SCALE)
            ?.toFloat()
            ?: DifficultySettingsKeys.DEFAULT_SCALE

        val resolved = keys.resolveTier(selected, customScale)
        if (!resolved.matched && selected.isNotEmpty()) {
            logger.warn("[ASTD] 难度档位显示名未命中：'$selected'，回退默认档（砺刃 2.0）")
        }

        DifficultyTuningImpl.applyResolvedScale(resolved.scale, resolved.displayName)
    }
}
