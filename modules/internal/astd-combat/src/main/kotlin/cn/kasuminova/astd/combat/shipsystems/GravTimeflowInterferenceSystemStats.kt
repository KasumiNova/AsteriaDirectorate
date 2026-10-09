package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravTimeflowTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.GravTimeflowLinkVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 引力时流干涉器（Gravity Timeflow Interference，系统 id：astd_grav_timeflow_interference）
 * ——决明级（ZW-001）舰船系统（purple/10-unique.md §1）。
 *
 * 目标锁定（对齐原版熵放大器 [com.fs.starfarer.api.impl.combat.EntropyAmplifierStats] 模式，
 * 与引力裂隙发生器同款约定）：
 * - 玩家操控取 shipTarget（锁定的友军舰船）；AI 取
 *   [ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM]（由 [GravTimeflowInterferenceSystemAI]
 *   评分选靶后写入）；
 * - 目标须为友军（owner 相同）、非战机/无人机、存活非残骸，且在有效射程
 *   （[GravTimeflowTuning.BASE_RANGE] 经 systemRangeBonus 折算）内；
 *   不满足时系统不可激活（[isUsable] = false），HUD 提示「无有效目标」/「超出射程」。
 *
 * 三段行为（CSV：chargeUp 0.5 / active 10 / down 0.5 / cooldown 30）：
 * - **IN 首帧（activation 闩建立）**：锁定目标、建立时流弧线特效（[GravTimeflowLinkVfx]）、
 *   激活瞬间立即缩减目标舰船系统剩余冷却（[GravTimeflowTuning.SYSTEM_COOLDOWN_REDUCTION]
 *   三锚点比例；目标系统未处于冷却时该部分无效果，不写兜底）；
 * - **IN/ACTIVE/OUT 每帧**：目标与其全部在外舰载机的时间流速按
 *   [GravTimeflowTuning.TIME_MULT] 三锚点乘区随 effectLevel 渐入/渐出
 *   （1 + (乘区 − 1) × effectLevel；modifier 打在目标与战机自身 mutableStats 上，
 *   unapply 严格配对 unmodify；中途新起飞战机自然被下帧覆盖，被击毁战机随实体消失）；
 *   自身时间流速不变（不给自身 timeMult）；**持续期间不做射程检查**——目标驶出射程
 *   不中断干涉（规格原文）；目标消亡/离场（[CombatEngineAPI.isEntityInPlay] 探活）
 *   仅提前收弧线与 jitter，系统照常走完窗口；
 * - **收口**：[teardown] 为唯一收口路径（移除激活闩、淡出弧线、解除目标侧全部时流修饰），
 *   unapply 与源舰残骸化（isHulk）分支共用——残骸化后引擎不保证回调 unapply，
 *   缺该分支会让友军目标舰整场保持时流修饰；
 * - **jitter（透镜紫）**：持续期间自身淡 jitter、目标明显 jitter、目标所属战机轻微 jitter，
 *   每帧刷新；结束后停止刷新，由原版 jitter 衰减机制自然消退（仓库既有口径，
 *   对照 GravStormSystemStats / FighterGravLinkSystemStats，原版 API 无显式清除路径）。
 *
 * 自身特效弧线在双舰之间渲染（BELOW_SHIPS_LAYER，被舰船盖住），流向目标舰。
 *
 * Fail Fast：战斗内 engine/ship API 均为安全访问，全程不包 try；
 * BoxUtil 弧线实体注册失败有 WARN 日志（视觉缺席，机制照常）。
 */
class GravTimeflowInterferenceSystemStats : BaseShipSystemScript() {

    /** 一次激活的触发闩（IN 首帧建立，unapply 清除）。 */
    private class TimeflowActivation(
        val target: ShipAPI,
        val link: GravTimeflowLinkVfx?,
    )

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (ship.isHulk) {
            // 源舰残骸化：引擎不保证后续回调 unapply，主动走同一收口路径，
            // 否则友军目标舰会整场保持时流修饰
            teardown(engine, ship)
            return
        }

        when (state) {
            ShipSystemStatsScript.State.IN -> activateOnce(engine, ship)
            else -> Unit
        }
        when (state) {
            ShipSystemStatsScript.State.IN,
            ShipSystemStatsScript.State.ACTIVE,
            ShipSystemStatsScript.State.OUT,
            -> driveInterference(engine, ship, effectLevel)

            else -> Unit
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        teardown(engine, ship)
    }

