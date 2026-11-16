package cn.kasuminova.astd.combat.automation.scenario.description

import cn.kasuminova.astd.impl.difficulty.DifficultySettingsKeys
import lunalib.lunaSettings.LunaSettings

/** 独立成类是为了只在 LunaLib 可用分支内加载，避免缺少 LunaLib 时提前链接 `lunalib.*`。 */
object LunaSettingsProbe {

    fun reportSettingsChanged() {
        LunaSettings.reportSettingsChanged(DifficultySettingsKeys.MOD_ID)
    }
}
