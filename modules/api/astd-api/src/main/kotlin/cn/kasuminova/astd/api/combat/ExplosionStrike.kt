package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 对范围爆炸进行目标筛选、伤害点解析与伤害结算。
 *
 * 所有方法仅在战斗主线程调用。[strike] 会直接修改目标状态并返回实际受到伤害的实体。
 * 舰船伤害点优先取护盾覆盖点；护盾未覆盖时取爆心，或将爆心沿舰心方向截断到 0.9 倍碰撞半径处。
 */
interface ExplosionStrike {

    /**
     * 执行一次范围爆炸并返回实际受到伤害的实体。
     *
     * 同方实体、残骸、相位舰船和过期导弹不参与结算。[victimFilter] 在内置筛选后调用。
     *
     * @param engine 当前战斗引擎
     * @param center 世界坐标中的爆心
     * @param radius 爆炸半径，单位为 su；非正数时记录 WARN 且不结算
     * @param damage 衰减前的伤害；负数或 NaN 按 0 处理并记录 WARN
     * @param damageType 伤害类型
     * @param emp 附加 EMP 伤害，0 表示无
     * @param source 伤害来源，可为 null
     * @param owner 攻击方归属
     * @param falloff 按目标表面距离计算伤害的策略
     * @param victimFilter 附加目标筛选，默认接受全部内置筛选通过的目标
     * @param playSound 是否播放命中音效
     */
    fun strike(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        damage: Float,
        damageType: DamageType,
        emp: Float = 0f,
        source: ShipAPI? = null,
        owner: Int,
        falloff: ExplosionFalloff,
        victimFilter: (CombatEntityAPI) -> Boolean = { true },
        playSound: Boolean = true,
    ): List<CombatEntityAPI>

    /**
     * 返回伤害结算位置。护盾覆盖时返回盾面点，否则返回爆心或舰体内的截断点。
     * 护盾中心缺失或半径非正时记录 WARN，并使用舰体内的截断点。
     * 不走 [strike] 的单次直击与终结结算也必须先经本方法取点，禁止直接使用爆心。
     */
    fun resolveDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f

    /**
     * 返回护盾是否开启且覆盖 [explosionPoint]。
     * 返回值决定 applyDamage 的 bypassShields 参数：返回 false 时必须传 true，否则护盾关闭的带盾舰船会无伤害。
     */
    fun shieldCovers(ship: ShipAPI, explosionPoint: Vector2f): Boolean
}

/** 按目标表面距离计算爆炸伤害。 */
fun interface ExplosionFalloff {

    /**
     * 返回实际伤害。
     *
     * @param damage 衰减前的伤害
     * @param surfaceDist 目标表面到爆心的距离
     * @param radius 爆炸半径，单位为 su
     */
    fun damageFor(damage: Float, surfaceDist: Float, radius: Float): Float
}
