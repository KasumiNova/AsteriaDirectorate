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

/**
 * 引力磁暴发生器（Gravity Storm Generator，系统 id：astd_grav_storm）——密蒙级（ZW-002）舰船系统。
 * 原版「量子干扰」（acausaldisruptor / [com.fs.starfarer.api.impl.combat.AcausalDisruptorStats]）的增强基线。
 *
 * 状态机（.system 建议口径：toggle=true + in 4s / out 1.5s / cooldown 24s，见装配侧接线说明）：
 * - **IN（充能，≤[GravStormTuning.MAX_CHARGE_SECONDS]s）**：首帧计入舰船基础最大辐能容量
 *   [GravStormTuning.ACTIVATION_FLUX_FRACTION] 的软辐能代价；充能期间紫色 jitter（幅度随充能进度）
 *   + 锥状射程描边选框（[GravStormConeIndicator]）+ 全类型伤害减免
 *   + 装饰性电弧（纯视觉，每波数量 2→8、间隔 0.5s→0.1s 随充能进度渐变，
 *   与空放装饰电弧同一生成逻辑）+ 充能进度写入 ship.customData [CHARGE_PROGRESS_KEY]
 *   （引力电磁力场读取后波形光斑反向聚集加速）。
 *   - 充能不足 [GravStormTuning.PHASE_LOCKOUT_SECONDS] 期间相位系统锁定：每帧把相位 cloak
 *     压入 COOLDOWN 并钉住小余量（原版 ChargeTracker 实证该态按键不激活），玩家按相位键无效；
 *   - 锁定解除后充能期间进入相位 → [ShipSystemAPI.deactivate] 直接进冷却（不释放电弧）；
 *   - 玩家再次按键（toggle 系统原版路径：IN 再按 → OUT，OUT 计时按充能进度折算）→ 提前结束：
 *     充能 ≥ [GravStormTuning.MIN_CHARGE_SECONDS] 释放，不足则视为取消（deactivate 进冷却）；
 *   - 充满 4s 自然进入 ACTIVE，首帧释放并 [ShipSystemAPI.forceState] 归位完整释放窗口。
 * - **OUT（释放窗口 [GravStormTuning.RELEASE_WINDOW_SECONDS]s）**：锁定充能结束时前方 60° 锥
 *   （射程 [GravStormTuning.BASE_RANGE] 经 systemRangeBonus 折算）内全部敌对舰船（含相位单位
 *   与战机——战机按护卫舰档结算电弧数与过载、单发伤害经 [GravStormTuning.FIGHTER_DAMAGE_MULT]
 *   折算为 50%，不含残骸），释放瞬间对全部锁定目标一次性打出紫色 EMP 电弧
 *   （spawnEmpArc 不穿盾口径：中盾只吃能量伤害；落点随机取目标武器/引擎槽位附近；
 *   发射点为母舰真实碰撞箱随机边缘），并在释放瞬间施加按舰级 × 充能占比插值的强制过载；
 *   过载触发硬辐能→软辐能转化（[GravStormTuning.hardToSoftAmount]：按目标舰级每 1s 过载
 *   将当前硬辐能 0.25%~4% 转为等额软辐能，总辐能不变）；
 *   锥内无锁定目标时改为向前方 60° 锥发射一批装饰性电弧（spawnEmpArcVisual 纯视觉，无伤害）。
 *   释放窗口存续期即伤害减免存续期（电弧已全部打出，窗口内不再追加）。
 * - **unapply**：解除伤害减免、描边选框收口、清理激活态。
 *
 * 目标锁定后不再跟踪（电弧朝释放时锁定的目标实体一次性打出；目标在释放瞬间已消亡则其电弧跳过）。
 * 超载色收口由 [OverloadColorResetPlugin] 托管（对齐原版量子干扰的 overloadColor 复原路径）。
 *
 * Fail Fast：战斗内 engine/ship API 均为安全访问，全程不包 try；BoxUtil 实体注册失败有 WARN 日志。
 */
