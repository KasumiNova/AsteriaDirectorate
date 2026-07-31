package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.buffHost
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
 * 电荷针刺 / 重型电荷针刺的命中路由（规格 01 §2.3）：挂两个 `.proj` 的 `onHitEffect`。
 *
 * - 护盾命中 → 目标舰电荷淤积叠层（[ChargeNeedleStacks]，耗散安全闸 clamp）+ 冷蓝白轻粒子；
 * - 船体/装甲命中 → 按难度概率泄放 EMP 电弧（[ChargeNeedleVfx.discharge]，真实 `spawnEmpArc` 结算）。
 *
 * 两分支互斥（`shieldHit` 二分），无交叉结算；`applyDamage` 不经手（EMP 电弧自带结算），无二次 onHit 回环。
 * 难度取值每次命中调用 [ChargeNeedleTuning.resolve] 一次（不缓存）；泄放随机走共享 [CombatRandom]，
 * 每武器实例一个确定性序列（Weapon 级标记 Buff [ChargeNeedleShots] 记 callIndex），同帧同事件不二次取值。
 */
class ChargeNeedleOnHitEffect : OnHitEffectPlugin {

    /** 无盾/零耗盾目标 DEBUG 日志闸（一次/船，按宿主身份去重）。 */
    private val debugLoggedNoUpkeepShips = mutableSetOf<String>()

    /** 游离弹（无源舰/武器）泄放随机退化 WARN 日志闸（一次/目标船）。 */
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

        // 只淤积/泄放舰船；战机/陨石等豁免。
        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isPhased) return

        // 某些实体命中回调 point 可能为 null；回退弹体当前位置（对齐 HighFluxShieldPressure 样板）。
        val hitPoint = point ?: projectile.location ?: return

        // 玩家固定 v2 取值在每次命中处调用（非缓存）；projectile.source 为 null（游离弹）按非玩家口径。
        val values = ChargeNeedleTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0)

        if (shieldHit) {
            applyShieldHit(projectile, ship, hitPoint, values, engine)
        } else {
            applyHullHit(projectile, ship, hitPoint, values, engine)
        }
    }

    /** 护盾命中：淤积叠层 + 轻粒子。 */
    private fun applyShieldHit(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        hitPoint: Vector2f,
        values: ChargeNeedleTuning.Values,
        engine: CombatEngineAPI,
    ) {
        val baseUpkeep = ship.hullSpec.shieldSpec?.upkeepCost ?: 0f
        if (baseUpkeep <= 0f) {
            // 无盾/零耗盾目标不淤积（安全闸豁免分支的防御日志也在此，一次/船）。
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
        buff.addStacks(1)
        if (projectile.source != null && projectile.source == engine.playerShip) buff.showOnPlayerHud = true

        ChargeNeedleVfx.shieldHitParticles(engine, hitPoint, ship)
    }

    /** 船体/装甲命中：概率泄放 EMP 电弧（结算随机走共享 CombatRandom）。 */
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
            // 游离弹无稳定种子来源：泄放随机退化为 Misc.random（非确定性边缘路径，WARN 一次/目标船不静默）。
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
