package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 范围爆炸伤害的统一结算入口。
 *
 * 动机：摧锋/辉星/七星/坠星残响/湮灭涡旋等效果各自抄了一份「爆炸落点解析 + 范围结算」，
 * 护盾未覆盖时硬编码落舰心，导致伤害浮字与装甲格选取落在舰船中心而非实际命中侧。
 * 本接口把「粗筛 → 通用过滤 → 逐目标落点解析 → applyDamage」收敛为唯一实现，
 * 各效果只声明差异（半径/伤害/EMP/归属/附加豁免判定）。
 *
 * 落点口径（全库统一，替代历史「界内边缘点恒 0 → 退回舰心」的旧判例）：
 * 盾开启且爆心在盾弧内 → 盾面点；否则把爆心压回舰体——爆心到舰心距离 ≤ 0.9×碰撞半径
 * 原样取爆心，否则沿「舰心 → 爆心」方向截断到 0.9×碰撞半径。压点既保证点在舰体内
 * （装甲正常结算），又让浮字落在命中侧。任何分支都不再返回舰心。
 *
 * applyDamage 参数口径：bypassShields = 受害目标是舰船且盾未覆盖爆心（尊重开启的护盾；
 * 盾关闭的带盾舰船 bypass=false 会全额无伤害）；dealsSoftFlux 恒 false；末参为 playSound
 * （播放命中音效），伤害浮字随结算自动弹出、位置由落点决定——不存在 showDamageFloaty 参数。
 *
 * 实现：[cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl]（object 无状态）。
 */
interface ExplosionStrike {

    /**
     * 执行一次范围爆炸结算，返回实际结算的受害目标清单（供调用方遥测计数）。
     *
     * 流程：LazyLib 空间网格粗筛（`CombatUtils.getEntitiesWithinRange`，表面进入半径语义）→
     * 内置通用过滤（与 [owner] 相同跳过、仅 ShipAPI/MissileAPI、hulk/相位舰船与过期导弹跳过）→
     * [victimFilter] 附加终判 → 逐目标解析落点（舰船走 [resolveDamagePoint]，导弹取爆心点）→
     * `applyDamage`（伤害量经 [falloff] 按表面距离折算）。
     *
     * @param engine 战斗引擎（结算唯一出口）
     * @param center 爆心（世界坐标）
     * @param radius 爆炸半径（su）；非正属配置错误，记 WARN 且本次不结算
     * @param damage 面板伤害（折算衰减前的基准值）；负值/NaN 记 WARN 并 clamp 到 0
     * @param damageType 伤害类型
     * @param emp 附带 EMP 量，0 表示无
     * @param source 伤害来源（归功/AI 仇恨/浮字归属），可空
     * @param owner 攻击方归属（敌我过滤基准）
     * @param falloff 衰减模式（全额/线性，见 [ExplosionFalloff]）
     * @param victimFilter 附加受害者终判（内置过滤通过后调用；供调用方塞直击目标豁免、
     *   遮挡判定等特异逻辑），默认全通过
     * @param playSound applyDamage 末参：是否播放命中音效
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
     * 统一伤害落点解析（单次直击/终结等不走 [strike] 的结算也必须经此取点）：
     * 盾覆盖爆心 → 盾面点（盾心沿爆心方向外推盾半径）；否则压回舰体命中侧压点
     * （0.9×碰撞半径截断）。护盾数据异常（盾心缺失/盾半径非正）记 WARN 并退回压点结果，
     * 不静默兜底、不返回舰心。
     */
    fun resolveDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f

    /**
     * 盾覆盖判定：盾开启且爆心在盾弧内。是 [strike] 内 bypassShields 口径的依据，
     * 也供不走 [strike] 的单次结算自行决定 bypassShields（覆盖 → false 尊重护盾；
     * 未覆盖 → true，否则盾关闭的带盾舰船全额无伤害）。
     */
    fun shieldCovers(ship: ShipAPI, explosionPoint: Vector2f): Boolean
}

/**
 * 范围爆炸的距离衰减模式。
 *
 * 动机：各爆炸效果对「半径内伤害如何随距离变化」的裁定不同（ASTD 现役站点一律全额，
 * 对齐原版 DamagingExplosion 的线性衰减留作后续平衡手段），衰减是纯策略，做成接口
 * 供结算入口按目标表面距离折算。
 */
fun interface ExplosionFalloff {

    /**
     * 按目标表面距离折算实际结算伤害。
     *
     * @param damage 面板伤害（衰减前基准）
     * @param surfaceDist 表面距离 = max(0, 目标舰心到爆心距离 − 目标碰撞半径)
     * @param radius 爆炸半径（su）
     */
    fun damageFor(damage: Float, surfaceDist: Float, radius: Float): Float
}