    /** 收口唯一路径（unapply 与 hulk 分支共用）：移除激活闩、淡出弧线、解除目标侧全部时流修饰。 */
    private fun teardown(engine: CombatEngineAPI, ship: ShipAPI) {
        val activation = engine.customData.remove(activationKey(ship)) as? TimeflowActivation ?: return
        activation.link?.dispose()
        removeTargetTimeflow(activation.target)
    }

    /**
     * IN 首帧（activation key 缺席即首帧闩）：锁定目标舰、建立时流弧线、
     * 立即缩减目标舰船系统剩余冷却（未在冷却中则无效果）。目标在激活瞬间失效属于
     * 异常路径（isUsable 已挡），记录错误并放弃本次激活。
     */
    private fun activateOnce(engine: CombatEngineAPI, ship: ShipAPI) {
        if (engine.customData[activationKey(ship)] != null) return

        val target = findTarget(ship)
        if (target == null) {
            log.error("引力时流干涉器激活瞬间无有效目标（ship=${ship.id}），isUsable 闸门被绕过？")
            return
        }

        val values = GravTimeflowTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val targetSystem = target.system
        if (targetSystem != null && targetSystem.cooldownRemaining > 0f) {
            targetSystem.cooldownRemaining = targetSystem.cooldownRemaining * (1f - values.cooldownReduction)
        }

        val link = GravTimeflowLinkVfx.attach(engine, ship, target)
        engine.customData[activationKey(ship)] = TimeflowActivation(target, link)
    }

    /**
     * IN/ACTIVE/OUT 每帧：目标与所属战机时流按 effectLevel 渐入/渐出，刷新三方 jitter 与
     * 时流弧线。目标消亡/离场：收弧线、停止对目标的时流写入与 jitter（系统窗口照常走完，
     * 不强制关闭系统）；持续期间不做射程检查（规格：目标超出射程不会强制关闭系统）。
     */
    private fun driveInterference(engine: CombatEngineAPI, ship: ShipAPI, effectLevel: Float) {
        val activation = engine.customData[activationKey(ship)] as? TimeflowActivation ?: return
        val target = activation.target
        if (!engine.isEntityInPlay(target) || !target.isAlive || target.isHulk) {
            activation.link?.dispose()
            return
        }

        val values = GravTimeflowTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val timeMult = 1f + (values.timeMult - 1f) * effectLevel.coerceIn(0f, 1f)

        target.mutableStats.timeMult.modifyMult(TIMEFLOW_MOD_ID, timeMult)
        for (wing in target.allWings) {
            for (fighter in wing.wingMembers) {
                if (fighter.isHulk) continue
                fighter.mutableStats.timeMult.modifyMult(TIMEFLOW_MOD_ID, timeMult)
                fighter.setJitter(
                    JITTER_KEY, FIGHTER_JITTER_COLOR, effectLevel, FIGHTER_JITTER_COPIES,
                    0f, 3f + fighter.collisionRadius * 0.3f,
                )
            }
        }

        ship.setJitter(JITTER_KEY, SELF_JITTER_COLOR, effectLevel * SELF_JITTER_LEVEL, SELF_JITTER_COPIES, 0f, 2f)
        target.setJitter(
            JITTER_KEY, TARGET_JITTER_COLOR, effectLevel, TARGET_JITTER_COPIES,
            0f, 5f + target.collisionRadius * 0.5f,
        )
        target.setJitterUnder(
            JITTER_KEY, TARGET_JITTER_UNDER_COLOR, effectLevel, TARGET_JITTER_UNDER_COPIES,
            0f, 4f + target.collisionRadius * 0.5f,
        )

        activation.link?.update(engine.elapsedInLastFrame, effectLevel)
    }

    /** 目标侧时流修饰配对解除（unapply 调用；对已消亡战机为无副作用的空 unmodify）。 */
    private fun removeTargetTimeflow(target: ShipAPI) {
        target.mutableStats.timeMult.unmodifyMult(TIMEFLOW_MOD_ID)
        for (wing in target.allWings) {
            for (fighter in wing.wingMembers) {
                if (fighter.isHulk) continue
                fighter.mutableStats.timeMult.unmodifyMult(TIMEFLOW_MOD_ID)
            }
        }
    }

