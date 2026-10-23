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

class AnnihilationVortexCollapseImpl(
    private val coarseQuery: (CombatEngineAPI, Vector2f, Float) -> List<CombatEntityAPI> = { _, center, radius ->
        CombatUtils.getEntitiesWithinRange(center, radius)
    },
) : AnnihilationVortexCollapse {

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
