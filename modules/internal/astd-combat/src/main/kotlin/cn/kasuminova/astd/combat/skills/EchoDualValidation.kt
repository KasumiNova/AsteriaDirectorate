package cn.kasuminova.astd.combat.skills

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.characters.LevelBasedEffect
import com.fs.starfarer.api.characters.ShipSkillEffect
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

class EchoDualValidation {

    class Level1 : ShipSkillEffect {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String, level: Float) {
            val ship = SkillUtil.getShip(stats)
            if (!SkillUtil.isAstdHull(ship)) return

            val key = "${id}_astd"
            stats.sensorStrength.modifyPercent(key, 25f)
            stats.sensorProfile.modifyPercent(key, -25f)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String) {
            val key = "${id}_astd"
            stats.sensorStrength.unmodify(key)
            stats.sensorProfile.unmodify(key)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.j("asteria_directorate", "skill.echo_dual_validation.l1")

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.ALL_SHIPS
    }

    class Level2 : ShipSkillEffect {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String, level: Float) {
            val ship = SkillUtil.getShip(stats)
            if (!SkillUtil.isAstdHull(ship)) return

            val key = "${id}_astd"
            stats.eccmChance.modifyPercent(key, 20f)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String) {
            val key = "${id}_astd"
            stats.eccmChance.unmodify(key)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.j("asteria_directorate", "skill.echo_dual_validation.l2")

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.ALL_SHIPS
    }
}