    /**
     * 目标解析（熵放大器模式）：AI 旗标 [ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM] 优先，
     * 否则取 shipTarget（玩家锁定的友军舰船）；校验友军/非战机非无人机/存活非残骸与系统射程。
     */
    private fun findTarget(ship: ShipAPI): ShipAPI? {
        var target: ShipAPI? = null
        if (ship.shipAI != null && ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM) as? ShipAPI
        }
        if (target == null) target = ship.shipTarget
        if (!isValidTarget(ship, target)) return null

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravTimeflowTuning.BASE_RANGE)
        val dist = Misc.getDistance(ship.location, target!!.location)
        val radSum = ship.collisionRadius + target.collisionRadius
        return if (dist <= range + radSum) target else null
    }

    private fun isValidTarget(ship: ShipAPI, target: ShipAPI?): Boolean =
        target != null && target !== ship && target.owner == ship.owner &&
                !target.isFighter && !target.isDrone && !target.isHulk && target.isAlive

    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean =
        findTarget(ship) != null

    override fun getInfoText(system: ShipSystemAPI, ship: ShipAPI): String? {
        if (system.isOutOfAmmo) return null
        if (system.state != ShipSystemAPI.SystemState.IDLE) return null
        if (findTarget(ship) != null) return null
        // 有锁定的友军舰船但不可用（超射程）→ 超出射程；无锁定/锁定无效 → 无有效目标
        val raw = ship.shipTarget
        return if (raw != null && isValidTarget(ship, raw)) {
            I18n[I18n.Categories.MOD, "ui.grav_timeflow.info.out_of_range"]
        } else {
            I18n[I18n.Categories.MOD, "ui.grav_timeflow.info.no_target"]
        }
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        return when (state) {
            ShipSystemStatsScript.State.IN -> ShipSystemStatsScript.StatusData(
                I18n[I18n.Categories.MOD, "system.grav_timeflow.status.in"],
                false,
            )

            ShipSystemStatsScript.State.ACTIVE -> {
                // 状态行仅玩家舰渲染：从玩家舰的激活闩取目标名；缺席（异常路径）退化为无名行
                val engine = Global.getCombatEngine()
                val playerShip = engine?.playerShip
                val activation = if (playerShip != null) {
                    engine.customData[activationKey(playerShip)] as? TimeflowActivation
                } else {
                    null
                }
                val targetName = activation?.target?.name
                if (targetName != null) {
                    ShipSystemStatsScript.StatusData(
                        I18n.t(I18n.Categories.MOD, "system.grav_timeflow.status.active", "targetName" to targetName),
                        false,
                    )
                } else {
                    ShipSystemStatsScript.StatusData(
                        I18n[I18n.Categories.MOD, "system.grav_timeflow.status.active_unknown"],
                        false,
                    )
                }
            }

            ShipSystemStatsScript.State.OUT -> ShipSystemStatsScript.StatusData(
                I18n[I18n.Categories.MOD, "system.grav_timeflow.status.out"],
                false,
            )

            else -> null
        }
    }

    companion object {
        private val log = Global.getLogger(GravTimeflowInterferenceSystemStats::class.java)

        /** 目标与战机时流修饰句柄（目标/战机 mutableStats 上的稳定 id，apply/unapply 严格配对）。 */
        private const val TIMEFLOW_MOD_ID = "astd_grav_timeflow_interference_buff"

        /** 激活闩 customData 键前缀（每船一条，unapply 清除）；键用 ship.id（战斗内唯一）。 */
        private const val ACTIVATION_KEY = "astd_grav_timeflow_activation:"

        /** jitter 源句柄（自身/目标/战机共用同一 source，覆盖式刷新；结束停止刷新自然消退）。 */
        private val JITTER_KEY = Any()

        /** 自身 jitter（淡）：低强度、小副本数。 */
        private val SELF_JITTER_COLOR = Color(190, 140, 255, 55)
        private const val SELF_JITTER_LEVEL = 0.5f
        private const val SELF_JITTER_COPIES = 3

        /** 目标 jitter（明显，规格：目标舰船的 Jitter 效果更明显）。 */
        private val TARGET_JITTER_COLOR = Color(195, 140, 255, 90)
        private val TARGET_JITTER_UNDER_COLOR = Color(190, 130, 255, 140)
        private const val TARGET_JITTER_COPIES = 6
        private const val TARGET_JITTER_UNDER_COPIES = 15

        /** 目标所属战机 jitter（轻微）。 */
        private val FIGHTER_JITTER_COLOR = Color(200, 160, 255, 60)
        private const val FIGHTER_JITTER_COPIES = 2

        private fun activationKey(ship: ShipAPI): String = "$ACTIVATION_KEY${ship.id}"
    }
}
