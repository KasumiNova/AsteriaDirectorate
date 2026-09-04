package cn.kasuminova.astd.combat.effect.lens.stellar

import com.fs.starfarer.api.Global
import org.lwjgl.util.vector.Vector2f

/**
 * 辉星命中结算与导弹 AI 的纯计算面（规格 08 §2.1）：战机增伤 / 武器 EMP / 爆炸伤害 /
 * 撞线阈值 / 领先瞄准点的全部数值推导，不触碰战斗引擎，供单测完整驱动。
 *
 * 0 值防线（规格 08 §2.4）：
 * - [lineCrossThreshold] 弹体 maxHitpoints ≤ 0 时返回 0（撞线者死恒不触发；WARN 由
 *   [StellarMrmStrikeImpl] 按引擎去重输出一次）；
 * - [leadPoint] 导弹 maxSpeed ≤ 0 时退回当前位置直瞄并一次性 WARN（进程级去重，数据异常可见）。
 */
object StellarMrmStrikeMath {
    private val log = Global.getLogger(StellarMrmStrikeMath::class.java)

    @Volatile
    private var warnedZeroMaxSpeed = false

    /** 撞线阈值（su 结构值）：弹体 maxHitpoints × h；maxHitpoints ≤ 0 → 0（机制失效，恒不触发）。 */
    fun lineCrossThreshold(projMaxHitpoints: Float, h: Float): Float =
        if (projMaxHitpoints <= 0f || projMaxHitpoints.isNaN()) 0f else projMaxHitpoints * h

    /** 撞线判定：严格小于（hp=600 / 阈值 600 → 不触发；hp=599 → 触发，规格 08 §2.4）。 */
    fun shouldCross(targetHitpoints: Float, threshold: Float): Boolean = targetHitpoints < threshold

    /** 战机增伤（能量）：面板 × 战机增伤倍率。 */
    fun fighterBonusDamage(panel: Float, fBonus: Float): Float = panel * fBonus

    /** 战机全部武器 EMP 量：面板 × EMP 倍率。 */
    fun weaponEmpDamage(panel: Float, wEmp: Float): Float = panel * wEmp

    /** 辉星爆炸单目标伤害（能量）：面板 × 爆炸倍率。 */
    fun explosionDamage(panel: Float, expMult: Float): Float = panel * expMult

    /**
     * 领先瞄准点：目标位置 + 目标速度 ×（距离 / 导弹最大航速）。
     * [missileMaxSpeed] ≤ 0 或 NaN（数据异常）时退回目标当前位置直瞄并一次性 WARN
     * （进程级去重；不静默产出 NaN 瞄准点）。
     */
    fun leadPoint(targetLoc: Vector2f, targetVel: Vector2f, dist: Float, missileMaxSpeed: Float): Vector2f {
        if (missileMaxSpeed <= 0f || missileMaxSpeed.isNaN()) {
            if (!warnedZeroMaxSpeed) {
                warnedZeroMaxSpeed = true
                log.warn("辉星导弹 maxSpeed 异常（$missileMaxSpeed），领先瞄准退回直瞄（后续同类异常不再重复告警）")
            }
            return Vector2f(targetLoc)
        }
        val t = dist / missileMaxSpeed
        return Vector2f(targetLoc.x + targetVel.x * t, targetLoc.y + targetVel.y * t)
    }
}