class GravStormSystemStats : BaseShipSystemScript() {

    /** 充能期状态（IN 首帧建立，释放/取消/unapply 清除）。 */
    private class ChargeState(
        val indicator: GravStormConeIndicator?,
    ) {
        /**
         * IN 每帧记录的充能进度（0-1）。提前释放强度结算只读此自持进度：
         * toggle 系统 IN 再按转 OUT 时 OUT 计时按充能进度折算，OUT 首帧 effectLevel
         * 已是释放窗口口径，不可当作充能进度使用。
         */
        var chargeProgress: Float = 0f

        /** 充能装饰电弧节拍计时器（间隔随充能进度渐变，见 [GravStormTuning.chargeArcInterval]）。 */
        var arcTimer: Float = 0f
    }

    /** 单目标打击计划：电弧数（多目标衰减后）、单发伤害系数（战机折算）与强制过载时长；电弧在释放瞬间一次性全部打出。 */
    private class TargetPlan(
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
            ShipSystemStatsScript.State.IN -> chargeTick(stats, engine, ship, system, id, effectLevel)
            ShipSystemStatsScript.State.ACTIVE -> {
                // 充满 4s 自然落入 ACTIVE（toggle 口径下 active 段无限长）：立即满充能释放并归位释放窗口；
                // activation 闩防 ACTIVE 段多帧重复触发释放
                if (engine.customData[activationKey(ship)] == null) {
                    release(engine, ship, GravStormTuning.MAX_CHARGE_SECONDS)
                }
                system.forceState(ShipSystemAPI.SystemState.OUT, 0f)
            }
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

    /**
     * IN 每帧：首帧闩建立充能态（软辐能代价 + 描边选框 attach）；充能不足
     * [GravStormTuning.PHASE_LOCKOUT_SECONDS] 期间锁定相位系统（每帧把相位 cloak
     * 压入 COOLDOWN 并钉住小余量，玩家按键无效）；锁定解除后进入相位则打断充能进冷却；
     * jitter 与描边选框随充能进度增强。
     */
    private fun chargeTick(
        stats: MutableShipStatsAPI,
        engine: CombatEngineAPI,
        ship: ShipAPI,
        system: ShipSystemAPI,
        id: String,
        effectLevel: Float,
    ) {
        val key = chargeKey(ship)
        var charge = engine.customData[key] as? ChargeState
        if (charge == null) {
            applyActivationFluxCost(stats, ship)
            charge = ChargeState(GravStormConeIndicator.attach(engine, ship))
            engine.customData[key] = charge
        }

        val progress = effectLevel.coerceIn(0f, 1f)
        val chargeSeconds = progress * GravStormTuning.MAX_CHARGE_SECONDS
        if (GravStormTuning.phaseLockoutActive(chargeSeconds)) {
            // 充能前段相位锁定：相位 cloak 不可激活（每帧归位 IDLE），玩家按键无效
            suppressPhaseCloak(ship)
        } else if (ship.isPhased) {
            // 锁定解除后充能期间进入相位：充能结束、不释放电弧、直接进冷却（deactivate → forceDeactivate → COOLDOWN）
            disposeCharge(engine, ship)
            system.deactivate()
            return
        }

        charge.chargeProgress = progress
        // 充能进度共享给引力电磁力场（波形光斑反向聚集加速）；释放/取消时随充能态一并清除
        ship.setCustomData(CHARGE_PROGRESS_KEY, progress)
        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravStormTuning.BASE_RANGE)
        charge.indicator?.update(range, CONE_ALPHA_BASE + CONE_ALPHA_SPAN * progress)
        driveChargeArcs(engine, ship, charge, progress, range)

        ship.setJitter(id, JITTER_COLOR, JITTER_LEVEL_BASE + JITTER_LEVEL_SPAN * progress, 4, 0f, 2f + 6f * progress)
        ship.setJitterUnder(id, JITTER_UNDER_COLOR, JITTER_LEVEL_BASE + JITTER_LEVEL_SPAN * progress, 20, 0f, 4f + 8f * progress)
    }

