package cn.kasuminova.astd.combat.effect.arc.piercinglance

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 贯星之矛的难度数值登记（规格 09 §2.3）：锥角/锥长/破片与 EMP 伤害倍率三锚点。
 *
 * 动机：三项常驻显性（含玩家版）——与摧锋的 k≥3 隐性解锁不同，直接 ScalingEntry 全段
 * 线性插值，无自定义映射；玩家（owner == 0）固定 v2 的取值入口统一收在本对象，
 * 结算层（[PiercingLanceConeStrike]）与单元测试直接驱动本对象，插件内不留重复逻辑。
 *
 * 缩放口径（90 计划全局约定）：敌方/友军 AI 按轨一 k_s 三锚点映射；
 * 玩家来源固定 v2（样板 ASTDVirtualParticleLatticeWebHullMod 既有口径）。
 */
object PiercingLanceDifficulty {

    /** 面板基准伤害（设计口径「面板 x%」，2500 能量；结算量 = 面板 × [CONE_DAMAGE] 倍率）。 */
    const val PANEL_DAMAGE = 2500f

    /** 锥角（度，面板全角）：迟暮 40 / 砺刃 50 / 破晓 80（设计案显式锚点）。 */
    val CONE_ARC = ScalingEntry(40f, 50f, 80f)

    /** 锥长（su）：迟暮 300 / 砺刃 375 / 破晓 600。 */
    val CONE_RANGE = ScalingEntry(300f, 375f, 600f)

    /** 破片/EMP 伤害倍率（面板倍数，EMP 与破片同锚，2026-07-28 裁定）：迟暮 100% / 砺刃 125% / 破晓 200%。 */
    val CONE_DAMAGE = ScalingEntry(1.00f, 1.25f, 2.00f)

    /**
     * 按来源取一项锚点值：玩家（owner == 0）固定 [ScalingEntry.v2]；
     * 敌方/友军 AI 与无主弹体（source == null）走 [DifficultyTuningImpl] 的 k_s 映射。
     */
    fun valueFor(source: ShipAPI?, entry: ScalingEntry): Float =
        if (source?.owner == 0) entry.v2 else DifficultyTuningImpl.value(entry)
}
