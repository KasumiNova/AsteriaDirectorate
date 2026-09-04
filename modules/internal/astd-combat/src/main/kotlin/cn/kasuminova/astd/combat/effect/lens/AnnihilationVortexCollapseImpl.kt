package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.combat.AnnihilationVortexCollapse
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

/**
 * [AnnihilationVortexCollapse] 实现（规格 04 §2.2）。
 *
 * 流程：`CombatUtils.getEntitiesWithinRange(center, radius)` 一次圆形粗筛 →
 * 敌方舰船/战机/导弹过滤（剔除 hulk 与归属相同方）→ 逐目标 `applyDamage`
 * （ENERGY，flat 无衰减，`showDamageFloaty = true` 自动弹伤害数字）。
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

        var hits = 0
        for (e in coarseQuery(engine, center, radius)) {
            if (e == null || e === source) continue
            // 目标类型：舰船（含战机）/导弹；其余实体（残骸碎片/弹体）不纳入。
            val isShip = e is ShipAPI
            val isMissile = e is MissileAPI
            if (!isShip && !isMissile) continue
            if (e.owner == source.owner) continue
            if (isShip && e.isHulk) continue

            val point = e.location ?: continue
            engine.applyDamage(e, point, damage, DamageType.ENERGY, 0f, false, false, source, true)
            hits++
        }
        return hits
    }

    private companion object {
        private val log = Global.getLogger(AnnihilationVortexCollapseImpl::class.java)
    }
}
