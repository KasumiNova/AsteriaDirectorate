package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

class PlaceholderHullMod : BaseHullMod() {

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        // spec 在渲染路径必然已注入（BaseHullMod.init），直接取 id 拼接该类 4 个 id 各自的 desc key
        tooltip.hullmodCard(width, HullmodThemes.CREWED, spec?.displayName) {
            para("ui.hullmod.${spec.id.removePrefix("astd_")}.desc")
        }
    }
}
