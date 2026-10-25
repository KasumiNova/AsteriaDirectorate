package cn.kasuminova.astd.impl.campaign

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.campaign.BountySettings

object BountySettingsImpl : BountySettings {

    const val FIELD_FILL_TO_FLEET_CAP: String = "astd_bounty_fill_to_fleet_cap"

    const val DEFAULT_FILL_TO_FLEET_CAP: Boolean = false

    @Volatile
    override var fillToFleetCap: Boolean = DEFAULT_FILL_TO_FLEET_CAP
        private set

    fun applyResolvedFillToFleetCap(enabled: Boolean) {
        if (enabled == fillToFleetCap) return
        fillToFleetCap = enabled
        AstdLog.logger.info("[ASTD] 赏金舰队「补齐至游戏舰队上限」变更：${if (enabled) "开启" else "关闭"}")
    }
}
