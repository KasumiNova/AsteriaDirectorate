package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.combat.AnnihilationVortexCollapse
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import cn.kasuminova.astd.impl.combat.FullExplosionFalloffImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

/**
 * [AnnihilationVortexCollapse] 实现（规格 04 §2.2）。
 *
 * 流程：委托统一结算入口 [ExplosionStrikeImpl.strike]——圆形粗筛 → 内置通用过滤
 * （同归属剔除，仅舰船/战机/导弹，hulk/相位/过期剔除）→ 逐目标落点解析 → `applyDamage`
 * （ENERGY，全额模式无距离衰减，现役站点一律 100%；末参 playSound=true 播放命中音效，
 * 伤害浮字随结算自动弹出、位置由落点决定）。
 * 无状态结算器：每开火周期由 BeamEffect 在停火首帧恰好调用一次。
 *
 * [coarseQuery] 可注入（默认 LazyLib 网格查询）：裸单测环境 LazyLib 不可达，
 * 测试注入候选清单提供者驱动同一结算路径（infra2 同款处置）。
 */
class AnnihilationVortexCollapseImpl(
    private val coarseQuery: (CombatEngineAPI, Vector2f, Float) -> List<CombatEntityAPI> = { _, center, radius ->
        CombatUtils.getEntitiesWithinRange(center, radius)
    },
) : AnnihilationVortexCollapse {

    /** 来源缺失 WARN 闸：每实例一次。 */
    private var warnedNullSource = false

    override fun resolve(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        damage: Float,
        source: ShipAPI?,
    ): Int {
        if (source == null) {
            if (!warnedNullSource) {
                warnedNullSource = true
                log.warn("[ASTD] 湮灭涡旋坍缩来源舰缺失，无法判定敌我，本次不结算（异常装配，不静默）")
            }
            return 0
        }
        if (damage <= 0f) {
            log.warn("[ASTD] 湮灭涡旋坍缩伤害非正（$damage），本次不结算（保底机制应保证 ≥${AnnihilationVortexDifficulty.POOL_FLOOR}×倍率，属程序错误）")
            return 0
        }

        return ExplosionStrikeImpl.strike(
            engine, center, radius, damage, DamageType.ENERGY,
            emp = 0f, source, source.owner,
            FullExplosionFalloffImpl,
            playSound = true,
            coarseQuery = { c, r -> coarseQuery(engine, c, r) },
        ).size
    }

    private companion object {
        private val log = Global.getLogger(AnnihilationVortexCollapseImpl::class.java)
    }
}
