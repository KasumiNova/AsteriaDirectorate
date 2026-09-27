package cn.kasuminova.astd.impl.difficulty

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.impl.combat.DualModeSettingsImpl
import cn.kasuminova.astd.internal.i18n.I18n
import lunalib.lunaSettings.LunaSettings
import lunalib.lunaSettings.LunaSettingsListener

/**
 * LunaLib 设置注册器（ASTD 全部 LunaLib 设置项的单一注册/回调入口）：
 * 把设置项注册进 LunaLib 设置界面，并在设置保存时把解析结果刷新进各持有对象。
 *
 * 注册内容：
 * - 敌方档位 radio：迟暮(1.0) / 砺刃(2.0，默认) / 远征(3.0) / 破晓(5.0) / 自定义 → [DifficultyTuningImpl.fixedScale]；
 * - 敌方自定义系数滑条：1.0~5.0 步进 0.1，仅在选中「自定义」档时生效；
 * - 我方（玩家阵营）档位 radio：四个预设档（无自定义档），默认砺刃(2.0) → [DifficultyTuningImpl.playerFixedScale]；
 * - 「双模式切换器自动模式免自动化点数」开关（默认开启）→ [DualModeSettingsImpl]；
 * - 四段档位描述文本（套 A 定稿文案）。
 *
 * 在 `AsteriaDirectoratePlugin.onApplicationLoad` 经 [LunaLibSupport.isAvailable] 门控后调用 [register]
 * （未安装 LunaLib 时不注册，全部设置项取默认值）。
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
     * 读取当前设置并解析出系数/开关，刷新 [DifficultyTuningImpl] 与 [DualModeSettingsImpl]。
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
            AstdLog.logger.warn("[ASTD] 敌方难度档位显示名未命中：'$selected'，回退默认档（砺刃 2.0）")
        }
        DifficultyTuningImpl.applyResolvedScale(resolved.scale, resolved.displayName)

        // 我方档位 radio 不含自定义档，customScale 入参不会被消费（自定义显示名不是可选项）
        val selectedPlayer = LunaSettings.getString(keys.MOD_ID, DifficultySettingsKeys.FIELD_PLAYER_TIER).orEmpty()
        val resolvedPlayer = keys.resolveTier(selectedPlayer, DifficultySettingsKeys.DEFAULT_SCALE)
        if (!resolvedPlayer.matched && selectedPlayer.isNotEmpty()) {
            AstdLog.logger.warn("[ASTD] 我方难度档位显示名未命中：'$selectedPlayer'，回退默认档（砺刃 2.0）")
        }
        DifficultyTuningImpl.applyResolvedPlayerScale(resolvedPlayer.scale, resolvedPlayer.displayName)

        val freeAutoPoints = LunaSettings.getBoolean(keys.MOD_ID, DualModeSettingsImpl.FIELD_FREE_AUTO_POINTS)
            ?: DualModeSettingsImpl.DEFAULT_FREE_AUTO_POINTS
        DualModeSettingsImpl.applyResolvedExempt(freeAutoPoints)
    }
}
