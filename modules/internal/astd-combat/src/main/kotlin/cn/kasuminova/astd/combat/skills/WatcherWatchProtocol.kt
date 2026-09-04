package cn.kasuminova.astd.combat.skills

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.characters.LevelBasedEffect
import com.fs.starfarer.api.characters.ShipSkillEffect
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

class WatcherWatchProtocol {

    class Level1 : ShipSkillEffect {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String, level: Float) {
            val ship = SkillUtil.getShip(stats)
            if (!SkillUtil.isAstdHull(ship)) return

            val key = "${id}_astd"
            stats.maxSpeed.modifyMult(key, 1.08f)
            stats.acceleration.modifyMult(key, 1.15f)
            stats.deceleration.modifyMult(key, 1.15f)
            stats.turnAcceleration.modifyMult(key, 1.15f)
            stats.maxTurnRate.modifyMult(key, 1.15f)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String) {
            val key = "${id}_astd"
            stats.maxSpeed.unmodify(key)
            stats.acceleration.unmodify(key)
            stats.deceleration.unmodify(key)
            stats.turnAcceleration.unmodify(key)
            stats.maxTurnRate.unmodify(key)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.j("asteria_directorate", "skill.watcher_watch_protocol.l1")

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.ALL_SHIPS
    }

    class Level2 : ShipSkillEffect {
        override fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String, level: Float) {
            val ship = SkillUtil.getShip(stats)
            if (!SkillUtil.isAstdHull(ship)) return

            val key = "${id}_astd"
            stats.fluxCapacity.modifyMult(key, 1.15f)
            stats.fluxDissipation.modifyMult(key, 1.15f)
        }

        override fun unapply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String) {
            val key = "${id}_astd"
            stats.fluxCapacity.unmodify(key)
            stats.fluxDissipation.unmodify(key)
        }

        override fun getEffectDescription(level: Float): String =
            I18n.j("asteria_directorate", "skill.watcher_watch_protocol.l2")

        override fun getEffectPerLevelDescription(): String? = null

        override fun getScopeDescription(): LevelBasedEffect.ScopeDescription =
            LevelBasedEffect.ScopeDescription.ALL_SHIPS
    }
}
