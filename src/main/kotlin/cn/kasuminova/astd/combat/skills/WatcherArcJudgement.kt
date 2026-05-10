package cn.kasuminova.astd.combat.skills

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.characters.LevelBasedEffect
import com.fs.starfarer.api.characters.ShipSkillEffect
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

class WatcherArcJudgement {

    class Level1 : ShipSkillEffect {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String, level: Float) {
            val ship = SkillUtil.getShip(stats)
            if (!SkillUtil.isAstdHull(ship)) return

            val key = "${id}_astd"
            stats.energyRoFMult.modifyPercent(key, 15f)
            stats.energyWeaponFluxCostMod.modifyMult(key, 0.9f)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String) {
            val key = "${id}_astd"
            stats.energyRoFMult.unmodify(key)
            stats.energyWeaponFluxCostMod.unmodify(key)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.j("asteria_directorate", "skill.watcher_arc_judgement.l1")

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.ALL_SHIPS
    }

    class Level2 : ShipSkillEffect {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String, level: Float) {
            val ship = SkillUtil.getShip(stats)
            if (!SkillUtil.isAstdHull(ship)) return

            val key = "${id}_astd"
            stats.energyWeaponDamageMult.modifyPercent(key, 10f)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String) {
            val key = "${id}_astd"
            stats.energyWeaponDamageMult.unmodify(key)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.j("asteria_directorate", "skill.watcher_arc_judgement.l2")

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.ALL_SHIPS
    }
}
