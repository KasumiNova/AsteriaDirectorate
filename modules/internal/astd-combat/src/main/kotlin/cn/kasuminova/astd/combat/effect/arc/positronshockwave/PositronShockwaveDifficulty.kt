package cn.kasuminova.astd.combat.effect.arc.positronshockwave

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveDifficulty.DAMAGE_MULT
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveDifficulty.resolve
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 正电子冲击波的机制数值锚点与判定纯函数（规格 06 §2.2）。
 *
 * 动机：锥角/锥长/破片伤害/密度增伤四组锚点与「近炸目标类型」「满射程引爆」两条判定集中在一处——
 * 难度取值在发射时由 [PositronShockwaveOnFireEffect] 一次性 [resolve] 并随引信脚本持有
 * （同一发弹体生命周期内恒定，下一发重新取值），引信脚本与单元测试直接驱动本对象，
 * 插件内不留重复逻辑。
 *
 * 数值缩放口径（90 计划全局约定）：敌方/友军 AI 按轨一 k_s 三锚点映射；
 * 玩家来源（owner == 0）按我方档位系数映射（默认砺刃 2.0，等价早期固定 v2 口径）。
 */
object PositronShockwaveDifficulty {
    private val log = Global.getLogger(PositronShockwaveDifficulty::class.java)

    /** 面板基准破片伤害（design 定案，不缩放；结算量 = 面板 × [DAMAGE_MULT]）。 */
    const val PANEL_DAMAGE = 150f

    /** 锥角（度，面板全角）：迟暮 45 / 砺刃 56.25 / 破晓 90（设计案显式锚点）。 */
    val CONE_ANGLE = ScalingEntry(45f, 56.25f, 90f)

    /** 锥长 = 近炸距离（su，同一参数，裁定）：迟暮 200 / 砺刃 250 / 破晓 400。 */
    val CONE_RANGE = ScalingEntry(200f, 250f, 400f)

    /** 面板 150 破片的伤害倍率：迟暮 75% / 砺刃 100% / 破晓 175%。 */
    val DAMAGE_MULT = ScalingEntry(0.75f, 1f, 1.75f)

    /**
     * 密度增伤（2026-10 裁定）：锥状冲击范围内每有一个导弹/战机目标，
     * 本次结算对单个目标的伤害再提升的比例——迟暮 10% / 砺刃 20% / 破晓 50%。
     */
    val DENSITY_BONUS = ScalingEntry(0.1f, 0.2f, 0.5f)

    /**
     * 近炸引信触发距离占锥长比例（2026-09 用户裁定：敌对战机/导弹进入锥状射程约 40% 处才引爆，
     * 不再进入锥缘立刻引爆）；几何与结算锥同源，仅引信触发圈收窄。
     */
    const val FUSE_RANGE_RATIO = 0.4f

    /** 无主弹体 WARN 的 once 守卫（罕见路径，不刷屏）。 */
    @Volatile
    private var nullSourceWarned = false

    /**
     * 发射时一次性结算的难度取值（同一发弹体生命周期内恒定）。
     *
     * @property halfAngleDeg 锥半角（度）= 面板全角 / 2。
     * @property range 锥长 = 近炸距离（su）。
     * @property damage 结算伤害 = 面板 × 倍率。
     * @property densityBonus 密度增伤比例：锥内每有一个导弹/战机目标，结算伤害再提升该比例。
     */
    data class Resolved(val halfAngleDeg: Float, val range: Float, val damage: Float, val densityBonus: Float)

    /**
     * 按来源结算三锚点：玩家（owner == 0）按我方档位系数映射；敌方/友军 AI 走 [DifficultyTuningImpl] 的 k_s 映射；
     * 无主弹体（source == null，罕见）按敌方口径取值并 WARN 一次。
     */
    fun resolve(source: ShipAPI?): Resolved {
        if (source == null && !nullSourceWarned) {
            nullSourceWarned = true
            log.warn("正电子冲击波弹体无来源舰船，难度取值按敌方口径（k_s 映射）结算")
        }
        val isPlayer = source?.owner == 0
        return Resolved(
            DifficultyTuningImpl.valueFor(CONE_ANGLE, isPlayer) / 2f,
            DifficultyTuningImpl.valueFor(CONE_RANGE, isPlayer),
            PANEL_DAMAGE * DifficultyTuningImpl.valueFor(DAMAGE_MULT, isPlayer),
            DifficultyTuningImpl.valueFor(DENSITY_BONUS, isPlayer),
        )
    }

    /**
     * 满射程引爆判定（纯函数）：弹体已飞距离 = [elapsed] × [speed]，达 [range] 即引爆（边界含等号）。
     *
     * 0 值防线（规格 §2.4）：[speed] <= 0 属配置错误，记 ERROR 并立即按当前位置引爆——
     * 宁可原地自爆也不允许「静默消散」违背裁定。
     */
    fun reachedMaxRange(elapsed: Float, speed: Float, range: Float): Boolean {
        if (speed <= 0f || speed.isNaN()) {
            log.error("正电子冲击波弹体 moveSpeed 非法（$speed），属配置错误，立即按当前位置引爆（不允许静默消散）")
            return true
        }
        return elapsed * speed >= range
    }

    /**
     * 近炸引信目标判定（纯函数，规格裁定矩阵）：
     * 严格只导弹/战机/无人机触发近炸——舰船由碰撞 + OnHit 即时引爆承接（不再穿模通过，
     * 2026-09 用户裁定）；剔除同方（owner 相同）与 hulk。
     */
    fun isFuseTarget(entity: CombatEntityAPI, owner: Int): Boolean = when (entity) {
        is MissileAPI -> entity.owner != owner
        is ShipAPI -> (entity.isFighter || entity.isDrone) && !entity.isHulk && entity.owner != owner
        else -> false
    }
}
