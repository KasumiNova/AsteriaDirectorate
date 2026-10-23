package cn.kasuminova.astd.combat.effect.arc.chargeneedle

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.buff.getOrCreateBuffByWeapon
import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import cn.kasuminova.astd.impl.combat.CombatRandom
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

class ChargeNeedleOnHitEffect : OnHitEffectPlugin {

    private val debugLoggedNoUpkeepShips = mutableSetOf<String>()

    private val warnedFreeProjectileShips = mutableSetOf<String>()

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isPhased) return

        // 命中回调的 point 可能为 null，回退到弹体当前位置。
        val hitPoint = point ?: projectile.location ?: return

        val values = ChargeNeedleTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0, ship.hullSize)

        if (shieldHit) {
            applyShieldHit(projectile, ship, hitPoint, values, engine)
        } else {
            applyHullHit(projectile, ship, hitPoint, values, engine)
        }
    }

    private fun applyShieldHit(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        hitPoint: Vector2f,
        values: ChargeNeedleTuning.Values,
        engine: CombatEngineAPI,
    ) {
        val baseUpkeep = ship.hullSpec.shieldSpec?.upkeepCost ?: 0f
        if (baseUpkeep <= 0f) {
            val shipKey = ship.id ?: ship.hullSpec.hullId ?: "unknown"
            if (debugLoggedNoUpkeepShips.add(shipKey)) {
                log.debug("电荷针刺命中护盾但目标基础维持 ≤ 0（$baseUpkeep），不淤积: ship=$shipKey")
            }
            return
        }

        val host = ship.buffHost()
        val buff = host.find(ChargeNeedleStacks.BUFF_ID) as? ChargeNeedleStacks
            ?: ChargeNeedleStacks(ship, engine, host).also { host.register(it) }
        buff.perStack = values.perStack
        buff.flatPerStack = values.flatFluxPerStack
        buff.fluxRegardlessOfShield =
            SicWeaponUpgradeFlags.isArmamentUpgradeActive(projectile.source ?: projectile.weapon?.ship)
        buff.addStacks(1)
        if (projectile.source != null && projectile.source == engine.playerShip) buff.showOnPlayerHud = true

        ChargeNeedleVfx.shieldHitParticles(engine, hitPoint, ship)
    }

    private fun applyHullHit(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        hitPoint: Vector2f,
        values: ChargeNeedleTuning.Values,
        engine: CombatEngineAPI,
    ) {
        val sourceShip = projectile.source ?: projectile.weapon?.ship
        val weapon = projectile.weapon

        val roll: Float = if (sourceShip != null && weapon != null) {
            val shots = sourceShip.getOrCreateBuffByWeapon(ChargeNeedleShots.SHOTS_ID, weapon) {
                ChargeNeedleShots(sourceShip, weapon)
            } as ChargeNeedleShots
            CombatRandom.nextFloatIn(shots.seed, shots.callIndex++, 0f..1f)
        } else {
            val shipKey = ship.id ?: ship.hullSpec.hullId ?: "unknown"
            if (warnedFreeProjectileShips.add(shipKey)) {
                log.warn("电荷针刺游离弹（无源舰/武器）命中，泄放随机退化为 Misc.random（非确定性）: target=$shipKey")
            }
            Misc.random.nextFloat()
        }

        if (ChargeNeedleTuning.shouldDischarge(roll, values.dischargeChance)) {
            ChargeNeedleVfx.discharge(
                engine,
                source = projectile.source,
                from = hitPoint,
                target = ship,
                emp = ChargeNeedleTuning.BASE_DISCHARGE_EMP * values.dischargeEmpMult,
            )
        }
    }

    companion object {
        private val log = Global.getLogger(ChargeNeedleOnHitEffect::class.java)
    }
}
