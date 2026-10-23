package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravStormTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.effect.system.GravStormConeIndicator
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

class GravStormSystemStats : BaseShipSystemScript() {

    private class ChargeState(
        val indicator: GravStormConeIndicator?,
    ) {
        // toggle 系统转 OUT 后 effectLevel 按释放窗口折算，充能进度只能自持记录
        var chargeProgress: Float = 0f

        var arcTimer: Float = 0f
    }

    internal class TargetPlan(
        val target: ShipAPI,
        val arcCount: Int,
        val damageMult: Float,
        val overloadSeconds: Float,
    )

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        val system = ship.system ?: return

        val values = GravStormTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val damageTakenMult = 1f - values.damageTakenReduction
        stats.hullDamageTakenMult.modifyMult(id, damageTakenMult)
        stats.armorDamageTakenMult.modifyMult(id, damageTakenMult)
        stats.shieldDamageTakenMult.modifyMult(id, damageTakenMult)
        stats.empDamageTakenMult.modifyMult(id, damageTakenMult)

        when (state) {
            ShipSystemStatsScript.State.IN -> chargeTick(engine, ship, system, id, effectLevel)
            ShipSystemStatsScript.State.ACTIVE -> onActiveEntered(engine, ship, system)
            ShipSystemStatsScript.State.OUT -> releaseTick(engine, ship, system)
            else -> Unit
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.hullDamageTakenMult.unmodifyMult(id)
        stats.armorDamageTakenMult.unmodifyMult(id)
        stats.shieldDamageTakenMult.unmodifyMult(id)
        stats.empDamageTakenMult.unmodifyMult(id)

        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        disposeCharge(engine, ship)
        engine.customData.remove(activationKey(ship))
    }

    // 原版同一帧内 ChargeTracker.advance 先于脚本 apply：IN 末帧进相位会被状态机直接顶进 ACTIVE，此处按相位规则满充能释放
    internal fun onActiveEntered(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        system: ShipSystemAPI,
        releaseFn: (CombatEngineAPI, ShipAPI, Float) -> Unit = ::release,
    ) {
        if (ship.isPhased) {
            endChargeByPhase(engine, ship, system, GravStormTuning.MAX_CHARGE_SECONDS, releaseFn)
            return
        }
        if (engine.customData[activationKey(ship)] == null) {
            release(engine, ship, GravStormTuning.MAX_CHARGE_SECONDS)
        }
        system.forceState(ShipSystemAPI.SystemState.OUT, 0f)
    }

    internal fun endChargeByPhase(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        system: ShipSystemAPI,
        chargeSeconds: Float,
        releaseFn: (CombatEngineAPI, ShipAPI, Float) -> Unit = ::release,
    ) {
        releaseFn(engine, ship, chargeSeconds)
        system.deactivate()
    }

    private fun chargeTick(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        system: ShipSystemAPI,
        id: String,
        effectLevel: Float,
    ) {
        val key = chargeKey(ship)
        var charge = engine.customData[key] as? ChargeState
        if (charge == null) {
            charge = ChargeState(GravStormConeIndicator.attach(engine, ship))
            engine.customData[key] = charge
        }

        val progress = effectLevel.coerceIn(0f, 1f)
        val chargeSeconds = progress * GravStormTuning.MAX_CHARGE_SECONDS
        if (GravStormTuning.phaseLockoutActive(chargeSeconds)) {
            suppressPhaseCloak(ship)
        } else if (ship.isPhased) {
            endChargeByPhase(engine, ship, system, chargeSeconds)
            return
        }

        charge.chargeProgress = progress
        // 充能进度供 GravEmFieldHullMod 读取
        ship.setCustomData(CHARGE_PROGRESS_KEY, progress)
        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravStormTuning.BASE_RANGE)
        charge.indicator?.update(range, CONE_ALPHA_BASE + CONE_ALPHA_SPAN * progress)
        driveChargeArcs(engine, ship, charge, progress, range)

