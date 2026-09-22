package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.GravityPhaseVisualEffect
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.plugins.ShipSystemStatsScript

/**
 * 密蒙级/茑萝级防御系统「引力相位」（astd_gravity_phase）的 stats 脚本。
 *
 * 相位机制与原版相位线圈（[PhaseCloakStats]）完全一致——继承即全部。
 * 本类额外承担两件事：
 * - 引力相位专属特效的接入点：每帧 apply 把舰船登记给
 *   [GravityPhaseVisualEffect]（整舰红色辉光 + 相位残影；相位等级由特效插件逐帧自查，
 *   不依赖本脚本的状态/等级传参）；
 * - HUD 状态行中文化：原版 [PhaseCloakStats.maintainStatus] 硬编码英文状态文本，
 *   这里按相同结构输出 I18n 文本。
 */
class GravityPhaseCloakStats : PhaseCloakStats() {

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        super.apply(stats, id, state, effectLevel)
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        GravityPhaseVisualEffect.track(engine, ship)
    }

    override fun maintainStatus(playerShip: ShipAPI, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val cloak = playerShip.phaseCloak ?: playerShip.system ?: return
        if (effectLevel <= PhaseCloakStats.VULNERABLE_FRACTION) return

        val engine = Global.getCombatEngine()
        val icon = cloak.specAPI.iconSpriteName
        engine.maintainStatusForPlayerShip(
            STATUSKEY2, icon, cloak.displayName,
            I18n[I18n.Categories.MOD, "system.gravity_phase.status.altered"], false,
        )
        if (PhaseCloakStats.FLUX_LEVEL_AFFECTS_SPEED) {
            if (getDisruptionLevel(playerShip) <= 0f) {
                engine.maintainStatusForPlayerShip(
                    STATUSKEY3, icon,
                    I18n[I18n.Categories.MOD, "system.gravity_phase.status.coils_stable"],
                    I18n[I18n.Categories.MOD, "system.gravity_phase.status.full_speed"], false,
                )
            } else {
                val speedPercent = Math.round(getSpeedMult(playerShip, effectLevel) * 100f)
                engine.maintainStatusForPlayerShip(
                    STATUSKEY3, icon,
                    I18n[I18n.Categories.MOD, "system.gravity_phase.status.coil_stress"],
                    I18n[I18n.Categories.MOD, "system.gravity_phase.status.speed_limited"]
                        .replace("%speed%", speedPercent.toString()),
                    true,
                )
            }
        }
    }
}