    /**
     * 充能装饰电弧（纯视觉）：节拍间隔随充能进度 0.5s→0.1s 缩短，每波数量 2→8 增多
     * （[GravStormTuning.chargeArcInterval]/[GravStormTuning.chargeArcCount]）；
     * 单道生成逻辑与空放装饰电弧共用（[spawnDecorativeArc]）。
     */
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

    /**
     * 相位锁定（充能前段 [GravStormTuning.PHASE_LOCKOUT_SECONDS] 口径）：每帧把相位 cloak
     * 剩余冷却钉为 [GravStormTuning.PHASE_LOCKOUT_COOLDOWN_REMAINING]——
     * ShipSystemAPI.setCooldownRemaining 一步置 COOLDOWN 态（原版 ChargeTracker.startCooldown
     * 实证），COOLDOWN 态下玩家按键仅置失败标记不再激活，相位脚本 unapply 即撤 phased；
     * cloak 已处于更长的自然冷却时不改写（不缩短既有冷却）。
     */
    private fun suppressPhaseCloak(ship: ShipAPI) {
        val cloak = ship.phaseCloak ?: return
        if (cloak.state != ShipSystemAPI.SystemState.COOLDOWN ||
            cloak.cooldownRemaining < GravStormTuning.PHASE_LOCKOUT_COOLDOWN_REMAINING
        ) {
            cloak.cooldownRemaining = GravStormTuning.PHASE_LOCKOUT_COOLDOWN_REMAINING
        }
    }

    /**
     * OUT 每帧：首帧（释放闩缺席）为玩家提前结束路径——释放强度只读充能态自持的充能进度
     * （OUT 首帧 effectLevel 已是折算后释放窗口口径，不可当作充能进度）；达到最小充能则
     * 释放并归位完整释放窗口，不足或充能态缺席则取消进冷却。电弧在释放瞬间已一次性打出，
     * 后续帧无追加结算，窗口存续期仅作伤害减免存续。
     */
    private fun releaseTick(engine: CombatEngineAPI, ship: ShipAPI, system: ShipSystemAPI) {
        if (engine.customData[activationKey(ship)] != null) return
        val charge = engine.customData[chargeKey(ship)] as? ChargeState
        val chargeSeconds = (charge?.chargeProgress ?: 0f) * GravStormTuning.MAX_CHARGE_SECONDS
        if (chargeSeconds >= GravStormTuning.MIN_CHARGE_SECONDS) {
            release(engine, ship, chargeSeconds)
            system.forceState(ShipSystemAPI.SystemState.OUT, 0f)
        } else {
            // 最小充能不足：结束充能且不放电弧，仍进正常冷却
            disposeCharge(engine, ship)
            system.deactivate()
        }
    }

    /**
     * 释放：锁定锥内敌对目标、按舰级与目标数结算电弧计划并对全部锁定目标一次性打出电弧、
     * 施加强制过载、播放扩散扭曲与释放音效、描边选框收口；锥内无锁定目标时改为装饰性电弧齐射。
     */
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
                // 战机目标：电弧数与过载对齐护卫舰档，单发伤害折算 50%
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

