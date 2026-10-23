package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.api.combat.CuifengTorpedoStrike
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import cn.kasuminova.astd.impl.combat.FullExplosionFalloffImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

object CuifengTorpedoStrikeImpl : CuifengTorpedoStrike {
    private val log = Global.getLogger(CuifengTorpedoStrikeImpl::class.java)

    internal val LAZYLIB_COARSE_QUERY: (Vector2f, Float) -> List<CombatEntityAPI> =
        { origin, range -> CombatUtils.getEntitiesWithinRange(origin, range) }

    private val HARD_FLUX_TEXT_COLOR = Color(200, 130, 255)

    override fun strike(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
    ) = strike(engine, projectile, target, point, shieldHit, LAZYLIB_COARSE_QUERY)

    internal fun strike(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        coarseQuery: (Vector2f, Float) -> List<CombatEntityAPI>,
    ) {
        val panel = projectile.damageAmount
        if (!panel.isFinite() || panel <= 0f) {
            log.warn("摧锋命中结算面板值异常（$panel），属配置错误，附加机制全部跳过: spec=${projectile.projectileSpecId}")
            return
        }
        val source = projectile.source
        val owner = projectile.owner
        val x = CuifengTorpedoDifficulty.resolve(CuifengTorpedoDifficulty.FLUX_ADAPTIVE_X, owner)
        val y = CuifengTorpedoDifficulty.resolve(CuifengTorpedoDifficulty.HARD_FLUX_Y, owner)
        val dpPct = CuifengTorpedoDifficulty.resolve(CuifengTorpedoDifficulty.DP_PCT, owner)

        if (target is ShipAPI && !target.isHulk) {
            val fluxTracker = target.fluxTracker
            val maxFlux = fluxTracker.maxFlux
            val fluxLevel = if (maxFlux > 0f) fluxTracker.currFlux / maxFlux else 0f
            val fluxBonus = CuifengTorpedoMath.fluxAdaptiveBonus(panel, x, fluxLevel)

            var hullBonus = 0f
            if (!target.isFighter) {
                val moduleParent = target.parentStation
                val dpSource = moduleParent ?: target
                val hullSize = dpSource.hullSize
                val sizePct = resolveHullSizePct(hullSize, owner)
                val baseline = resolveDpBaseline(hullSize)
                val dp = dpSource.fleetMember?.unmodifiedDeploymentPointsCost ?: baseline
                hullBonus = CuifengTorpedoMath.hullAdaptiveBonus(panel, sizePct, dp, baseline, dpPct)
                if (moduleParent != null) hullBonus *= MODULE_BONUS_MULT
            }

            val totalBonus = fluxBonus + hullBonus
            if (totalBonus > 0f) {
                val covered = ExplosionStrikeImpl.shieldCovers(target, point)
                engine.applyDamage(
                    target, ExplosionStrikeImpl.resolveDamagePoint(target, point), totalBonus,
                    DamageType.ENERGY, 0f, !covered, false, source, true,
                )
                bump(engine, TELE_ADAPTIVE_HITS)
                engine.customData[TELE_LAST_ADAPTIVE_BONUS + ownerSuffix(owner)] = totalBonus
            }
        }

        if (shieldHit && target is ShipAPI && !target.isHulk) {
            val maxFlux = target.fluxTracker.maxFlux
            if (maxFlux > 0f) {
                val amount = maxFlux * y
                target.fluxTracker.increaseFlux(amount, true)
                engine.addFloatingDamageText(Vector2f(point), amount, HARD_FLUX_TEXT_COLOR, target, source)
                bump(engine, TELE_HARD_FLUX_PUSHES)
                engine.customData[TELE_LAST_HARD_FLUX + ownerSuffix(owner)] = amount
            }
        }

        // 直击面板已由引擎结算，AOE 豁免存活的直击目标以避免重复结算。
        val aoeVictims = ExplosionStrikeImpl.strike(
            engine, point, CuifengTorpedoDifficulty.AOE_RADIUS, panel, DamageType.ENERGY,
            emp = 0f, source, owner,
            FullExplosionFalloffImpl,
            victimFilter = { victim ->
                victim !== projectile && !(victim === target && engine.isEntityInPlay(victim))
            },
            playSound = true,
            coarseQuery = coarseQuery,
        )
        for (victim in aoeVictims) {
            bump(engine, TELE_AOE_HITS)
            if (victim is ShipAPI && !victim.isFighter) bump(engine, TELE_AOE_SHIP_HITS)
            engine.customData[TELE_LAST_AOE_VICTIM + ownerSuffix(owner)] =
                "${victim.javaClass.simpleName}@${(victim as? ShipAPI)?.hullSpec?.hullId ?: (victim as? MissileAPI)?.projectileSpecId}"
        }

        CuifengTorpedoVfx.spawnImpact(engine, point, projectile.facing)
        bump(engine, TELE_IMPACT_VFX)
    }

    private fun resolveHullSizePct(hullSize: ShipAPI.HullSize?, owner: Int): Float = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> CuifengTorpedoDifficulty.HULL_FRIGATE
        ShipAPI.HullSize.DESTROYER -> CuifengTorpedoDifficulty.HULL_DESTROYER
        ShipAPI.HullSize.CRUISER -> CuifengTorpedoDifficulty.HULL_CRUISER
        ShipAPI.HullSize.CAPITAL_SHIP -> CuifengTorpedoDifficulty.HULL_CAPITAL
        else -> null
    }?.let { CuifengTorpedoDifficulty.resolve(it, owner) } ?: 0f

    private fun resolveDpBaseline(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> CuifengTorpedoDifficulty.DP_BASELINE_FRIGATE
        ShipAPI.HullSize.DESTROYER -> CuifengTorpedoDifficulty.DP_BASELINE_DESTROYER
        ShipAPI.HullSize.CRUISER -> CuifengTorpedoDifficulty.DP_BASELINE_CRUISER
        ShipAPI.HullSize.CAPITAL_SHIP -> CuifengTorpedoDifficulty.DP_BASELINE_CAPITAL
        else -> 0f
    }

    const val TELE_ADAPTIVE_HITS = "astd_cuifeng_tele_adaptive_hits"

    const val TELE_HARD_FLUX_PUSHES = "astd_cuifeng_tele_hard_flux_pushes"

    const val TELE_AOE_HITS = "astd_cuifeng_tele_aoe_hits"

    const val TELE_AOE_SHIP_HITS = "astd_cuifeng_tele_aoe_ship_hits"

    const val TELE_IMPACT_VFX = "astd_cuifeng_tele_impact_vfx"

    const val TELE_LAST_ADAPTIVE_BONUS = "astd_cuifeng_tele_last_adaptive_bonus"

    const val TELE_LAST_HARD_FLUX = "astd_cuifeng_tele_last_hard_flux"

    const val TELE_LAST_AOE_VICTIM = "astd_cuifeng_tele_last_aoe_victim"

    const val TELE_OWNER_PLAYER = "_p"

    const val TELE_OWNER_ENEMY = "_e"

    private const val HARD_FLUX_TEXT_SIZE = 24f

    private const val MODULE_BONUS_MULT = 0.5f

    private fun ownerSuffix(owner: Int): String = if (owner == 0) TELE_OWNER_PLAYER else TELE_OWNER_ENEMY

    private fun bump(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }
}
