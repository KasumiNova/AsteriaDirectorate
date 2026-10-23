package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.lens.system.FighterGravLinkTuning
import cn.kasuminova.astd.combat.shipsystems.FighterGravLinkSystemStats.Companion.LAND_EFFECT_LEVEL
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import java.awt.Color

class FighterGravLinkSystemStats : BaseShipSystemScript() {

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        when (state) {
            ShipSystemStatsScript.State.IN, ShipSystemStatsScript.State.ACTIVE -> {
                applyFighterBuffs(ship, effectLevel)
                if (state == ShipSystemStatsScript.State.ACTIVE) {
                    cancelWhenNoFighters(engine, ship)
                }
            }

            ShipSystemStatsScript.State.OUT -> {
                removeFighterBuffs(ship)
                triggerRecallOnce(engine, ship)
                advanceRecallVfx(engine, ship, effectLevel)
            }

            else -> removeFighterBuffs(ship)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        removeFighterBuffs(ship)
        val engine = Global.getCombatEngine() ?: return
        // land 阈值在低帧率下可能被末帧越过；引擎在 effectLevel 归零帧必调 unapply，回收逻辑挂在此必然路径
        finishRecall(engine, ship)
        engine.customData.remove(recallDoneKey(ship))
        engine.customData.remove(recallListKey(ship))
        engine.customData.remove(activeStartKey(ship))
    }

    private fun applyFighterBuffs(ship: ShipAPI, effectLevel: Float) {
        val values = FighterGravLinkTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val timeMult = 1f + (values.timeMult - 1f) * effectLevel
        val damageTakenMult = 1f - (1f - values.damageTakenMult) * effectLevel

        for (wing in ship.allWings) {
            for (fighter in wing.wingMembers) {
                if (fighter.isHulk) continue
                val stats = fighter.mutableStats
                stats.timeMult.modifyMult(BUFF_MOD_ID, timeMult)
                stats.hullDamageTakenMult.modifyMult(BUFF_MOD_ID, damageTakenMult)
                stats.armorDamageTakenMult.modifyMult(BUFF_MOD_ID, damageTakenMult)
                stats.shieldDamageTakenMult.modifyMult(BUFF_MOD_ID, damageTakenMult)
                stats.empDamageTakenMult.modifyMult(BUFF_MOD_ID, damageTakenMult)
                fighter.setJitter(
                    JITTER_KEY, JITTER_COLOR, effectLevel, JITTER_COPIES,
                    0f, 5f + fighter.collisionRadius * 0.5f,
                )
            }
        }
    }

    private fun removeFighterBuffs(ship: ShipAPI) {
        for (wing in ship.allWings) {
            for (fighter in wing.wingMembers) {
                if (fighter.isHulk) continue
                val stats = fighter.mutableStats
                stats.timeMult.unmodifyMult(BUFF_MOD_ID)
                stats.hullDamageTakenMult.unmodifyMult(BUFF_MOD_ID)
                stats.armorDamageTakenMult.unmodifyMult(BUFF_MOD_ID)
                stats.shieldDamageTakenMult.unmodifyMult(BUFF_MOD_ID)
                stats.empDamageTakenMult.unmodifyMult(BUFF_MOD_ID)
            }
        }
    }

    // 提前关闭必须走 useSystem() 的 fire 路径；deactivate() 等价 forceDeactivate，直接跳 COOLDOWN 跳过 OUT 窗口
    private fun cancelWhenNoFighters(engine: CombatEngineAPI, ship: ShipAPI) {
        val now = engine.getTotalElapsedTime(false)
        val key = activeStartKey(ship)
        val start = engine.customData[key] as? Float
        if (start == null) {
            engine.customData[key] = now
            return
        }
        val elapsed = now - start
        if (elapsed >= FighterGravLinkTuning.NO_FIGHTER_CANCEL_GRACE_SECONDS && !hasAliveFighters(ship)) {
            ship.useSystem()
        }
    }

    // 返航中的战机在原版 orderReturn 后从 wingMembers 移入 returning 列表，仍属存活战力
    private fun hasAliveFighters(ship: ShipAPI): Boolean =
        ship.allWings.any { wing ->
            wing.wingMembers.any { !it.isHulk } ||
                wing.returning.any { it.fighter.isAlive && !it.fighter.isHulk } ||
                wing.source?.getTimeUntilNextReplacement()?.let { it >= 0f } == true
        }