        ship.setJitter(id, JITTER_COLOR, JITTER_LEVEL_BASE + JITTER_LEVEL_SPAN * progress, 4, 0f, 2f + 6f * progress)
        ship.setJitterUnder(id, JITTER_UNDER_COLOR, JITTER_LEVEL_BASE + JITTER_LEVEL_SPAN * progress, 20, 0f, 4f + 8f * progress)
    }

    private fun driveChargeArcs(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        charge: ChargeState,
        progress: Float,
        range: Float,
    ) {
        charge.arcTimer += engine.elapsedInLastFrame
        val interval = GravStormTuning.chargeArcInterval(progress)
        while (charge.arcTimer >= interval) {
            charge.arcTimer -= interval
            repeat(GravStormTuning.chargeArcCount(progress)) { spawnDecorativeArc(engine, ship, range) }
        }
    }

    // 原版 ShipSystemAPI.setCooldownRemaining 一步置 COOLDOWN 态，COOLDOWN 下按键仅置失败标记不再激活
    private fun suppressPhaseCloak(ship: ShipAPI) {
        val cloak = ship.phaseCloak ?: return
        if (cloak.state != ShipSystemAPI.SystemState.COOLDOWN ||
            cloak.cooldownRemaining < GravStormTuning.PHASE_LOCKOUT_COOLDOWN_REMAINING
        ) {
            cloak.cooldownRemaining = GravStormTuning.PHASE_LOCKOUT_COOLDOWN_REMAINING
        }
    }

    private fun releaseTick(engine: CombatEngineAPI, ship: ShipAPI, system: ShipSystemAPI) {
        if (engine.customData[activationKey(ship)] != null) return
        val charge = engine.customData[chargeKey(ship)] as? ChargeState
        val chargeSeconds = (charge?.chargeProgress ?: 0f) * GravStormTuning.MAX_CHARGE_SECONDS
        if (chargeSeconds >= GravStormTuning.MIN_CHARGE_SECONDS) {
            release(engine, ship, chargeSeconds)
            system.forceState(ShipSystemAPI.SystemState.OUT, 0f)
        } else {
            disposeCharge(engine, ship)
            system.deactivate()
        }
    }

    private fun release(engine: CombatEngineAPI, ship: ShipAPI, chargeSeconds: Float) {
        disposeCharge(engine, ship)

        val values = GravStormTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravStormTuning.BASE_RANGE)
        val targets = scanConeTargets(engine, ship, range)
        val chargeNorm = GravStormTuning.chargeNorm(chargeSeconds)

        val plans = ArrayList<TargetPlan>(targets.size)
        for (target in targets) {
            val countRange = GravStormTuning.arcBaseCountRange(target.hullSize) ?: continue
            val overloadAnchor = GravStormTuning.overloadAnchor(values, target.hullSize) ?: continue
            val baseCount = MathUtils.getRandomNumberInRange(countRange.first, countRange.endInclusive)
            plans += TargetPlan(
                target = target,
                arcCount = GravStormTuning.finalArcCount(baseCount, targets.size),
                damageMult = if (target.isFighter) GravStormTuning.FIGHTER_DAMAGE_MULT else 1f,
                overloadSeconds = GravStormTuning.overloadDuration(overloadAnchor, chargeNorm),
            )
        }

        applyOverloads(engine, ship, plans)
        if (plans.isEmpty()) {
            spawnDecorativeArcs(engine, ship, range)
        } else {
            for (plan in plans) {
                repeat(plan.arcCount) { fireArc(engine, ship, plan, values) }
            }
        }
        spawnReleaseDistortion(engine, ship)
        Global.getSoundPlayer().playSound(RELEASE_SOUND_ID, 1f, 1f, ship.location, ship.velocity)

        engine.customData[activationKey(ship)] = true
    }

    private fun scanConeTargets(engine: CombatEngineAPI, ship: ShipAPI, range: Float): List<ShipAPI> {
        val result = ArrayList<ShipAPI>()
        for (target in engine.ships) {
            if (target == null || target === ship) continue
            if (target.owner == ship.owner || target.isHulk || !target.isAlive) continue
            if (GravStormTuning.arcBaseCountRange(target.hullSize) == null) continue
            val dist = Misc.getDistance(ship.location, target.location) - target.collisionRadius
            if (dist > range) continue
            val angleDiff = Misc.getAngleDiff(ship.facing, Misc.getAngleInDegrees(ship.location, target.location))
            if (!GravStormTuning.isInCone(angleDiff)) continue
            result += target
        }
        return result
    }

    private fun spawnDecorativeArcs(engine: CombatEngineAPI, ship: ShipAPI, range: Float) {
        val count = MathUtils.getRandomNumberInRange(DECOR_ARC_COUNT_MIN, DECOR_ARC_COUNT_MAX)
        repeat(count) { spawnDecorativeArc(engine, ship, range) }
    }

    private fun spawnDecorativeArc(engine: CombatEngineAPI, ship: ShipAPI, range: Float) {
        val from = hullBoundaryPoint(ship)
        val angle = ship.facing + MathUtils.getRandomNumberInRange(
            -GravStormTuning.CONE_HALF_ANGLE_DEG, GravStormTuning.CONE_HALF_ANGLE_DEG,
        )
        val dist = range * MathUtils.getRandomNumberInRange(DECOR_ARC_DIST_MIN_FRACTION, DECOR_ARC_DIST_MAX_FRACTION)
        val rad = Math.toRadians(angle.toDouble())
        val to = Vector2f(
            from.x + (cos(rad) * dist).toFloat(),
            from.y + (sin(rad) * dist).toFloat(),
        )
        val params = EmpArcEntityAPI.EmpArcParams().apply {
            segmentLengthMult = 5f
            zigZagReductionFactor = 0.12f
            fadeOutDist = 200f
            minFadeOutMult = 6f
            flickerRateMult = 0.42f
            movementDurMax = 0.5f
            movementDurMin = 0.2f
        }
        engine.spawnEmpArcVisual(from, ship, to, ship, DECOR_ARC_THICKNESS, ARC_FRINGE, ARC_CORE, params)
    }

    internal fun applyOverloads(engine: CombatEngineAPI, ship: ShipAPI, plans: List<TargetPlan>) {
        val tracked = ArrayList<ShipAPI>(plans.size)
        var totalRatio = 0f
        for (plan in plans) {
            val target = plan.target
            if (!target.isAlive || target.isHulk) continue
            if (target.fluxTracker.isOverloadedOrVenting) continue
            target.setOverloadColor(OVERLOAD_COLOR)
            target.fluxTracker.beginOverloadWithTotalBaseDuration(plan.overloadSeconds)
            totalRatio += GravStormTuning.hardToSoftRatio(target.hullSize, plan.overloadSeconds)
            if (target.fluxTracker.showFloaty() || ship === engine.playerShip || target === engine.playerShip) {
                target.fluxTracker.playOverloadSound()
                target.fluxTracker.showOverloadFloatyIfNeeded(
                    I18n[I18n.Categories.MOD, "ui.grav_storm.overload_floaty"],
                    OVERLOAD_COLOR, 4f, true,
                )
            }
            tracked += target
        }
        convertHardToSoft(ship, totalRatio)
        if (tracked.isNotEmpty()) {
            engine.addPlugin(OverloadColorResetPlugin(engine, tracked))
        }
    }

    // 只调 setHardFlux 完成等额转化：increaseFlux 第二参 true 加的是硬辐能且总辐能 +x，与转化语义相反
    internal fun convertHardToSoft(ship: ShipAPI, totalRatio: Float) {
        val tracker = ship.fluxTracker
        val amount = tracker.hardFlux * totalRatio
        if (amount <= 0f) return
        tracker.hardFlux -= amount
    }

    private fun fireArc(engine: CombatEngineAPI, ship: ShipAPI, plan: TargetPlan, values: GravStormTuning.Values) {
        val target = plan.target
        if (!target.isAlive || target.isHulk) return
        val from = hullBoundaryPoint(ship)
        val to = randomSlotPoint(target)
        val maxRange = Misc.getDistance(from, to) * 1.2f + 50f
        engine.spawnEmpArc(
            ship, from, ship, target,
            DamageType.ENERGY,
            values.arcEnergyDamage * plan.damageMult, values.arcEmpDamage * plan.damageMult,
            maxRange, ARC_IMPACT_SOUND_ID,
            ARC_THICKNESS, ARC_FRINGE, ARC_CORE,
        )
    }

    private fun randomSlotPoint(target: ShipAPI): Vector2f {
        val points = ArrayList<Vector2f>()
        for (weapon in target.allWeapons) {
            if (weapon != null) points += weapon.location
        }
        val engines = target.engineController?.shipEngines
        if (engines != null) {
            for (shipEngine in engines) {
                if (shipEngine != null) points += shipEngine.location
            }
        }
        val base = if (points.isEmpty()) {
            MathUtils.getRandomPointInCircle(target.location, target.collisionRadius * 0.5f)
        } else {
            points[MathUtils.getRandomNumberInRange(0, points.size - 1)]
        }
        return MathUtils.getRandomPointInCircle(base, SLOT_POINT_SCATTER)
    }

    private fun spawnReleaseDistortion(engine: CombatEngineAPI, ship: ShipAPI) {
        BoxUtilCombatVfx.ensureReady(engine)
        val r = ship.collisionRadius
        val distortion = DistortionEntity()
        distortion.setGlobalTimer(0.5f, 0.5f, 0.5f)
        distortion.setSizeIn(r * 0.20f, r * 0.20f)
        distortion.setSizeFull(r * 0.80f, r * 0.80f)
        distortion.setSizeOut(r * 2.20f, r * 2.20f)
        distortion.setInnerFull(0.20f, 0.20f)
        distortion.setInnerOut(0.55f, 0.55f)
        distortion.innerHardness = 0.80f
        distortion.ringHardness = 0.60f
        distortion.powerIn = 0f
        distortion.powerFull = 0.55f
        distortion.powerOut = 0f
        distortion.setLocation(Vector2f(ship.location))
        val addState = BoxUtilCombatVfx.addEntity(engine, distortion)
        if (addState != 0) {
            log.warn("[ASTD] 引力磁暴释放扭曲注册失败（addEntity 返回 $addState，ship=${ship.id}），本次扭曲视觉缺席")
            distortion.delete()
        }
    }

    private fun disposeCharge(engine: CombatEngineAPI, ship: ShipAPI) {
        ship.removeCustomData(CHARGE_PROGRESS_KEY)
        val charge = engine.customData.remove(chargeKey(ship)) as? ChargeState ?: return
        charge.indicator?.dispose()
    }

    private fun hullBoundaryPoint(ship: ShipAPI): Vector2f {
        val bounds = ship.exactBounds
        if (bounds != null) {
            bounds.update(ship.location, ship.facing)
            val segments = bounds.segments
            if (segments.isNotEmpty()) {
                val seg = segments[MathUtils.getRandomNumberInRange(0, segments.size - 1)]
                val t = MathUtils.getRandomNumberInRange(0f, 1f)
                return Vector2f(
                    seg.p1.x + (seg.p2.x - seg.p1.x) * t,
                    seg.p1.y + (seg.p2.y - seg.p1.y) * t,
                )
            }
        }
        val angle = MathUtils.getRandomNumberInRange(0f, 360f)
        val radius = ship.collisionRadius * 0.90f
        val rad = Math.toRadians(angle.toDouble())
        return Vector2f(
            ship.location.x + (cos(rad) * radius).toFloat(),
            ship.location.y + (sin(rad) * radius).toFloat(),
        )
    }

    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean = !ship.isPhased

    override fun getInfoText(system: ShipSystemAPI, ship: ShipAPI): String? {
        if (system.isOutOfAmmo) return null
        if (system.state != ShipSystemAPI.SystemState.IDLE) return null
        return if (ship.isPhased) I18n[I18n.Categories.MOD, "ui.grav_storm.info.phased"] else null
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (index == 0) {
            val suffix = when (state) {
                ShipSystemStatsScript.State.IN -> "in"
                ShipSystemStatsScript.State.ACTIVE -> "active"
                ShipSystemStatsScript.State.OUT -> "out"
                else -> return null
            }
            return ShipSystemStatsScript.StatusData(
                I18n[I18n.Categories.MOD, "system.grav_storm.status.default.$suffix"],
                false,
            )
        }
        if (index == 1 && state == ShipSystemStatsScript.State.IN &&
            GravStormTuning.phaseLockoutActive(effectLevel.coerceIn(0f, 1f) * GravStormTuning.MAX_CHARGE_SECONDS)
        ) {
            return ShipSystemStatsScript.StatusData(
                I18n[I18n.Categories.MOD, "system.grav_storm.status.default.phase_locked"],
                true,
            )
        }
        return null
    }

    private class OverloadColorResetPlugin(
        private val engine: CombatEngineAPI,
        private val tracked: MutableList<ShipAPI>,
    ) : BaseEveryFrameCombatPlugin() {
        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val it = tracked.iterator()
            while (it.hasNext()) {
                val target = it.next()
                if (!target.isAlive || !target.fluxTracker.isOverloadedOrVenting) {
                    if (target.isAlive) target.resetOverloadColor()
                    it.remove()
                }
            }
            if (tracked.isEmpty()) engine.removePlugin(this)
        }
    }

    companion object {
        private val log = Global.getLogger(GravStormSystemStats::class.java)

        private const val RELEASE_SOUND_ID = "astd_grav_storm_release"
        private const val ARC_IMPACT_SOUND_ID = "system_emp_emitter_impact"

        private val ARC_FRINGE = Color(186, 120, 255, 200)
        private val ARC_CORE = Color(238, 215, 255, 160)
        private val OVERLOAD_COLOR = Color(215, 130, 255, 255)

        private const val ARC_THICKNESS = 7f
        private const val SLOT_POINT_SCATTER = 15f

        private const val DECOR_ARC_COUNT_MIN = 8
        private const val DECOR_ARC_COUNT_MAX = 14
        private const val DECOR_ARC_DIST_MIN_FRACTION = 0.3f
        private const val DECOR_ARC_DIST_MAX_FRACTION = 0.9f
        private const val DECOR_ARC_THICKNESS = 7f

        private const val CONE_ALPHA_BASE = 0.15f
        private const val CONE_ALPHA_SPAN = 0.35f

        private const val JITTER_LEVEL_BASE = 0.5f
        private const val JITTER_LEVEL_SPAN = 1f

        private val JITTER_COLOR = Color(190, 130, 255, 75)
        private val JITTER_UNDER_COLOR = Color(190, 130, 255, 155)

        private const val CHARGE_KEY = "astd_grav_storm_charge:"
        internal const val CHARGE_PROGRESS_KEY = "astd_grav_storm_charge_progress"
        private const val ACTIVATION_KEY = "astd_grav_storm_activation:"

        private fun chargeKey(ship: ShipAPI): String = "$CHARGE_KEY${ship.id}"
        private fun activationKey(ship: ShipAPI): String = "$ACTIVATION_KEY${ship.id}"
    }
}
