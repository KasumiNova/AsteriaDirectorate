package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 执行摧锋鱼雷命中后的追加伤害、硬辐能推进、范围伤害与特效。
 *
 * 仅在战斗主线程调用。弹体基础伤害不是有限正数时记录 WARN 并跳过全部追加结算；
 * 本方法会直接修改目标与弹体状态。
 */
interface CuifengTorpedoStrike {

    /**
     * 执行一次命中结算，依次处理舰船追加伤害、护盾硬辐能、范围伤害与特效。
     * 存活的直接命中目标不再承受范围伤害，没有有效目标时仍生成特效。
     *
     * @param engine 当前战斗引擎
     * @param projectile 命中弹体，提供基础伤害、归属与来源舰
     * @param target 直接命中的实体
     * @param point 世界坐标中的命中点
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
