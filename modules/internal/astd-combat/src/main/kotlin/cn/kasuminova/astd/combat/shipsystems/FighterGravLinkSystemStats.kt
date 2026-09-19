package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.lens.system.FighterGravLinkTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import java.awt.Color

/**
 * 战机引力联结器（Fighter Gravity Link）——飞蓬级（ZW-102）舰船系统
 * （purple/20-production.md §1，2026-09 重构；2026-09 二轮：toggle 化 + 原版式召回特效）。
 *
 * 以原版召回装置（recalldevice / RecallDeviceStats）为基线的增强，三段行为：
 *
 * 1. **持续强化（IN/ACTIVE，至多 15s，可提前手动关闭）**：本舰全部机群时间流速 +100%~200%、
 *    承伤 -25%~75%（轨一三锚点，玩家固定 v2，数值见 [FighterGravLinkTuning]），按 effectLevel
 *    平滑渐入；战机附加持续 jitter 特效（透镜紫）。buff 打在战机自身 mutableStats 上（原版无
 *    航母侧「战机承伤」键），随系统结束在 OUT 帧 / unapply 严格配对 unmodify。
 *    系统为 **toggle 型**（CSV active 为空 + toggle=true）：原版 ChargeTracker 仅 infinite
 *    模式在 ACTIVE 期响应再次按键（canBeDeactivated 默认 true），故 ACTIVE 上限 15s 由本脚本
 *    计时补发 useSystem() 收口；机群全灭（宽限 1s 后无在外战机）同样提前结束。收口必须走
 *    fire 路径——`ShipSystemAPI.deactivate()` 等价 forceDeactivate，直接跳 COOLDOWN、
 *    跳过 OUT 窗口，召回结算不会触发。
 * 2. **代价（IN/ACTIVE）**：每秒产出舰船**基础**最大辐能 7% 的软辐能（按 effectLevel 折算）；
 *    进入 OUT 瞬间将全部当前辐能直接置为硬辐能（`setHardFlux(currFlux)`：软辐能等量硬化）。
 *    不用 `increaseFlux(x, true)`——其在散辐/过载状态下被原版闸门静默吞掉（返回 false 且
 *    不记日志），会导致软辐能扣了却没转化；`setHardFlux` 无闸门。注意 `setHardFlux` 只做赋值，
 *    转化本身不会触发过载判定；真实代价是辐能全量定格为硬辐能后，下一次任意硬辐能来源
 *    （如护盾吸伤）即可能把舰船推进过载。
 * 3. **召回（OUT 窗口，对齐原版召回装置特效）**：OUT 首帧快照全部部署在外的战机并结算软→硬
 *    转化；整个 OUT 窗口（CSV down=1s，effectLevel 1→0）快照内战机持续 jitter、进入相位态
 *    （免伤免撞，原版召回同款保护）并随 effectLevel 渐隐；窗口结束（effectLevel ≤ 0.05）
 *    逐架 `bay.land()` 回收并 `makeCurrentIntervalFast()` 快速重新出击——land 内部对整备
 *    倒计时 > 10000 的战机自动发放快速整备额度（本系统机群倒计时恒为 1e7 量级，必中），
 *    补机由 wing/bay 既有自动链路完成；land 时机的单点阈值在低帧率下可能被末帧越过，
 *    故 unapply（effectLevel 归零帧必调）对快照内仍存活战机做最终收口（[finishRecall]）；
 *    召回音效复用原版召回装置的 `system_phase_skimmer`。
 *
 * 触发闩：召回快照/转化为「OUT 首帧 + customData 闩」一次性触发；ACTIVE 起始时间戳同样
 * 存 customData（每船一条），全部闩在 unapply 清除以保证下次激活可用。
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
                if (state == ShipSystemStatsScript.State.ACTIVE) {
                    enforceActiveLimits(engine, ship)
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
        // 最终回收：OUT 窗口内 land 以「effectLevel ≤ 阈值」为时机，低帧率下窗口最后一帧
        // 可能越过阈值（状态已翻 COOLDOWN、apply 不再被调），故回收收口挂在本必然路径——
        // 引擎在 effectLevel 归零那帧必定调用 unapply。窗口内已 land 的战机 isAlive=false
        // 会被跳过，不会重复计快速整备。
        finishRecall(engine, ship)
        engine.customData.remove(recallDoneKey(ship))
        engine.customData.remove(recallListKey(ship))
        engine.customData.remove(activeStartKey(ship))
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
    private fun generateSoftFlux(engine: CombatEngineAPI, ship: ShipAPI, effectLevel: Float) {
        val amount = engine.elapsedInLastFrame
        if (amount <= 0f) return
        val baseMaxFlux = ship.hullSpec.fluxCapacity
        val flux = FighterGravLinkTuning.softFluxPerSecond(baseMaxFlux) * effectLevel * amount
        if (flux > 0f) ship.fluxTracker.increaseFlux(flux, false)
    }

    /**
     * ACTIVE 持续约束（每帧）：记录 ACTIVE 起始时间戳，达到
     * [FighterGravLinkTuning.MAX_ACTIVE_SECONDS] 补发 useSystem() 主动关闭（toggle 系统引擎
     * 不自动结束）；宽限期后机群全灭同样提前结束（无可强化对象，提前进冷却结算）。
     *
     * 关闭必须走 fire 路径（useSystem → ACTIVE 期再次按键 → OUT 充能消退），不能用
     * `ShipSystemAPI.deactivate()`——其等价 ChargeTracker.forceDeactivate，直接跳 COOLDOWN、
     * 跳过 OUT 窗口，召回结算（快照/软硬转化/land 回收）不会触发。
     */
    private fun enforceActiveLimits(engine: CombatEngineAPI, ship: ShipAPI) {
        val now = engine.getTotalElapsedTime(false)
        val key = activeStartKey(ship)
        val start = engine.customData[key] as? Float
        if (start == null) {
            engine.customData[key] = now
            return
        }
        val elapsed = now - start
        if (elapsed >= FighterGravLinkTuning.MAX_ACTIVE_SECONDS) {
            ship.useSystem()
            return
        }
        if (elapsed >= FighterGravLinkTuning.NO_FIGHTER_CANCEL_GRACE_SECONDS && !hasDeployedFighters(ship)) {
            ship.useSystem()
        }
    }

    /** 任一联队仍有存活在外战机。 */
    private fun hasDeployedFighters(ship: ShipAPI): Boolean =
        ship.allWings.any { wing -> wing.wingMembers.any { !it.isHulk } }

    /**
     * OUT 首帧一次性触发（customData 闩）：快照召回目标机群 + 软→硬辐能转化 + 逐机音效。
     * 快照语义：OUT 窗口内新补员/新发射的战机不受召回影响（原版召回装置同款快照语义）。
     */
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

        // 代价结算：全部当前辐能直接置为硬辐能（软辐能等量硬化）。
        // 不用 increaseFlux(x, true)——其在散辐/过载状态下被原版闸门静默吞掉；
        // setHardFlux 无闸门，转化在所有状态下都确定生效。
        val tracker = ship.fluxTracker
        if (FighterGravLinkTuning.softFluxNow(tracker.currFlux, tracker.hardFlux) > 0f) {
            tracker.setHardFlux(tracker.currFlux)
        }
    }

    /**
     * 召回特效推进（OUT 每帧，对齐原版 RecallDeviceStats 视觉语义）：快照内战机相位态 +
     * jitter + 随 effectLevel（1→0）渐隐；窗口结束（effectLevel ≤ [LAND_EFFECT_LEVEL]）
     * 逐架 land 回收并触发快速整备补员。漏帧保险由 [finishRecall] 承担（unapply 必然路径）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun advanceRecallVfx(engine: CombatEngineAPI, ship: ShipAPI, effectLevel: Float) {
        val targets = engine.customData[recallListKey(ship)] as? List<ShipAPI> ?: return
        val landNow = effectLevel <= LAND_EFFECT_LEVEL
        for (fighter in targets) {
            if (fighter.isHulk || !fighter.isAlive) continue
            val bay = fighter.wing?.source
            if (bay == null) {
                // 联队/甲板已失效（如母舰甲板被摧毁）：复位视觉状态，不做回收。
                fighter.setPhased(false)
                fighter.setExtraAlphaMult(1f)
                continue
            }
            if (landNow) {
                bay.makeCurrentIntervalFast()
                bay.land(fighter)
            } else {
                fighter.setPhased(true)
                fighter.setExtraAlphaMult(effectLevel.coerceIn(0f, 1f))
                fighter.setJitter(
                    RECALL_JITTER_KEY, RECALL_JITTER_COLOR, 1f, RECALL_JITTER_COPIES,
                    0f, 5f + fighter.collisionRadius,
                )
            }
        }
    }

    /**
     * 召回最终收口（unapply 调用，必然路径）：OUT 窗口的 land 时机是「effectLevel ≤ 阈值」
     * 的单点条件，低帧率下窗口最后一帧可能越过阈值（此时状态已翻 COOLDOWN、apply 不再
     * 被调），导致快照机群永不回收（保持相位隐形）。本方法对快照内仍存活的战机统一结算：
     * 甲板有效则 land 回收（窗口内已 land 的 isAlive=false 自然跳过）；甲板失效则复位
     * 视觉状态并记日志（战机滞留战场属异常可观测事件）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun finishRecall(engine: CombatEngineAPI, ship: ShipAPI) {
        val targets = engine.customData[recallListKey(ship)] as? List<ShipAPI> ?: return
        var stranded = 0
        for (fighter in targets) {
            if (fighter.isHulk || !fighter.isAlive) continue
            val bay = fighter.wing?.source
            if (bay == null) {
                fighter.setPhased(false)
                fighter.setExtraAlphaMult(1f)
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
        /** 机群 buff 修饰句柄（战机 mutableStats 上的稳定 id，apply/unapply 严格配对）。 */
        private const val BUFF_MOD_ID = "astd_fighter_grav_link_buff"

        /** 召回一次性触发闩 customData key 前缀（每船一条，unapply 清除）。 */
        private const val RECALL_DONE_KEY = "astd_fighter_grav_link_recall_done:"

        /** 召回目标快照 customData key 前缀（OUT 首帧写入，unapply 清除）。 */
        private const val RECALL_LIST_KEY = "astd_fighter_grav_link_recall_list:"

        /** ACTIVE 起始时间戳 customData key 前缀（ACTIVE 首帧写入，unapply 清除）。 */
        private const val ACTIVE_START_KEY = "astd_fighter_grav_link_active_start:"

        /** OUT 窗口内执行 land 的 effectLevel 阈值（窗口将近结束时一次性回收）。 */
        private const val LAND_EFFECT_LEVEL = 0.05f

        /** 召回音效（复用原版召回装置的单机音效）。 */
        private const val RECALL_SOUND = "system_phase_skimmer"

        /** 持续期间战机 jitter（透镜紫，与紫线视觉统一）。 */
        private val JITTER_COLOR = Color(200, 160, 255, 120)
        private const val JITTER_COPIES = 3
        private val JITTER_KEY = Any()

        /** 召回窗口战机 jitter（对齐原版召回装置配色，取透镜紫变体）。 */
        private val RECALL_JITTER_COLOR = Color(170, 140, 255, 155)
        private const val RECALL_JITTER_COPIES = 10
        private val RECALL_JITTER_KEY = Any()

        private val log = Global.getLogger(FighterGravLinkSystemStats::class.java)

        // customData 键用 ship.id（战斗内唯一，原版 RecallDeviceStats 同款口径），
        // 不用 identityHashCode——其值可被后续对象复用，存在陈旧闩串船的理论风险。
        private fun recallDoneKey(ship: ShipAPI): String = "$RECALL_DONE_KEY${ship.id}"
        private fun recallListKey(ship: ShipAPI): String = "$RECALL_LIST_KEY${ship.id}"
        private fun activeStartKey(ship: ShipAPI): String = "$ACTIVE_START_KEY${ship.id}"
    }
}
