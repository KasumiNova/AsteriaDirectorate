package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 摧锋鱼雷命中机制的一次性结算总入口（blue/30-superlative.md 定案 v1.0）。
 *
 * 动机：`OnHitEffectPlugin` 保持薄入口（暂停检查 + 命中点回退），把结算顺序
 * （直击自适应增伤 → 硬辐推进 → 全额面板 AOE → 特效）收敛到一个可注入测试桩引擎驱动的
 * 接口后面，逐机制的伤害量与浮字可被单测断言。
 *
 * 实现：[cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoStrikeImpl]（object 无状态）。
 */
interface CuifengTorpedoStrike {

    /**
     * 执行一次命中结算。调用顺序与语义：
     * 0. 面板值 sanitize：非有限或 ≤0 记 WARN 并整体跳过（直击已由引擎原生结算）；
     * 1. 直击自适应（仅舰船目标）：辐能自适应（目标辐能 40%~90% 线性）+ 舰体自适应
     *    （舰体等级档位 + 同级部署点差值；模块舰取主舰体部署点且减半）合并为一笔能量伤害；
     * 2. 硬辐推进：命中护盾时强制抬升目标最大辐能 y% 的硬辐能，并给出紫色浮字；
     * 3. 全额面板 AOE：150su 范围内所有敌方目标吃全额面板能量伤害——存活的直击目标豁免
     *    （设计案「打中造成的伤害本身即为范围伤害」口径：直击面板已由引擎原生结算，不重复计）；
     * 4. 特效恒执行：十字辉星 ×2 + 爆炸星云 ×10（无有效受害目标时仅 VFX，合法）。
     *
     * @param engine 战斗引擎（结算与 VFX 的唯一出口）
     * @param projectile 命中弹体（面板伤害/归属/来源舰的读取面）
     * @param target 直接命中实体（可为残骸以外的任意实体）
     * @param point 命中点（世界坐标）
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
