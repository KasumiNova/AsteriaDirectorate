package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 湮灭涡旋坍缩一次性结算（规格 04 §2.1）。
 *
 * 动机：光束停火帧，把吞噬池转化为一次径向 AOE 能量伤害——对坍缩半径内敌方目标
 * `applyDamage`（ENERGY，flat 无衰减）；无状态、每开火周期恰好调用一次（BeamEffect 停火首帧）。
 * 与 ConeImpactHandler 的分工：坍缩为径向 AOE（非锥状），不复用锥面组件（规格 04 §5）。
 */
interface AnnihilationVortexCollapse {

    /**
     * 结算一次坍缩爆炸。
     *
     * @param engine 战斗引擎
     * @param center 坍缩中心（停火前最后记录的光束终点）
     * @param radius 坍缩半径（涡旋半径 × 150%，由调用方算好传入）
     * @param damage 坍缩伤害（`max(池值, 保底) × AOE 倍率`，由调用方算好传入；flat 无衰减逐目标施加）
     * @param source 伤害来源舰（归功/AI 仇恨；可为 null）
     * @return 命中目标数（遥测与目检证据用）
     */
    fun resolve(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        damage: Float,
        source: ShipAPI?,
    ): Int
}
