package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingMoteAi
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingMotes
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.input.Mouse
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin

/**
 * 星翼折跃（astd_rift_shift，星翼-级舰船系统）。
 * 0.1s 切入 → 0.25s 相位滑行（2.5x 时流、3 倍转向对齐准星）→ 0.15s 现身收束；
 * 进出双次净空力场，现身满额星羽齐射、主炮瞬装、软幅排散并进入 3s 超翼过载。
 * 透支折跃的充能池逻辑由 [cn.kasuminova.astd.combat.hullmods.arc.ASTDImaginaryWingsHullMod] 管理。
 */
class StarwingShiftSystemStats : PhaseCloakStats() {

    private class ShiftState {
        var started = false
        var outFired = false
        val start = Vector2f()
        val dir = Vector2f(1f, 0f)
        var dist = 0f
        var startTime = 0f
        var lastFrameTime = 0f
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        super.apply(stats, id, state, effectLevel)
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return

        val key = STATE_KEY_PREFIX + ship.id
        if (state == ShipSystemStatsScript.State.IDLE || state == ShipSystemStatsScript.State.COOLDOWN) {
            engine.customData.remove(key)
            // 系统被 EMP 等打断导致 OUT 结算未执行时，透支标记不能留到下一次折跃
            ImaginaryCharge.of(ship).overdraftActive = false
            return
        }

        // 滑行期间 2.5x 舰船时流（PhaseCloakStats 上限 3x，dynamic 系数 0.75）
        // 必须写 getStat：getMaxTimeMult 读取的是 getValue，只查 stats 表而非 mods 表
        stats.dynamic.getStat("phase_time_mult").modifyFlat(id, ImaginaryWingsTuning.SHIFT_TIME_MULT_DYNAMIC)

        val shift = engine.customData.getOrPut(key) { ShiftState() } as ShiftState
        val now = engine.getTotalElapsedTime(false)

        if (state == ShipSystemStatsScript.State.IN && !shift.started) {
            shift.started = true
            shift.start.set(ship.location)
            shift.dir.set(shiftDirection(ship))
            shift.dist = ship.mutableStats.systemRangeBonus.computeEffective(ImaginaryWingsTuning.SHIFT_DISTANCE)
            shift.startTime = now
            clearPulse(engine, ship)
            StarwingShiftVfx.burstAlongPath(engine, shift.start, shift.dir, shift.dist)
        }

        if (state == ShipSystemStatsScript.State.IN || state == ShipSystemStatsScript.State.ACTIVE) {
            alignFacingToAim(engine, ship, shift, now)
        }

        if (state == ShipSystemStatsScript.State.ACTIVE) {
            // progress 只对齐 ACTIVE 滑行窗口，需扣除 IN（chargeUp）阶段耗时
            val chargeUp = ship.system?.chargeUpDur ?: 0f
            val progress = ((now - shift.startTime - chargeUp) / ACTIVE_GLIDE_SECONDS).coerceIn(0f, 1f)
            val eased = easeProgress(progress)
            ship.location.set(
                shift.start.x + shift.dir.x * shift.dist * eased,
                shift.start.y + shift.dir.y * shift.dist * eased,
            )
        }

        if (state == ShipSystemStatsScript.State.OUT && !shift.outFired) {
            shift.outFired = true
            ship.location.set(
                shift.start.x + shift.dir.x * shift.dist,
                shift.start.y + shift.dir.y * shift.dist,
            )
            val exitSpeed = ship.mutableStats.maxSpeed.modifiedValue * EXIT_SPEED_MULT
            ship.velocity.set(shift.dir.x * exitSpeed, shift.dir.y * exitSpeed)
            clearPulse(engine, ship)
            StarwingShiftVfx.emergePulse(engine, ship.location)
            resolveEmerge(engine, ship)
        }

        shift.lastFrameTime = now
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        super.unapply(stats, id)
        stats.dynamic.getStat("phase_time_mult").unmodifyFlat(id)
    }

    // 原版 PhaseCloakStats.maintainStatus 硬编码英文状态文本，此处按相同结构输出 I18n 文本
    override fun maintainStatus(playerShip: ShipAPI, state: ShipSystemStatsScript.State, effectLevel: Float) {
        if (effectLevel <= VULNERABLE_FRACTION) return
        val cloak = playerShip.phaseCloak ?: playerShip.system ?: return
        Global.getCombatEngine()?.maintainStatusForPlayerShip(
            STATUSKEY2, cloak.specAPI.iconSpriteName, cloak.displayName,
            I18n[I18n.Categories.MOD, "system.rift_shift.status.0"], false,
        )
    }

    // ---------- 位移与朝向 ----------

    private fun shiftDirection(ship: ShipAPI): Vector2f {
        if (ship.velocity.lengthSquared() > 1f) {
            val v = Vector2f(ship.velocity)
            v.normalise()
            return v
        }
        val rad = Math.toRadians(ship.facing.toDouble())
        return Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
    }

