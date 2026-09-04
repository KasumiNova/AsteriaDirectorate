package cn.kasuminova.astd.combat.lens.ui

import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 引力透镜级标记的玩家船左侧状态栏显示。
 *
 * 动机（spec §1.1）：两类标记需常驻状态显示。用原版官方
 * maintainStatusForPlayerShip 在战斗 UI 左侧维护条目（每帧调用刷新）。
 */
object LensMarkStatusBar {

    private const val ICON_DRIFT = "graphics/hullmods/astd_lens_array_core.png"
    private const val ICON_DEEP = "graphics/hullmods/astd_lens_array_core.png"

    /** 每帧调用：若玩家船带标记，维护左侧状态条目。 */
    fun maintain(engine: CombatEngineAPI) {
        val player: ShipAPI = engine.playerShip ?: return
        if (player.isHulk) return

        val drift = LensMarks.driftStacks(player)
        if (drift > 0) {
            engine.maintainStatusForPlayerShip(
                "astd_lens_drift_status",
                ICON_DRIFT,
                I18n[I18n.Categories.MOD, "ui.lens.status.drift"],
                I18n.t(I18n.Categories.MOD, "ui.lens.status.stacks", "stacks" to drift),
                true,
            )
        }

        val deep = LensMarks.deepWaterStacks(player)
        if (deep > 0) {
            engine.maintainStatusForPlayerShip(
                "astd_lens_deep_water_status",
                ICON_DEEP,
                I18n[I18n.Categories.MOD, "ui.lens.status.deep_water"],
                I18n.t(I18n.Categories.MOD, "ui.lens.status.stacks", "stacks" to deep),
                true,
            )
        }
    }
}
