package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 从候选实体中选择辉星导弹的追踪目标。
 *
 * 实现不得在 [select] 中修改实体状态。
 */
fun interface StellarMrmTargeting {

    /**
     * 返回最近的敌方战机；没有战机时返回最近的敌方舰船，没有合格候选时返回 null。
     *
     * @param candidates 候选实体，非舰船实体必须忽略
     * @param from 世界坐标中的弹体位置
     * @param owner 弹体归属方，同方实体必须忽略
     * @param acquireRange 最大捕获距离，单位为 su
     * @param inPlay 判断候选是否仍在战斗中
     */
    fun select(
        candidates: List<CombatEntityAPI>,
        from: Vector2f,
        owner: Int,
        acquireRange: Float,
        inPlay: (CombatEntityAPI) -> Boolean,
    ): ShipAPI?
}

/**
 * 执行辉星导弹命中后的导弹移除、战机追加伤害、EMP 与范围爆炸。
 *
 * 仅在战斗主线程调用。弹体基础伤害不是有限正数时记录 WARN 并跳过追加结算；
 * 本方法会直接修改目标与弹体状态。
 */
interface StellarMrmStrike {

    /**
     * 执行一次命中结算。战机机体命中时追加能量伤害与武器 EMP，任意命中均生成范围爆炸。
     *
     * @param engine 当前战斗引擎
     * @param projectile 命中弹体，提供基础伤害、结构上限、归属与来源舰
     * @param target 直接命中的实体
     * @param point 世界坐标中的命中点，用作爆炸中心
     * @param shieldHit 是否命中护盾
     */
    fun strike(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
    )
}
