package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.loading.HullModSpecAPI

object ASTDHullmodSprites {

    const val SWITCHER_CREW = "graphics/hullmods/astd_dual_mode_switcher_crew.png"
    const val SWITCHER_AUTO = "graphics/hullmods/astd_dual_mode_switcher_auto.png"

    fun swap(spec: HullModSpecAPI?, sprite: String) {
        if (spec == null || spec.spriteName == sprite) return
        spec.spriteName = sprite
    }

    fun switcherSpriteFor(variant: ShipVariantAPI, config: ASTDDualModeConfig): String =
        if (variant.permaMods.contains(config.automatedModeId)) SWITCHER_AUTO else SWITCHER_CREW
}