    private fun triggerRecallOnce(engine: CombatEngineAPI, ship: ShipAPI) {
        val latchKey = recallDoneKey(ship)
        if (engine.customData[latchKey] == true) return
        engine.customData[latchKey] = true

        val recallTargets = mutableListOf<ShipAPI>()
        for (wing in ship.allWings) {
            if (wing.source == null) continue
            for (fighter in wing.wingMembers) {
                if (fighter.isHulk) continue
                recallTargets += fighter
                Global.getSoundPlayer().playSound(
                    RECALL_SOUND, 1f, 0.5f, fighter.location, fighter.velocity,
                )
            }
        }
        engine.customData[recallListKey(ship)] = recallTargets

        // increaseFlux(x, true) 在散辐/过载状态下被原版闸门拦截不生效；setHardFlux 无闸门
        val tracker = ship.fluxTracker
        if (FighterGravLinkTuning.softFluxNow(tracker.currFlux, tracker.hardFlux) > 0f) {
            tracker.hardFlux = tracker.currFlux
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun advanceRecallVfx(engine: CombatEngineAPI, ship: ShipAPI, effectLevel: Float) {
        val targets = engine.customData[recallListKey(ship)] as? List<ShipAPI> ?: return
        val landNow = effectLevel <= LAND_EFFECT_LEVEL
        for (fighter in targets) {
            if (fighter.isHulk || !fighter.isAlive) continue
            val bay = fighter.wing?.source
            if (bay == null) {
                fighter.isPhased = false
                fighter.extraAlphaMult = 1f
                continue
            }
            if (landNow) {
                bay.makeCurrentIntervalFast()
                bay.land(fighter)
            } else {
                fighter.isPhased = true
                fighter.extraAlphaMult = effectLevel.coerceIn(0f, 1f)
                fighter.setJitter(
                    RECALL_JITTER_KEY, RECALL_JITTER_COLOR, 1f, RECALL_JITTER_COPIES,
                    0f, 5f + fighter.collisionRadius,
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun finishRecall(engine: CombatEngineAPI, ship: ShipAPI) {
        val targets = engine.customData[recallListKey(ship)] as? List<ShipAPI> ?: return
        var stranded = 0
        for (fighter in targets) {
            if (fighter.isHulk || !fighter.isAlive) continue
            val bay = fighter.wing?.source
            if (bay == null) {
                fighter.isPhased = false
                fighter.extraAlphaMult = 1f
                stranded++
            } else {
                bay.makeCurrentIntervalFast()
                bay.land(fighter)
            }
        }
        if (stranded > 0) {
            log.warn(
                "fighter_grav_link: $stranded fighter(s) stranded without a valid bay on ${ship.hullSpec?.hullId}, " +
                        "visual state reset instead of landing",
            )
        }
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        val suffix = when (state) {
            ShipSystemStatsScript.State.IN -> "in"
            ShipSystemStatsScript.State.ACTIVE -> "active"
            ShipSystemStatsScript.State.OUT -> "out"
            else -> return null
        }
        return ShipSystemStatsScript.StatusData(
            I18n[I18n.Categories.MOD, "system.fighter_grav_link.status.$suffix"],
            false,
        )
    }

    companion object {
        private const val BUFF_MOD_ID = "astd_fighter_grav_link_buff"

        private const val RECALL_DONE_KEY = "astd_fighter_grav_link_recall_done:"

        private const val RECALL_LIST_KEY = "astd_fighter_grav_link_recall_list:"

        private const val ACTIVE_START_KEY = "astd_fighter_grav_link_active_start:"

        private const val LAND_EFFECT_LEVEL = 0.05f

        private const val RECALL_SOUND = "system_phase_skimmer"

        private val JITTER_COLOR = Color(200, 160, 255, 120)
        private const val JITTER_COPIES = 3
        private val JITTER_KEY = Any()

        private val RECALL_JITTER_COLOR = Color(170, 140, 255, 155)
        private const val RECALL_JITTER_COPIES = 10
        private val RECALL_JITTER_KEY = Any()

        private val log = Global.getLogger(FighterGravLinkSystemStats::class.java)

        // customData 键用 ship.id（原版 RecallDeviceStats 同款），不用 identityHashCode：其值可被复用，有陈旧闩串船风险
        private fun recallDoneKey(ship: ShipAPI): String = "$RECALL_DONE_KEY${ship.id}"
        private fun recallListKey(ship: ShipAPI): String = "$RECALL_LIST_KEY${ship.id}"
        private fun activeStartKey(ship: ShipAPI): String = "$ACTIVE_START_KEY${ship.id}"
    }
}
