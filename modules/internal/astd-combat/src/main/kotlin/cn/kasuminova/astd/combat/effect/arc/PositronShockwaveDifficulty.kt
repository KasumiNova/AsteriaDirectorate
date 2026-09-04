package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 正电子冲击波的机制数值锚点与判定纯函数（规格 06 §2.2）。
 *
 * 动机：锥角/锥长/破片伤害三锚点与「近炸目标类型」「满射程引爆」两条判定集中在一处——
 * 难度取值在发射时由 [PositronShockwaveOnFireEffect] 一次性 [resolve] 并随引信脚本持有
 * （同一发弹体生命周期内恒定，下一发重新取值），引信脚本与单元测试直接驱动本对象，
 * 插件内不留重复逻辑。
 *
 * 数值缩放口径（90 计划全局约定）：敌方/友军 AI 按轨一 k_s 三锚点映射；
 * 玩家来源（owner == 0）固定 v2（对照 ASTDVirtualParticleLatticeWebHullMod 既有口径）。
 */
object PositronShockwaveDifficulty {
    private val log = Global.getLogger(PositronShockwaveDifficulty::class.java)

    /** 面板基准破片伤害（design 定案，不缩放；结算量 = 面板 × [DAMAGE_MULT]）。 */
    const val PANEL_DAMAGE = 200f

    /** 锥角（度，面板全角）：迟暮 45 / 砺刃 56.25 / 破晓 90（设计案显式锚点）。 */
    val CONE_ANGLE = ScalingEntry(45f, 56.25f, 90f)

    /** 锥长 = 近炸距离（su，同一参数，裁定）：迟暮 200 / 砺刃 250 / 破晓 400。 */
    val CONE_RANGE = ScalingEntry(200f, 250f, 400f)

    /** 面板 200 破片的伤害倍率：迟暮 100% / 砺刃 125% / 破晓 200%。 */
    val DAMAGE_MULT = ScalingEntry(1f, 1.25f, 2f)

    /** 无主弹体 WARN 的 once 守卫（罕见路径，不刷屏）。 */
    @Volatile
    private var nullSourceWarned = false

    /**
     * 发射时一次性结算的三项难度取值（同一发弹体生命周期内恒定）。
     *
     * @property halfAngleDeg 锥半角（度）= 面板全角 / 2。
     * @property range 锥长 = 近炸距离（su）。
     * @property damage 结算伤害 = 面板 × 倍率。
     */
    data class Resolved(val halfAngleDeg: Float, val range: Float, val damage: Float)

    /**
     * 按来源结算三锚点：玩家（owner == 0）固定 v2；敌方/友军 AI 走 [DifficultyTuningImpl] 的 k_s 映射；
     * 无主弹体（source == null，罕见）按敌方口径取值并 WARN 一次。
     */
    fun resolve(source: ShipAPI?): Resolved {
        if (source == null && !nullSourceWarned) {
            nullSourceWarned = true
            log.warn("正电子冲击波弹体无来源舰船，难度取值按敌方口径（k_s 映射）结算")
        }
        fun pick(e: ScalingEntry): Float = if (source?.owner == 0) e.v2 else DifficultyTuningImpl.value(e)
        return Resolved(pick(CONE_ANGLE) / 2f, pick(CONE_RANGE), PANEL_DAMAGE * pick(DAMAGE_MULT))
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
     * 严格只导弹/战机/无人机触发近炸——舰船不触发（只可能在满射程自爆时被锥面波及）；
     * 剔除同方（owner 相同）与 hulk。
     */
    fun isFuseTarget(entity: CombatEntityAPI, owner: Int): Boolean = when (entity) {
        is MissileAPI -> entity.owner != owner
        is ShipAPI -> (entity.isFighter || entity.isDrone) && !entity.isHulk && entity.owner != owner
        else -> false
    }
}
