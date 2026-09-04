package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 辉星导弹的猎杀目标筛选策略（规格 08 §2.1）。
 *
 * 动机：把「战机优先、排除导弹」的目标权重规则从导弹 AI 的转向循环中剥离，
 * 使筛选矩阵（归属/存活/hulk/射程门/类型优先级）可脱离战斗引擎被单测完整驱动；
 * AI 每 0.25s 节流调用一次，不承担任何权重细节。
 *
 * 实现：[cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmTargetingImpl]（object 无状态）。
 */
fun interface StellarMrmTargeting {

    /**
     * 从候选实体清单中选出本帧追踪目标。
     *
     * @param candidates 候选实体（通常来自 `engine.getShips()`；允许混入 MissileAPI 等
     *   非舰船实体以验证「导弹永不入选」——实现必须自行做类型过滤）
     * @param from 弹体当前位置（su，世界坐标），距离与射程门的基准点
     * @param owner 弹体归属方（0=玩家），同方实体一律排除
     * @param acquireRange 捕获射程（su）：超出此距离的候选不入选
     * @param inPlay 在场判定（引擎 `isEntityInPlay` 的注入面，测试可桩）
     * @return 选中目标：距离最近的敌方战机优先；无战机时最近的敌方舰船（无人机同普通舰船
     *   纳入兜底）；无任何合格候选返回 null；永不返回 MissileAPI
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
 * 辉星命中三大机制的一次性结算总入口（规格 08 §2.1）。
 *
 * 动机：`OnHitEffectPlugin` 保持薄入口（暂停检查 + 命中点回退），把结算顺序
 * （撞线者死 → 猎机本能 → 辉星爆炸，爆炸恒执行）收敛到一个可注入测试桩引擎驱动的
 * 接口后面，爆炸 AOE 逐目标结算与逐武器电弧循环可被单测断言。
 *
 * 实现：[cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmStrikeImpl]（object 无状态）。
 */
interface StellarMrmStrike {

    /**
     * 执行一次命中结算。调用顺序与语义（规格 08 §2.2）：
     * 0. 面板值 sanitize：非有限或 ≤0 记 WARN 并整体跳过（直击已由引擎原生结算）；
     * 1. 撞线者死：目标为敌方导弹且结构值 < 弹体 maxHitpoints × h 时 `removeEntity`；
     * 2. 猎机本能：目标为战机机体命中（非护盾）时追加能量伤害 + 一次全部武器 EMP
     *    （EMP 伤害走 `applyDamage` emp 参数，视觉走逐武器 `spawnEmpArcVisual`）；
     * 3. 辉星爆炸：任意撞击恒触发——50su 范围能量 AOE + 裂隙爆炸 VFX（RiftExplosionVfx，半径 ×0.6）。
     *
     * @param engine 战斗引擎（结算与 VFX 的唯一出口）
     * @param projectile 命中弹体（面板伤害 / maxHitpoints / source / owner 来源）
     * @param target 被命中实体（舰船/战机/导弹/残骸均可）
     * @param point 命中点（su，世界坐标；AOE 圆心与 VFX 落点）
     * @param shieldHit 是否护盾命中（护盾命中不触发猎机本能，爆炸照常）
     */
    fun strike(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
    )
}
