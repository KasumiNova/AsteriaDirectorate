package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.api.combat.StellarMrmStrike
import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmStrikeImpl.LAZYLIB_COARSE_QUERY
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import cn.kasuminova.astd.impl.combat.FullExplosionFalloffImpl
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx
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

object StellarMrmStrikeImpl : StellarMrmStrike {
    private val log = Global.getLogger(StellarMrmStrikeImpl::class.java)

    private const val WARN_KEY_ZERO_MAX_HP = "astd_stellar_mrm_warn_zero_maxhp"

    private const val EMP_ARC_THICKNESS = 6f

    private const val RIFT_RADIUS_SCALE = 0.6f

    private val EMP_ARC_FRINGE = Color(170, 110, 255)

    private val EMP_ARC_CORE = Color(255, 255, 255)

    internal val LAZYLIB_COARSE_QUERY: (Vector2f, Float) -> List<CombatEntityAPI> =
        { origin, range -> CombatUtils.getEntitiesWithinRange(origin, range) }

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
            log.warn("辉星命中结算面板值异常（$panel），属配置错误，附加机制全部跳过: spec=${projectile.projectileSpecId}")
            return
        }
        val source = projectile.source
        val owner = projectile.owner
        val fBonus = StellarMrmDifficulty.resolve(StellarMrmDifficulty.FIGHTER_BONUS, owner)
        val wEmp = StellarMrmDifficulty.resolve(StellarMrmDifficulty.WEAPON_EMP, owner)
        val expMult = StellarMrmDifficulty.resolve(StellarMrmDifficulty.EXPLOSION_MULT, owner)
        val h = StellarMrmDifficulty.resolve(StellarMrmDifficulty.LINE_CROSS_H, owner)

        val ownerSuffix = if (owner == 0) TELE_OWNER_PLAYER else TELE_OWNER_ENEMY
        engine.customData[TELE_LAST_F_BONUS + ownerSuffix] = fBonus
        engine.customData[TELE_LAST_W_EMP + ownerSuffix] = wEmp
        engine.customData[TELE_LAST_EXP_MULT + ownerSuffix] = expMult
        if (shieldHit) bump(engine, TELE_SHIELD_HITS)

        if (target is MissileAPI && target.owner != owner && engine.isEntityInPlay(target)) {
            val projMaxHp = projectile.maxHitpoints
            if (projMaxHp <= 0f && engine.customData[WARN_KEY_ZERO_MAX_HP] != true) {
                engine.customData[WARN_KEY_ZERO_MAX_HP] = true
                log.warn("辉星弹体 maxHitpoints 异常（$projMaxHp），撞线者死机制失效（阈值恒 0）: spec=${projectile.projectileSpecId}")
            }
            val threshold = StellarMrmStrikeMath.lineCrossThreshold(projMaxHp, h)
            if (StellarMrmStrikeMath.shouldCross(target.hitpoints, threshold)) {
                engine.removeEntity(target)
                bump(engine, TELE_LINE_CROSS)
            }
        }

        if (target is ShipAPI && target.isFighter && !shieldHit && !target.isHulk) {
            val covered = ExplosionStrikeImpl.shieldCovers(target, point)
            engine.applyDamage(
                target, ExplosionStrikeImpl.resolveDamagePoint(target, point),
                StellarMrmStrikeMath.fighterBonusDamage(panel, fBonus),
                DamageType.ENERGY, 0f, !covered, false, source, true,
            )
            bump(engine, TELE_BONUS_HITS)
            // 原版 EMP 通路（emp-only applyDamage / spawnEmpArc）均无法送达战机武器组件，
            // 只能逐武器扣 currHealth 结算，熄火与自修由原版组件机制完成。
            val empDamage = StellarMrmStrikeMath.weaponEmpDamage(panel, wEmp)
            for (weapon in target.allWeapons) {
                if (weapon.isDisabled) continue
                weapon.currHealth = maxOf(0f, weapon.currHealth - empDamage)
            }
            bump(engine, TELE_EMP_HITS)
            for (weapon in target.allWeapons) {
                if (weapon.isDisabled) continue
                val weaponLoc = weapon.location ?: continue
                engine.spawnEmpArcVisual(
                    point, target, weaponLoc, target,
                    EMP_ARC_THICKNESS, EMP_ARC_FRINGE, EMP_ARC_CORE,
                )
                bump(engine, TELE_EMP_ARCS)
            }
        }

        val expDamage = StellarMrmStrikeMath.explosionDamage(panel, expMult)
        val aoeVictims = ExplosionStrikeImpl.strike(
            engine, point, StellarMrmDifficulty.EXPLOSION_RADIUS, expDamage, DamageType.ENERGY,
            emp = 0f, source, owner,
            FullExplosionFalloffImpl,
            victimFilter = { victim ->
                victim !== projectile && !(victim === target && !engine.isEntityInPlay(victim))
            },
            playSound = true,
            coarseQuery = coarseQuery,
        )
        for (victim in aoeVictims) {
            bump(engine, TELE_AOE_HITS)
            if (victim is ShipAPI && !victim.isFighter) bump(engine, TELE_AOE_SHIP_HITS)
        }

        RiftExplosionVfx.riftExplosion(engine, point, radius = RiftExplosionVfx.DEFAULT_RADIUS * RIFT_RADIUS_SCALE)
        bump(engine, TELE_EXPLOSIONS)
    }

    const val TELE_LINE_CROSS = "astd_stellar_mrm_tele_line_cross"

    const val TELE_BONUS_HITS = "astd_stellar_mrm_tele_bonus_hits"

    const val TELE_EMP_HITS = "astd_stellar_mrm_tele_emp_hits"

    const val TELE_EMP_ARCS = "astd_stellar_mrm_tele_emp_arcs"

    const val TELE_AOE_HITS = "astd_stellar_mrm_tele_aoe_hits"

    const val TELE_AOE_SHIP_HITS = "astd_stellar_mrm_tele_aoe_ship_hits"

    const val TELE_SHIELD_HITS = "astd_stellar_mrm_tele_shield_hits"

    const val TELE_EXPLOSIONS = "astd_stellar_mrm_tele_explosions"

    const val TELE_LAST_F_BONUS = "astd_stellar_mrm_tele_last_fbonus"

    const val TELE_LAST_W_EMP = "astd_stellar_mrm_tele_last_wemp"

    const val TELE_LAST_EXP_MULT = "astd_stellar_mrm_tele_last_expmult"

    const val TELE_OWNER_PLAYER = "_p"

    const val TELE_OWNER_ENEMY = "_e"

    private fun bump(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }
}
