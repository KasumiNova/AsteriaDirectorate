package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.plugins.ShipSystemStatsScript

/**
 * 密蒙级防御系统「引力相位」（astd_gravity_phase）的 stats 脚本。
 *
 * 当前行为与原版相位线圈（[PhaseCloakStats]）完全一致——继承即全部。
 * 本类是引力相位专属特效的接入点：后续相位特效（引力透镜视觉/音效/机制扩展）
 * 在 [apply] / [unapply] 的覆写中接入（先调 super 保留原版相位行为），
 * 舰船侧引用（ship_data.csv defense id → astd_gravity_phase → 本类）无需再改。
 */
class GravityPhaseCloakStats : PhaseCloakStats() {

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        super.apply(stats, id, state, effectLevel)
        // TODO(引力相位特效): 相位激活态特效在此接入（state/effectLevel 驱动）。
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        super.unapply(stats, id)
        // TODO(引力相位特效): 相位退出清理在此接入。
    }
}
