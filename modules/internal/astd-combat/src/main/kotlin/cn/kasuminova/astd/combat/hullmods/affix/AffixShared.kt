package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 HullMod 共用辅助：
 * - 轨一数值读取面（机制数值统一走 [DifficultyTuningImpl]，禁止读取舰队 memory 中的 k_p）；
 * - 相位舰判定、舰船大小分档（护卫舰/驱逐舰/巡洋舰/主力舰）、友军舰船枚举。
 */
internal object AffixShared {

    /** 轨一固有缩放系数读取面（单例实现）。 */
    val tuning: DifficultyTuning get() = DifficultyTuningImpl

    /** 相位舰船判定（相位限定词缀的效果层生效条件）。 */
    fun isPhaseShip(stats: com.fs.starfarer.api.combat.MutableShipStatsAPI): Boolean =
        stats.variant?.hullSpec?.isPhase == true

    /**
     * 按舰船大小选择分档 entry。
     * 分档顺序统一为：护卫舰 / 驱逐舰 / 巡洋舰 / 主力舰；非战斗舰艇档位（战机等）按护卫舰处理。
     */
    fun bySize(
        hullSize: ShipAPI.HullSize?,
        frigate: ScalingEntry,
        destroyer: ScalingEntry,
        cruiser: ScalingEntry,
        capital: ScalingEntry,
    ): ScalingEntry = when (hullSize) {
        ShipAPI.HullSize.CAPITAL_SHIP -> capital
        ShipAPI.HullSize.CRUISER -> cruiser
        ShipAPI.HullSize.DESTROYER -> destroyer
        else -> frigate
    }

    /**
     * 枚举战场上存活的友军舰船（不含自身、舰载机与无人机），供编队光环类词缀逐帧计数。
     */
    fun aliveAllies(ship: ShipAPI): List<ShipAPI> {
        val engine = Global.getCombatEngine() ?: return emptyList()
        return engine.ships.filter {
            it !== ship && it.owner == ship.owner && it.isAlive && !it.isHulk && !it.isFighter && !it.isDrone
        }
    }
}
