package cn.kasuminova.astd.combat.hullmods.lens

/**
 * 视差甲板（Parallax Decks，spec §4 / `purple/10-unique.md` §1 插件②）的纯判定与数值常量。
 *
 * 动机：将「出库相位窗口判定 / 整备减伤范围判定 / 机群叠标记每敌每秒上限判定」三段可单测的
 * 纯逻辑从 [ASTDLensParallaxDecksHullMod] 的运行时遍历中剥离，使数值边界可被
 * [cn.kasuminova.astd.combat.hullmods.lens.ParallaxDecksMath] 单测覆盖（advanceInCombat 集成层不单测）。
 *
 * 数值锚点取自 spec §4.2（v0，待平衡）：
 * - 出库相位隐形 ~2s + 时流 +100%。
 * - 机群 on-hit 叠 1 层误差标记，每敌每秒上限 1~2 层（防刷）——本实现取保守端 1 层/秒。
 * - 整备（返航）意图且本舰 1000su 内 → 50% 承伤减免。
 */
object ParallaxDecksMath {

    /** 出库相位隐形 + 时流加成的持续窗口（s，spec §4.2「出库相位隐形 ~2s」）。 */
    const val LAUNCH_PHASE_WINDOW: Float = 2f

    /** 出库窗口内的时流倍率（+100% 即 ×2，spec §4.2「出库时流加成 +100%」）。 */
    const val TIME_MULT_BONUS: Float = 2.0f

    /** 整备承伤减免生效半径（su，spec §4.2「整备生效范围 本舰 1000su」）。 */
    const val RECOVERY_RANGE: Float = 1000f

    /** 整备承伤减免后的承伤倍率（50% 减免即 ×0.5，spec §4.2「整备承伤减免 50%」）。 */
    const val RECOVERY_DAMAGE_TAKEN_MULT: Float = 0.5f

    /**
     * 机群叠误差标记的每敌最小间隔（s）。spec §4.2 给出「每敌舰每秒 1~2 层」的区间，
     * 取保守端 1 层/秒 → minInterval = 1/1 = 1s（防刷，冷却单位为敌舰）。
     */
    const val MARK_MIN_INTERVAL: Float = 1f

    /**
     * 出库相位窗口判定：自该 fighter 出库以来经过 [timeSinceDeployed] 秒，是否仍在 [windowSec] 隐形窗口内。
     *
     * 左闭右开（`>=0 && < windowSec`）：刚出库（0）即在窗口内；恰好到窗口时长视为关闭，
     * 与 unapply 时刻对齐，避免相位/时流修饰在边界帧反复 apply→unapply 抖动。负值（异常时间戳）一律视为不在窗口。
     */
    fun isInLaunchPhaseWindow(timeSinceDeployed: Float, windowSec: Float): Boolean =
        timeSinceDeployed >= 0f && timeSinceDeployed < windowSec

    /**
     * 整备承伤减免范围判定：fighter 与本舰距离 [distToCarrier] 是否在 [rangeSu] 内（含边界）。
     *
     * 含边界（`<=`）：1000su 是「生效范围」上限，恰在边界的机群应受保护。
     */
    fun isInRecoveryRange(distToCarrier: Float, rangeSu: Float): Boolean =
        distToCarrier <= rangeSu

    /**
     * 机群叠误差标记的每敌每秒上限判定：对某敌舰上次叠标记于 [lastAppliedTime]（战斗经过秒数），
     * 当前为 [now]，是否已过 [minIntervalSec] 冷却。
     *
     * [lastAppliedTime] < 0 作为「从未叠过」的 sentinel，恒可叠加。其余按 `now - last >= minInterval` 判定。
     */
    fun canApplyMarkNow(lastAppliedTime: Float, now: Float, minIntervalSec: Float): Boolean {
        if (lastAppliedTime < 0f) return true
        return now - lastAppliedTime >= minIntervalSec
    }
}