    /** 滑行期间舰首以 3 倍转向速率平滑对齐鼠标准星（AI 对齐当前攻击目标）。 */
    private fun alignFacingToAim(engine: CombatEngineAPI, ship: ShipAPI, shift: ShiftState, now: Float) {
        val aim = resolveAimPoint(engine, ship, shift) ?: return
        val desired = Misc.getAngleInDegrees(ship.location, aim)
        val dt = (now - shift.lastFrameTime).coerceIn(0f, 0.1f)
        if (dt <= 0f) return
        val maxTurn = ship.mutableStats.maxTurnRate.modifiedValue * ImaginaryWingsTuning.SHIFT_TURN_MULT * dt
        val diff = MathUtils.getShortestRotation(ship.facing, desired)
        ship.facing += diff.coerceIn(-maxTurn, maxTurn)
    }

    private fun resolveAimPoint(engine: CombatEngineAPI, ship: ShipAPI, shift: ShiftState): Vector2f? {
        if (ship === engine.playerShip) {
            val viewport = engine.viewport ?: return null
            return Vector2f(
                viewport.convertScreenXToWorldX(Mouse.getX().toFloat()),
                viewport.convertScreenYToWorldY(Mouse.getY().toFloat()),
            )
        }
        val target = ship.shipTarget
        if (target != null && target.isAlive && !target.isHulk) return Vector2f(target.location)
        return Vector2f(
            shift.start.x + shift.dir.x * shift.dist,
            shift.start.y + shift.dir.y * shift.dist,
        )
    }

    // ---------- 净空力场 ----------

    /** 抹除 250su 内单发 ≤600 的敌方非导弹射弹，>600 的重型射弹永久削减伤害；并断开所有锁定本舰的敌方导弹诱导。 */
    private fun clearPulse(engine: CombatEngineAPI, ship: ShipAPI) {
        val reduction = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.CLEAR_HEAVY_REDUCTION, ship.owner == 0)
        val radius = ImaginaryWingsTuning.CLEAR_RADIUS
        for (proj in engine.projectiles.toList()) {
            if (proj is MissileAPI) continue
            if (proj.owner == ship.owner) continue
            if (MathUtils.getDistance(proj.location, ship.location) > radius + proj.collisionRadius) continue
            if (proj.damageAmount <= ImaginaryWingsTuning.CLEAR_DAMAGE_THRESHOLD) {
                engine.removeEntity(proj)
            } else {
                proj.setDamageAmount(proj.damageAmount * (1f - reduction))
            }
        }
        for (missile in engine.missiles) {
            if (missile.owner == ship.owner) continue
            val ai = missile.missileAI as? GuidedMissileAI ?: continue
            if (ai.target === ship) ai.setTarget(null)
        }
    }

    // ---------- 现身结算 ----------

    private fun resolveEmerge(engine: CombatEngineAPI, ship: ShipAPI) {
        val pool = ImaginaryCharge.of(ship)
        val isPlayer = ship.owner == 0

        if (pool.overdraftActive) {
            // 透支折跃：不拉满不扣 30%，齐射免费；50% 已在激活边沿扣除
            pool.overdraftActive = false
        } else {
            pool.charge = ImaginaryWingsTuning.SALVO_REFILL
            pool.consume(ImaginaryWingsTuning.SALVO_COST)
        }

        val target = resolveSalvoTarget(engine, ship, pool)
        for ((left, right) in StarfallWingMotes.wingSlotPairs(ship)) {
            StarfallWingMotes.spawnFromSlot(engine, ship, left, target)
            StarfallWingMotes.spawnFromSlot(engine, ship, right, target)
        }

        val reload = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SHIFT_AMMO_RELOAD, isPlayer).toInt()
        for (weapon in ship.allWeapons) {
            if (weapon.id != StarfallWingTuning.WEAPON_ID) continue
            val tracker = weapon.ammoTracker ?: continue
            if (tracker.maxAmmo <= 0) continue
            tracker.setAmmo((tracker.ammo + reload).coerceAtMost(tracker.maxAmmo))
        }

        val softFlux = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SHIFT_SOFT_FLUX, isPlayer)
        ship.fluxTracker.decreaseFlux(softFlux)

        pool.overdriveRemaining = ImaginaryWingsTuning.OVERDRIVE_SECONDS
    }

    private fun resolveSalvoTarget(engine: CombatEngineAPI, ship: ShipAPI, pool: ImaginaryCharge): CombatEntityAPI? {
        fun valid(t: ShipAPI?): Boolean {
            if (t == null || t === ship) return false
            if (!engine.isEntityInPlay(t) || !t.isAlive || t.isHulk || t.isFighter || t.isDrone) return false
            return t.owner != ship.owner
        }
        if (valid(ship.shipTarget)) return ship.shipTarget
        if (valid(pool.lastMainGunHit)) return pool.lastMainGunHit
        // 光矢按点防定位运作，齐射同样沿用导弹 → 战机 → 舰船的接敌优先级
        return StarfallWingMoteAi.acquirePointDefenseTarget(engine, ship, ship.location)
    }

    private fun easeProgress(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        private const val STATE_KEY_PREFIX = "astd_starwing_shift_state:"

        private const val ACTIVE_GLIDE_SECONDS = 0.25f

        private const val EXIT_SPEED_MULT = 1.5f
    }
}
