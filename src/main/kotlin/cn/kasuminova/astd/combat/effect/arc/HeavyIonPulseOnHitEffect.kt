package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.getOrCreateBuffByWeapon
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

/**
 * 重型离子脉冲的命中路由（规格 02 §2.3，结构对照 01 电荷针刺）：挂 `.proj` 的 `onHitEffect`。
 *
 * - 护盾命中 → 直接返回（EMP 对盾无效，面板 EMP 亦不产生贯穿话题）；
 * - 船体/装甲命中 → 按难度概率泄放 EMP 电弧（[HeavyIonPulseVfx.discharge]，真实 `spawnEmpArc` 结算）
 *   +（破晓敌版限定）EMP 贯穿补伤（[HeavyIonPulseVfx.pierce]，面板命中 EMP 与本次电弧 EMP 一起补）。
 *
 * 结算顺序：泄放判定与电弧结算在前，贯穿补伤在后一次性覆盖「面板 EMP + 本次电弧 EMP」两笔；
 * 贯穿走 `applyDamage`（不触发 onHitEffect），无二次 onHit 回环。
 *
 * 难度取值每次命中调用 [HeavyIonPulseTuning.resolve] 一次（不缓存）；泄放随机走共享 [CombatRandom]，
 * 每武器实例一个确定性序列（Weapon 级标记 Buff [HeavyIonPulseShots] 记 callIndex），同帧同事件不二次取值。
 */
class HeavyIonPulseOnHitEffect : OnHitEffectPlugin {

    /** `baseEmp ≤ 0` 配置异常 WARN 闸（一次/武器 id）。 */
    private val warnedZeroEmpWeaponIds = mutableSetOf<String>()

    /** 游离弹（无源舰/武器）泄放随机退化 WARN 闸（一次/目标船）。 */
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

        // EMP 对盾无效（原版特性），面板 EMP 亦不产生贯穿话题。
        if (shieldHit) return

        // 只对舰船泄放/贯穿；战机/陨石豁免。
        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isPhased) return

        // 某些实体命中回调 point 可能为 null；回退弹体当前位置（对齐 HighFluxShieldPressure 样板）。
        val hitPoint = point ?: projectile.location ?: return

        // 玩家固定 v2 取值在每次命中处调用（非缓存）；projectile.source 为 null（游离弹）按非玩家口径。
        val values = HeavyIonPulseTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0)

        // 面板 600 × 武器侧修正；≤ 0 属配置异常（emp 列被清/被其他 mod 清零）——
        // 面板 EMP 是本件存在意义，归零不静默：WARN 一次/武器 id 后泄放与贯穿全部跳过。
        val baseEmp = projectile.empAmount
        if (baseEmp <= 0f) {
            val weaponKey = projectile.weapon?.id ?: projectile.projectileSpecId ?: "unknown"
            if (warnedZeroEmpWeaponIds.add(weaponKey)) {
                log.warn("重型离子脉冲面板 EMP ≤ 0（$baseEmp），配置异常，泄放与贯穿全部跳过: weapon=$weaponKey")
            }
            return
        }

        HeavyIonPulseVfx.recordHullHit(engine, projectile.source)

        // 瘫痪电弧：结算随机走共享 CombatRandom（同帧同事件不二次取值）。
        val roll = dischargeRoll(projectile, ship)
        var arcEmp = 0f
        if (HeavyIonPulseTuning.shouldDischarge(roll, values.dischargeChance)) {
            arcEmp = HeavyIonPulseTuning.BASE_DISCHARGE_EMP * values.dischargeEmpMult
            HeavyIonPulseVfx.discharge(engine, source = projectile.source, from = hitPoint, target = ship, emp = arcEmp)
        }

        // EMP 贯穿（破晓敌版限定）：面板命中 EMP 与电弧 EMP 一起补。
        // 折算补偿（A9 裁定方案 a）：applyDamage 的 empDamage 会被目标 empDamageTakenMult 再乘一次，
        // 施加量为 extra/max(mult, 0.01)，引擎二次乘算后实际结算回补到 extra；mult ≤ 0 完全免疫时
        // applied = 0 整体跳过（补偿无法突破 0 乘区，不弹假浮字）。
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

    /** 泄放结算随机取值：每武器实例一个确定性序列；游离弹退化为 Misc.random（WARN 一次/目标船不静默）。 */
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