        // 释放闩：OUT 窗口存续期在场（自动化取证与 releaseTick 首帧判定共用），OUT 结束/unapply 清除
        engine.customData[activationKey(ship)] = true
    }

    /** 锥状锁定：前方 60° 锥、有效射程内（计目标碰撞半径余量）的敌对舰船；含相位单位与战机，不含残骸。 */
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

    /**
     * 空放装饰电弧（锥内无锁定目标）：向前方 60° 锥内发射
     * [DECOR_ARC_COUNT_MIN]~[DECOR_ARC_COUNT_MAX] 道纯视觉电弧（单道生成见 [spawnDecorativeArc]）。
     */
    private fun spawnDecorativeArcs(engine: CombatEngineAPI, ship: ShipAPI, range: Float) {
        val count = MathUtils.getRandomNumberInRange(DECOR_ARC_COUNT_MIN, DECOR_ARC_COUNT_MAX)
        repeat(count) { spawnDecorativeArc(engine, ship, range) }
    }

    /**
     * 单道装饰电弧（纯视觉，无伤害结算；空放齐射与充能期渐变波共用）：
     * 发射点为母舰碰撞箱随机边缘，落点在射程内随机
     * （[DECOR_ARC_DIST_MIN_FRACTION]~[DECOR_ARC_DIST_MAX_FRACTION] × 有效射程，前方 60° 锥内）。
     */
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

    /** 强制过载：释放瞬间施加；已过载/排气目标跳过（对齐原版量子干扰口径），紫色过载色由插件托管复原。过载生效的同时结算硬辐能→软辐能转化（[convertHardToSoft]）。 */
    private fun applyOverloads(engine: CombatEngineAPI, ship: ShipAPI, plans: List<TargetPlan>) {
        val tracked = ArrayList<ShipAPI>(plans.size)
        for (plan in plans) {
            val target = plan.target
            if (!target.isAlive || target.isHulk) continue
            if (target.fluxTracker.isOverloadedOrVenting) continue
            target.setOverloadColor(OVERLOAD_COLOR)
            target.fluxTracker.beginOverloadWithTotalBaseDuration(plan.overloadSeconds)
            convertHardToSoft(target, plan.overloadSeconds)
            if (target.fluxTracker.showFloaty() || ship === engine.playerShip || target === engine.playerShip) {
                target.fluxTracker.playOverloadSound()
                target.fluxTracker.showOverloadFloatyIfNeeded(
                    I18n[I18n.Categories.MOD, "ui.grav_storm.overload_floaty"],
                    OVERLOAD_COLOR, 4f, true,
                )
            }
            tracked += target
        }
        if (tracked.isNotEmpty()) {
            engine.addPlugin(OverloadColorResetPlugin(engine, tracked))
        }
    }

    /**
     * 硬辐能→软辐能转化：目标每承受 1s 过载，按舰级比例（[GravStormTuning.hardToSoftPerSecond]）
     * 将其当前硬辐能的一部分转为等额软辐能——hardFlux -= x，总辐能不变则软辐能自动 +x
     * （设计意图：不降低当前总辐能，迫使敌方靠耗散/过载处理转化出的软辐能）。
     * 只需 setHardFlux 即完成等额转化：setHardFlux 不动 currFlux，软辐能 = currFlux − hardFlux
     * 自动 +x；不得再调 increaseFlux——其第二参 true 加的是硬辐能且总辐能 +x，与转化语义相反。
     */
    internal fun convertHardToSoft(target: ShipAPI, overloadSeconds: Float) {
        val tracker = target.fluxTracker
        val amount = GravStormTuning.hardToSoftAmount(tracker.hardFlux, target.hullSize, overloadSeconds)
        if (amount <= 0f) return
        tracker.hardFlux = tracker.hardFlux - amount
    }

    /** 单发电弧：母舰碰撞箱随机边缘 → 目标武器/引擎槽位附近随机点；不穿盾（中盾只吃能量伤害）。伤害经 [TargetPlan.damageMult] 折算（战机目标为护卫舰档 50%）。 */
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

    /** 电弧落点：目标全部武器槽位与引擎槽位中随机取一点 + 小散布；均无则取舰体周边随机点。 */
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

    /** 释放瞬间：以舰体为中心向外扩散的圆形扭曲（时序 0.5s 淡入 / 0.5s 满值 / 0.5s 淡出）。 */
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

    /** 激活代价：基础最大辐能容量 [GravStormTuning.ACTIVATION_FLUX_FRACTION] 的软辐能。 */
    private fun applyActivationFluxCost(stats: MutableShipStatsAPI, ship: ShipAPI) {
        ship.fluxTracker.increaseFlux(stats.fluxCapacity.baseValue * GravStormTuning.ACTIVATION_FLUX_FRACTION, false)
    }

    /** 充能态收口：描边选框 dispose + 移除首帧闩与充能进度共享键（释放/取消/相位打断/unapply 共用）。 */
    private fun disposeCharge(engine: CombatEngineAPI, ship: ShipAPI) {
        ship.removeCustomData(CHARGE_PROGRESS_KEY)
        val charge = engine.customData.remove(chargeKey(ship)) as? ChargeState ?: return
        charge.indicator?.dispose()
    }

    /** 舰体真实碰撞箱随机边缘点（exactBounds 随机段上插值；无碰撞箱时按碰撞半径近似）。 */
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
        // 相位锁定提示行：充能前段（不足 PHASE_LOCKOUT_SECONDS）期间维持，提示玩家相位系统被抑制
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

    /** 过载色复原托管：目标退出过载/消亡即复原其过载色，全部复原后自移除（对齐原版量子干扰路径）。 */
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

        /** 释放音效：模组 sounds.json 单变体池 astd_grav_storm_release（钉死原版 shields_burnout_dweller.ogg 采样，避免原版 shield_burnout 池随机到另一变体）。 */
        private const val RELEASE_SOUND_ID = "astd_grav_storm_release"

        /** 电弧命中音效（原版 EMP 发射器命中音）。 */
        private const val ARC_IMPACT_SOUND_ID = "system_emp_emitter_impact"

        /** 电弧配色（透镜协议紫）。 */
        private val ARC_FRINGE = Color(186, 120, 255, 200)
        private val ARC_CORE = Color(238, 215, 255, 160)

        /** 强制过载配色（对齐原版量子干扰 OVERLOAD_COLOR 的品红系，偏透镜紫）。 */
        private val OVERLOAD_COLOR = Color(215, 130, 255, 255)

        /** 电弧厚度（su）。 */
        private const val ARC_THICKNESS = 7f

        /** 电弧落点小散布半径（su）。 */
        private const val SLOT_POINT_SCATTER = 15f

        /** 空放装饰电弧：数量区间、落点距离占有效射程的比例区间、厚度（su）。 */
        private const val DECOR_ARC_COUNT_MIN = 8
        private const val DECOR_ARC_COUNT_MAX = 14
        private const val DECOR_ARC_DIST_MIN_FRACTION = 0.3f
        private const val DECOR_ARC_DIST_MAX_FRACTION = 0.9f
        private const val DECOR_ARC_THICKNESS = 7f

        /** 描边选框 alpha：基底 + 充能进度增量（描边实体侧再乘基准透明度，整体低于旧填充贴图口径）。 */
        private const val CONE_ALPHA_BASE = 0.15f
        private const val CONE_ALPHA_SPAN = 0.35f

        /** 充能 jitter 强度：基底 + 充能进度增量。 */
        private const val JITTER_LEVEL_BASE = 0.25f
        private const val JITTER_LEVEL_SPAN = 0.75f

        private val JITTER_COLOR = Color(190, 130, 255, 75)
        private val JITTER_UNDER_COLOR = Color(190, 130, 255, 155)

        /** 充能态 customData 键前缀（每船一条，释放/取消/unapply 清除）。 */
        private const val CHARGE_KEY = "astd_grav_storm_charge:"

        /**
         * 充能进度共享键（ship.customData，IN 每帧写入 0-1，释放/取消/unapply 随充能态清除）：
         * 引力电磁力场（GravEmFieldHullMod）读取后驱动波形光斑反向聚集加速。
         */
        internal const val CHARGE_PROGRESS_KEY = "astd_grav_storm_charge_progress"

        /** 激活态 customData 键前缀（每船一条，OUT 结束/unapply 清除）。 */
        private const val ACTIVATION_KEY = "astd_grav_storm_activation:"

        private fun chargeKey(ship: ShipAPI): String = "$CHARGE_KEY${ship.id}"
        private fun activationKey(ship: ShipAPI): String = "$ACTIVATION_KEY${ship.id}"
    }
}
