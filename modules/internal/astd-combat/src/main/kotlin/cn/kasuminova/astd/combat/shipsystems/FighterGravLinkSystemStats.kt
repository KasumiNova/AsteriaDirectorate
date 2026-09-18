package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.lens.system.FighterGravLinkTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import java.awt.Color

/**
 * 战机引力联结器（Fighter Gravity Link）——飞蓬级（ZW-102）舰船系统
 * （purple/20-production.md §1，2026-09 重构）。
 *
 * 以原版召回装置（recalldevice / RecallDeviceStats）为基线的增强，三段行为：
 *
 * 1. **持续强化（IN/ACTIVE，至多 15s）**：本舰全部机群时间流速 +100%~200%、承伤 -25%~75%
 *    （轨一三锚点，玩家固定 v2，数值见 [FighterGravLinkTuning]），按 effectLevel 平滑渐入；
 *    战机附加持续 jitter 特效（透镜紫）。buff 打在战机自身 mutableStats 上（原版无航母侧
 *    「战机承伤」键），随系统结束在本脚本 unapply / OUT 帧严格配对 unmodify。
 * 2. **代价（IN/ACTIVE）**：每秒产出舰船**基础**最大辐能 7% 的软辐能（按 effectLevel 折算）；
 *    进入 OUT 瞬间将未耗散的全部软辐能等量转化为硬辐能（可能直接过载，设计本意）。
 * 3. **召回（ACTIVE → OUT 瞬间，一次性）**：全部部署在外的战机即刻回收进机库并快速重新出击
 *    ——复用原版召回语义：`bay.land()`（瞬时移除）+ 每机 +1 快速整备额度 +
 *    `makeCurrentIntervalFast()`，补机由 wing/bay 既有自动链路完成（0.3~0.8s/架）；
 *    召回音效复用原版召回装置的 `system_phase_skimmer`。
 *
 * 触发闩：本系统 CSV active = 15s（非原版的 0），ACTIVE 期 effectLevel 恒 1.0，
 * 故召回/转化以「OUT 首帧 + customData 闩」一次性触发（原版单帧 effectLevel==1.0 写法
 * 在长 active 下会逐帧重复触发），闩在 unapply 清除以保证下次激活可用。
 *
 * Fail Fast：战斗内 engine/fighter API 均为安全访问，全程不包 try。
 */
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
                generateSoftFlux(engine, ship, effectLevel)
            }
            ShipSystemStatsScript.State.OUT -> {
                removeFighterBuffs(ship)
                triggerRecallOnce(engine, ship)
            }
            else -> removeFighterBuffs(ship)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        removeFighterBuffs(ship)
        val engine = Global.getCombatEngine() ?: return
        engine.customData.remove("$RECALL_DONE_KEY${System.identityHashCode(ship)}")
    }

    /** 机群强化（IN/ACTIVE 每帧）：时流 + 四承伤乘区按 effectLevel 渐入，附 jitter。 */
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

    /** 机群强化配对解除（OUT/unapply 每帧调用；对新补员战机为无副作用的空 unmodify）。 */
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

    /** 持续软辐能产出（IN/ACTIVE 每帧）：基础最大辐能 × 7%/s × effectLevel。 */
    private fun generateSoftFlux(engine: com.fs.starfarer.api.combat.CombatEngineAPI, ship: ShipAPI, effectLevel: Float) {
        val amount = engine.elapsedInLastFrame
        if (amount <= 0f) return
        val baseMaxFlux = ship.hullSpec.fluxCapacity
        val flux = FighterGravLinkTuning.softFluxPerSecond(baseMaxFlux) * effectLevel * amount
        if (flux > 0f) ship.fluxTracker.increaseFlux(flux, false)
    }

    /**
     * OUT 首帧一次性触发（customData 闩）：召回全部部署在外的战机 + 软→硬辐能转化。
     */
    private fun triggerRecallOnce(engine: com.fs.starfarer.api.combat.CombatEngineAPI, ship: ShipAPI) {
        val latchKey = "$RECALL_DONE_KEY${System.identityHashCode(ship)}"
        if (engine.customData[latchKey] == true) return
        engine.customData[latchKey] = true

        // 召回：复用原版召回装置语义（land = 瞬时移除 + 自动整备补员链路）。
        for (wing in ship.allWings) {
            val bay = wing.source ?: continue
            for (fighter in ArrayList(wing.wingMembers)) {
                if (fighter.isHulk) continue
                Global.getSoundPlayer().playSound(
                    RECALL_SOUND, 1f, 0.5f, fighter.location, fighter.velocity,
                )
                bay.fastReplacements = bay.fastReplacements + 1
                bay.makeCurrentIntervalFast()
                bay.land(fighter)
            }
        }

        // 代价结算：未耗散的全部软辐能等量转化为硬辐能。
        val tracker = ship.fluxTracker
        val soft = FighterGravLinkTuning.softFluxNow(tracker.currFlux, tracker.hardFlux)
        if (soft > 0f) {
            tracker.decreaseFlux(soft)
            tracker.increaseFlux(soft, true)
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
        /** 机群 buff 修饰句柄（战机 mutableStats 上的稳定 id，apply/unapply 严格配对）。 */
        private const val BUFF_MOD_ID = "astd_fighter_grav_link_buff"

        /** 召回一次性触发闩 customData key 前缀（每船一条，unapply 清除）。 */
        private const val RECALL_DONE_KEY = "astd_fighter_grav_link_recall_done:"

        /** 召回音效（复用原版召回装置的单机音效）。 */
        private const val RECALL_SOUND = "system_phase_skimmer"

        /** 持续期间战机 jitter（透镜紫，与紫线视觉统一）。 */
        private val JITTER_COLOR = Color(200, 160, 255, 120)
        private const val JITTER_COPIES = 3
        private val JITTER_KEY = Any()
    }
}
