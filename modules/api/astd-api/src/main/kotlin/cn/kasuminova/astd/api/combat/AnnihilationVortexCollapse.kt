package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 对湮灭涡旋停火时的径向能量伤害进行一次性结算。
 *
 * 每个开火周期仅在停火首帧调用一次，且仅在战斗主线程调用。结算会直接修改目标状态。
 */
interface AnnihilationVortexCollapse {

    /**
     * 对半径内敌方目标施加无距离衰减的能量伤害，返回命中目标数。
     *
     * @param engine 当前战斗引擎
     * @param center 世界坐标中的坍缩中心
     * @param radius 坍缩半径
     * @param damage 每个目标受到的伤害
     * @param source 伤害来源舰，可为 null
     */
    fun resolve(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        damage: Float,
        source: ShipAPI?,
    ): Int
}
