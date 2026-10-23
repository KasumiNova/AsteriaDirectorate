package cn.kasuminova.astd.combat.effect.arc.heavyionpulse

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

class HeavyIonPulseOnHitEffect : OnHitEffectPlugin {

    private val warnedZeroEmpWeaponIds = mutableSetOf<String>()

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

        if (shieldHit) return

        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isPhased) return

        // 命中回调的 point 可能为 null，回退到弹体当前位置。
        val hitPoint = point ?: projectile.location ?: return

        val values = HeavyIonPulseTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0)

        val baseEmp = projectile.empAmount
        if (baseEmp <= 0f) {
            val weaponKey = projectile.weapon?.id ?: projectile.projectileSpecId ?: "unknown"
            if (warnedZeroEmpWeaponIds.add(weaponKey)) {
                log.warn("重型离子脉冲面板 EMP ≤ 0（$baseEmp），配置异常，泄放与贯穿全部跳过: weapon=$weaponKey")
            }
            return
        }

        HeavyIonPulseVfx.recordHullHit(engine, projectile.source)

        val host = ship.buffHost()
        val resistBuff = host.find(HeavyIonPulseEmpResistStacks.BUFF_ID) as? HeavyIonPulseEmpResistStacks
            ?: HeavyIonPulseEmpResistStacks(ship, engine, host).also { host.register(it) }
        resistBuff.perStack = values.empResistPerStack
        resistBuff.armamentUpgrade =
            SicWeaponUpgradeFlags.isArmamentUpgradeActive(projectile.source ?: projectile.weapon?.ship)
        resistBuff.addStacks(1)
        if (projectile.source != null && projectile.source == engine.playerShip) resistBuff.showOnPlayerHud = true

        val roll = dischargeRoll(projectile, ship)
        var arcEmp = 0f
        if (HeavyIonPulseTuning.shouldDischarge(roll, values.dischargeChance)) {
            arcEmp = baseEmp * values.dischargeEmpMult
            HeavyIonPulseVfx.discharge(engine, source = projectile.source, from = hitPoint, target = ship, emp = arcEmp)
        }

        if (HeavyIonPulseTuning.pierceActive(values.isPlayer, DifficultyTuningImpl.fixedScale)) {
            val mult = ship.mutableStats.empDamageTakenMult.modifiedValue
            val extra = HeavyIonPulseTuning.empPierceExtra(baseEmp + arcEmp, mult)
            val applied = HeavyIonPulseTuning.empPierceApplied(extra, mult)
            if (applied > 0f) {
                HeavyIonPulseVfx.pierce(
                    engine, ship, hitPoint, extra, applied,
                    source = projectile.source, mult = mult, baseEmp = baseEmp, arcEmp = arcEmp,
                )
            }
        }
    }

    private fun dischargeRoll(projectile: DamagingProjectileAPI, ship: ShipAPI): Float {
        val sourceShip = projectile.source ?: projectile.weapon?.ship
        val weapon = projectile.weapon
        if (sourceShip != null && weapon != null) {
            val shots = sourceShip.getOrCreateBuffByWeapon(HeavyIonPulseShots.SHOTS_ID, weapon) {
                HeavyIonPulseShots(sourceShip, weapon)
            } as HeavyIonPulseShots
            return CombatRandom.nextFloatIn(shots.seed, shots.callIndex++, 0f..1f)
        }
        val shipKey = ship.id ?: ship.hullSpec.hullId ?: "unknown"
        if (warnedFreeProjectileShips.add(shipKey)) {
            log.warn("重型离子脉冲游离弹（无源舰/武器）命中，泄放随机退化为 Misc.random（非确定性）: target=$shipKey")
        }
        return Misc.random.nextFloat()
    }

    companion object {
        private val log = Global.getLogger(HeavyIonPulseOnHitEffect::class.java)
    }
}
