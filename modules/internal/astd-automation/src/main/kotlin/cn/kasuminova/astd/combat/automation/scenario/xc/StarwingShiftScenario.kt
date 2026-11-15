package cn.kasuminova.astd.combat.automation.scenario.xc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.XC2_STAGE_MOD_ID
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingShot
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.impl.combat.DcrDamageAttribution
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import data.scripts.combatanalytics.damagedetection.EveryFrameDamageDetector
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.roundToInt

class StarwingShiftScenario : AbstractAutomationScenario() {
    private var xc2Phase = XC2_PHASE_SPAWN
    private var xc2PhaseStartedAt = 0f

    private var xc2ActivateAttempts = 0
    private var xc2ActivatedAt = -1f
    private var xc2ShiftDisplacementMax = 0f
    private var xc2DisplacementAsserted = false
    private var xc2ClearDisplacementMax = 0f
    private var xc2ClearHpLoss = 0f
    private var xc2ClearWindowLogged = false

    private var xc2ExchangeArmed = false
    private var xc2ChargeBeforeExchange = -1f
    private var xc2ChargeDropAtExchange = -1f
    private var xc2AmmoBeforeExchange = -1

    private var xc2ShuttleSeen = 0
    private val xc2ShuttleIdentities = mutableSetOf<Int>()
    private val xc2ShuttleTypes = linkedSetOf<StarfallWingTuning.ShuttleType>()
    private var xc2PowerMin = Float.MAX_VALUE
    private var xc2PowerMax = -Float.MAX_VALUE
    private var xc2SpeedRatioMin = Float.MAX_VALUE
    private var xc2SpeedRatioMax = -Float.MAX_VALUE
    private var xc2SpeedSamples = 0
    private var xc2RangeMismatch = 0
    private var xc2MissileTypeViolations = 0
    private var xc2CollisionClassViolations = 0
    private var xc2DamageTypeMismatch = 0
    private val xc2ShuttleFlux = HashMap<StarfallWingTuning.ShuttleType, Float>()
    private var xc2DamagePerFactorPowerMin = Float.MAX_VALUE
    private var xc2DamagePerFactorPowerMax = -Float.MAX_VALUE
    private var xc2BlueEmpRatioViolations = 0
    private var xc2EmpProbe: Xc2EmpProbe? = null
    private var xc2EmpDirectHits = 0
    private var xc2EmpArcTotal = 0
    private var xc2EmpArcMaxBurst = 0
    private var xc2EmpArcMinBurst = Int.MAX_VALUE
    private var xc2EmpBurstCurrent = 0
    private var xc2EmpBurstPower = -1f
    private var xc2EmpBurstViolations = 0
    private var xc2EmpBurstsClosed = 0
    private var xc2EmpShieldHitEvents = 0
    private var xc2EnemyShieldOnFrames = 0
    private var xc2PlayerShieldOnBeforeShift = false
    private var xc2PlayerShieldForcedOff = false
    private var xc2HitChargeJumpSeen = false
    private var xc2ChargePrevFrame = -1f

    private var xc2CoastBaseline: Set<Int> = emptySet()
    private var xc2CoastProjectile: DamagingProjectileAPI? = null
    private var xc2CoastLaunchedAt = -1f
    private var xc2CoastLaunchOrigin: Vector2f? = null
    private var xc2CoastLaunchFacing = 0f
    private var xc2CoastFadeAt = -1f
    private var xc2CoastFadeLocation: Vector2f? = null
    private var xc2CoastLastLocation: Vector2f? = null
    private var xc2CoastRangeRatio = -1f
    private var xc2CoastLateralMax = 0f
    private var xc2CoastMinFadeSpeed = Float.MAX_VALUE
    private var xc2CoastEvaluated = false
    private var xc2DcrEvaluated = false
    private var xc2CoastMissile: MissileAPI? = null
    private var xc2CoastMissileContacts = 0

    private var xc2WeaponDamageAccum = 0f
    private var xc2EnemyHpPrevFrame = -1f
    private var xc2WeaponStageBuffed = false
    private var xc2WeaponDiagLogged = false
    private var xc2ImpactProbe: StarwingImpactProbe? = null
    private var xc2TargetProbe: StarwingTargetProbe? = null

    override val scenarioId: String = ASTDInGameAutomationScenario.XC002_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isXc002Enabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockXc2Camera(engine)
        // reserves 部署放在 advance：init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findXc2Player(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.XC002_SCENARIO_ID} combat plugin initialized")
    }

    private fun findXc2Player(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == XC2_PLAYER_HULL && !ship.isFighter }

    private fun findXc2Enemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == XC2_ENEMY_HULL && !ship.isFighter }

    private fun deployXc2ReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == XC2_PLAYER_HULL -> XC2_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == XC2_ENEMY_HULL -> XC2_CLEAR_ENEMY_ANCHOR
                    else -> continue
                }
                if (findShipByHull(engine, member.hullId) != null) {
                    manager.removeFromReserves(member)
                    continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionXc2Phase(next: String) {
        if (xc2Phase == XC2_PHASE_COAST || next == XC2_PHASE_COAST) {
            findXc2Enemy(ctx.engine)?.isPhased = next == XC2_PHASE_COAST
        }
        ctx.log.info("[ASTD-Automation] xc2 phase $xc2Phase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        if (next == XC2_PHASE_FAILED) {
            ctx.log.info("[ASTD-Automation] xc2 FAILED reason: ${ctx.failureReason}")
        }
        xc2Phase = next
        xc2PhaseStartedAt = ctx.elapsed
    }

    private fun stabilizeXc2Ships(
        engine: CombatEngineAPI,
        pinPlayerAt: Vector2f?,
        playerFacing: Float,
        blockSystem: Boolean,
        healEnemy: Boolean,
        allowFire: Boolean,
        enemyAnchor: Vector2f = XC2_CLEAR_ENEMY_ANCHOR,
        enemyShieldUp: Boolean = false,
        preservePlayerAI: Boolean = false,
        pinEnemy: Boolean = true,
    ) {
        val player = findXc2Player(engine)
        val enemy = findXc2Enemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            if (pinPlayerAt != null) {
                // 无舰船 AI 时强制开火不生效；默认 AI 要求非空配置。
                if (preservePlayerAI && player.shipAI == null) {
                    player.shipAI = Global.getSettings().createDefaultShipAI(player, ShipAIConfig())
                }
                stabilizeShip(player, pinPlayerAt, playerFacing, allowFire = allowFire, preserveAI = preservePlayerAI)
            } else {
                player.shipAI = null
                player.shipTarget = null
                player.setControlsLocked(false)
                player.isHoldFireOneFrame = !allowFire
                player.blockCommandForOneFrame(ShipCommand.ACCELERATE)
                player.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS)
                player.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT)
                player.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT)
                player.blockCommandForOneFrame(ShipCommand.TURN_LEFT)
                player.blockCommandForOneFrame(ShipCommand.TURN_RIGHT)
                if (!allowFire) player.blockCommandForOneFrame(ShipCommand.FIRE)
            }
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            player.fluxTracker.hardFlux = 0f
            if (blockSystem) player.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
            player.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
        }
        if (enemy != null && !enemy.isHulk) {
            if (pinEnemy) {
                stabilizeShip(enemy, enemyAnchor, 180f, allowFire = false, preserveAI = false)
                if (healEnemy) enemy.hitpoints = enemy.maxHitpoints
                enemy.fluxTracker.currFlux = 0f
                enemy.fluxTracker.hardFlux = 0f
            } else {
                enemy.shipAI = null
                enemy.shipTarget = null
                enemy.isHoldFireOneFrame = true
            }
            if (enemyShieldUp) {
                enemy.shield?.let { if (!it.isOn) it.toggleOn() }
            } else {
                enemy.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
                enemy.shield?.let { if (it.isOn) it.toggleOff() }
            }
        }
    }

    private fun lockXc2Camera(engine: CombatEngineAPI) {
        lockCameraAt(engine, XC2_CAMERA_CENTER, XC2_CAMERA_VISIBLE_HEIGHT)
    }

    private fun raiseXc2PlayerShield(player: ShipAPI?) {
        val shield = player?.shield ?: return
        if (!shield.isOn) shield.toggleOn()
    }

    private fun closeXc2EmpBurst() {
        if (xc2EmpBurstPower >= 0f) {
            val minimum = (DifficultyTuningImpl.valueFor(StarfallWingTuning.ARC_MIN, true) * xc2EmpBurstPower).roundToInt()
            val maximum = (DifficultyTuningImpl.valueFor(StarfallWingTuning.ARC_MAX, true) * xc2EmpBurstPower).roundToInt()
            if (xc2EmpBurstCurrent !in minimum..maximum) xc2EmpBurstViolations++
            xc2EmpBurstsClosed++
            xc2EmpArcMinBurst = minOf(xc2EmpArcMinBurst, xc2EmpBurstCurrent)
            xc2EmpArcMaxBurst = maxOf(xc2EmpArcMaxBurst, xc2EmpBurstCurrent)
            ctx.log.info("[ASTD-Automation] XC2_EMP_POWER_BURST power=$xc2EmpBurstPower arcs=$xc2EmpBurstCurrent expectedArcs=[$minimum,$maximum] violations=$xc2EmpBurstViolations")
        }
        xc2EmpBurstCurrent = 0
        xc2EmpBurstPower = -1f
    }

    private fun trackXc2Shuttles(engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.projectiles.isEmpty()) return
        for (proj in engine.projectiles) {
            if (proj.source?.owner != 0) continue
            val shot = proj.customData[StarfallWingTuning.SHOT_STATE_KEY] as? StarfallWingShot ?: continue
            if (xc2ShuttleIdentities.add(System.identityHashCode(proj))) {
                xc2ShuttleSeen++
                xc2ShuttleTypes.add(shot.type)
                xc2PowerMin = minOf(xc2PowerMin, shot.power)
                xc2PowerMax = maxOf(xc2PowerMax, shot.power)
                xc2ShuttleFlux[shot.type] = maxOf(xc2ShuttleFlux[shot.type] ?: 0f, proj.damage.fluxComponent)
                if (proj is MissileAPI) xc2MissileTypeViolations++
                if (proj.collisionClass != CollisionClass.PROJECTILE_NO_FF) xc2CollisionClassViolations++
                if (proj.damage.type != shot.type.damageType) xc2DamageTypeMismatch++
                if (Math.abs(shot.range - weapon.range) > XC2_RANGE_TOLERANCE) xc2RangeMismatch++
                if (shot.type == StarfallWingTuning.ShuttleType.BLUE &&
                    Math.abs(proj.damage.fluxComponent - proj.damageAmount * XC2_BLUE_EMP_RATIO) >
                    proj.damageAmount * XC2_BLUE_EMP_RATIO_TOLERANCE
                ) {
                    xc2BlueEmpRatioViolations++
                }
                val damagePerFactorPower = proj.damageAmount / (shot.power * shot.type.damageFactor)
                if (damagePerFactorPower.isFinite() && damagePerFactorPower > 0f) {
                    xc2DamagePerFactorPowerMin = minOf(xc2DamagePerFactorPowerMin, damagePerFactorPower)
                    xc2DamagePerFactorPowerMax = maxOf(xc2DamagePerFactorPowerMax, damagePerFactorPower)
                }
            }
            val travelled = shot.travelled
            if (proj.isFading || travelled < shot.range * XC2_SPEED_SAMPLE_MIN_FRACTION ||
                travelled > shot.range * XC2_SPEED_SAMPLE_MAX_FRACTION
            ) continue
            val ratio = proj.velocity.length() / StarfallWingTuning.speedFactor(shot.power)
            if (!ratio.isFinite() || ratio <= 0f) continue
            xc2SpeedRatioMin = minOf(xc2SpeedRatioMin, ratio)
            xc2SpeedRatioMax = maxOf(xc2SpeedRatioMax, ratio)
            xc2SpeedSamples++
        }
    }

    private fun trackXc2HitCharge(pool: ImaginaryCharge, hitGain: Float) {
        val charge = pool.charge
        if (xc2ChargePrevFrame >= 0f && charge - xc2ChargePrevFrame >= hitGain * XC2_HIT_CHARGE_JUMP_FRACTION) {
            xc2HitChargeJumpSeen = true
        }
        xc2ChargePrevFrame = charge
    }

    private fun accumulateXc2WeaponDamage(enemy: ShipAPI) {
        if (!xc2WeaponStageBuffed || enemy.isHulk) return
        val hpNow = enemy.hitpoints
        if (xc2EnemyHpPrevFrame >= 0f && hpNow < xc2EnemyHpPrevFrame) {
            xc2WeaponDamageAccum += xc2EnemyHpPrevFrame - hpNow
        }
        enemy.hitpoints = enemy.maxHitpoints
        xc2EnemyHpPrevFrame = enemy.hitpoints
    }

    private fun stageXc2ShuttleTarget(enemy: ShipAPI) {
        enemy.armorGrid?.grid?.forEach { row -> row.fill(0f) }
        enemy.mutableStats.hullBonus.modifyFlat(XC2_STAGE_MOD_ID, XC2_STAGE_ENEMY_HULL_BUFFER)
        enemy.hitpoints = enemy.maxHitpoints
        xc2EnemyHpPrevFrame = enemy.hitpoints
        xc2WeaponDamageAccum = 0f
        xc2WeaponStageBuffed = true
    }

    private fun findCoastShuttle(engine: CombatEngineAPI): DamagingProjectileAPI? =
        engine.projectiles.firstOrNull { proj ->
            proj.source?.owner == 0 &&
                proj.customData[StarfallWingTuning.SHOT_STATE_KEY] is StarfallWingShot &&
                System.identityHashCode(proj) !in xc2CoastBaseline
        }

    private fun trackCoast(proj: DamagingProjectileAPI, ctx: AutomationCombatContext) {
        val origin = xc2CoastLaunchOrigin ?: return
        val shot = proj.customData[StarfallWingTuning.SHOT_STATE_KEY] as? StarfallWingShot ?: return
        if (proj.isFading && xc2CoastFadeAt < 0f) {
            xc2CoastFadeAt = ctx.elapsed
            xc2CoastFadeLocation = Vector2f(proj.location)
            xc2CoastRangeRatio = shot.travelled / shot.range
            ctx.log.info(
                "[ASTD-Automation] xc2 coast fade begin: afterLaunch=${"%.2f".format(ctx.elapsed - xc2CoastLaunchedAt)}s " +
                        "loc=(${"%.0f".format(proj.location.x)}, ${"%.0f".format(proj.location.y)}) " +
                        "rangeRatio=${"%.3f".format(xc2CoastRangeRatio)}",
            )
        }
        // 原版退出时 location 仍可读，但下一帧实体即失效；最后有效位置必须逐帧留存。
        xc2CoastLastLocation = Vector2f(proj.location)
        val rad = Math.toRadians(xc2CoastLaunchFacing.toDouble())
        val dx = proj.location.x - origin.x
        val dy = proj.location.y - origin.y
        val lateral = Math.abs(dx * -Math.sin(rad) + dy * Math.cos(rad)).toFloat()
        xc2CoastLateralMax = maxOf(xc2CoastLateralMax, lateral)
        if (xc2CoastFadeAt >= 0f) {
            xc2CoastMinFadeSpeed = minOf(xc2CoastMinFadeSpeed, proj.velocity.length())
        }
    }

    private fun driveCoastMissile(
        engine: CombatEngineAPI,
        enemy: ShipAPI,
        coaster: DamagingProjectileAPI,
        amount: Float,
    ): MissileAPI? {
        var missile = xc2CoastMissile
        if (missile == null || !engine.isEntityInPlay(missile)) {
            val spawned = engine.spawnProjectile(
                enemy, null, XC2_COAST_MISSILE_WEAPON,
                Vector2f(coaster.location), coaster.facing, Vector2f(),
            ) as? MissileAPI
            if (spawned == null) {
                ctx.log.info("[ASTD-Automation] xc2 coast missile spawn failed: weapon=$XC2_COAST_MISSILE_WEAPON")
                return null
            }
            spawned.missileAI = NoOpMissileAi
            spawned.collisionClass = CollisionClass.MISSILE_NO_FF
            spawned.collisionRadius = 40f
            spawned.armingTime = 0f
            xc2CoastMissile = spawned
            ctx.log.info("[ASTD-Automation] xc2 coast missile spawned at coaster head")
            missile = spawned
        }
        if (coaster.isFading) return missile
        val step = coaster.velocity.length() * amount
        val dir = Vector2f(coaster.velocity)
        if (dir.lengthSquared() <= 1e-4f) {
            val rad = Math.toRadians(coaster.facing.toDouble())
            dir.set(Math.cos(rad).toFloat(), Math.sin(rad).toFloat())
        } else {
            dir.normalise()
        }
        val target = Vector2f(
            coaster.location.x + dir.x * (step * XC2_COAST_MISSILE_LEAD),
            coaster.location.y + dir.y * (step * XC2_COAST_MISSILE_LEAD),
        )
        missile.location.set(target)
        missile.velocity.set(0f, 0f)
        missile.flightTime = 0f
        if (MathUtils.getDistance(coaster.location, target) <= coaster.collisionRadius + missile.collisionRadius) {
            xc2CoastMissileContacts++
        }
        return missile
    }

    private fun evaluateCoast(ctx: AutomationCombatContext, player: ShipAPI?, weapon: WeaponAPI?) {
        val fadeStart = xc2CoastFadeLocation
        if (xc2CoastFadeAt < 0f || fadeStart == null) {
            ctx.failureReason = "xc2 coast never faded: lateral=${"%.1f".format(xc2CoastLateralMax)}" +
                    "（星梭射程耗尽后应进入原版淡出而非直接移除）"
            transitionXc2Phase(XC2_PHASE_FAILED)
            return
        }
        val fadeSeconds = ctx.elapsed - xc2CoastFadeAt
        val last = xc2CoastLastLocation ?: fadeStart
        val coastDisplacement = MathUtils.getDistance(last, fadeStart)
        val minSpeed = if (xc2CoastMinFadeSpeed == Float.MAX_VALUE) -1f else xc2CoastMinFadeSpeed
        ctx.log.info(
            "[ASTD-Automation] xc2 coast evidence: fade=${"%.2f".format(fadeSeconds)}s " +
                    "coastDisplacement=${"%.0f".format(coastDisplacement)} " +
                    "lateral=${"%.1f".format(xc2CoastLateralMax)} minFadeSpeed=${"%.1f".format(minSpeed)} " +
                    "rangeRatio=${"%.3f".format(xc2CoastRangeRatio)}",
        )
        when {
            Math.abs(fadeSeconds - XC2_COAST_FADE_SECONDS) > XC2_COAST_FADE_TOLERANCE -> {
                ctx.failureReason = "xc2 coast fade=${"%.2f".format(fadeSeconds)}s ∉ " +
                        "$XC2_COAST_FADE_SECONDS ± $XC2_COAST_FADE_TOLERANCE（原版淡出时长）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            Math.abs(xc2CoastRangeRatio - 1f) > XC2_COAST_RANGE_RATIO_TOLERANCE -> {
                ctx.failureReason = "xc2 coast rangeRatio=${"%.3f".format(xc2CoastRangeRatio)} ∉ " +
                        "1 ± $XC2_COAST_RANGE_RATIO_TOLERANCE（淡出应在射程耗尽时触发，防载体寿命不对）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            coastDisplacement <= XC2_COAST_MIN_DISPLACEMENT -> {
                ctx.failureReason = "xc2 coast displacement=${"%.0f".format(coastDisplacement)}" +
                        " ≤ $XC2_COAST_MIN_DISPLACEMENT（淡出期星梭应继续滑行）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            minSpeed < XC2_COAST_MIN_FADE_SPEED -> {
                ctx.failureReason = "xc2 coast minFadeSpeed=${"%.1f".format(minSpeed)}" +
                        " < $XC2_COAST_MIN_FADE_SPEED（淡出期不应减速）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            xc2CoastMissileContacts < XC2_COAST_MIN_MISSILE_CONTACTS -> {
                ctx.failureReason = "xc2 coast missile contacts=$xc2CoastMissileContacts" +
                        " < $XC2_COAST_MIN_MISSILE_CONTACTS（未真正与敌导弹重叠，穿越未被验证）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            xc2CoastLateralMax <= XC2_COAST_MIN_LATERAL -> {
                ctx.failureReason = "xc2 coast lateral=${"%.1f".format(xc2CoastLateralMax)}" +
                        " ≤ $XC2_COAST_MIN_LATERAL（无目标时应有弧线摆动）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            else -> evaluateDcr(ctx, player, weapon)
        }
    }

    private fun evaluateDcr(ctx: AutomationCombatContext, player: ShipAPI?, weapon: WeaponAPI?) {
        if (xc2DcrEvaluated) return
        xc2DcrEvaluated = true
        if (ctx.engine.isMission) {
            ctx.log.info("[ASTD-Automation] xc2 DCR 不在任务战场采集（engine.isMission=true），跳过战报归属验收")
            transitionXc2Phase(XC2_PHASE_IMPACT)
            return
        }
        if (!DcrDamageAttribution.isAvailable()) {
            ctx.log.info("[ASTD-Automation] xc2 DCR 未启用，跳过战报归属验收")
            transitionXc2Phase(XC2_PHASE_IMPACT)
            return
        }
        val memberId = player?.fleetMemberId
        val weaponName = weapon?.displayName
        if (memberId == null || weaponName == null) {
            ctx.log.info("[ASTD-Automation] xc2 DCR 跳过：源舰或主炮缺失 memberId=$memberId weapon=$weaponName")
            transitionXc2Phase(XC2_PHASE_IMPACT)
            return
        }
        val report = Xc2DcrProbe.collect(memberId, weaponName)
        ctx.log.info(
            "[ASTD-Automation] xc2 dcr evidence: entries=${report.entryCount} own=${report.ownEntries} " +
                    "gunDamage=${"%.0f".format(report.gunTotal)} gunEmp=${"%.0f".format(report.gunEmp)} " +
                    "unknown=${report.unknownWeapons}",
        )
        when {
            report.ownEntries <= 0 -> {
                ctx.failureReason = "xc2 dcr 无源舰记录（memberId=$memberId）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            report.gunTotal <= 0f -> {
                ctx.failureReason = "xc2 dcr 主炮 $weaponName 无伤害记录（own=${report.ownEntries}）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            report.gunEmp <= 0f -> {
                ctx.failureReason = "xc2 dcr 主炮 $weaponName 无 EMP 记录（蓝色星梭的 EMP 未归入主炮）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            report.unknownWeapons > 0 -> {
                ctx.failureReason = "xc2 dcr 存在未归属记录 unknown=${report.unknownWeapons}" +
                        "（脚本伤害未被武器名澄清）"
                transitionXc2Phase(XC2_PHASE_FAILED)
            }

            else -> transitionXc2Phase(XC2_PHASE_IMPACT)
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployXc2ReserveShips(engine)
        lockXc2Camera(engine)

        val player = findXc2Player(engine)
        val enemy = findXc2Enemy(engine)
        val system = player?.system
        val weapon = player?.allWeapons?.firstOrNull { it.id == XC2_WEAPON_ID }

        when (xc2Phase) {
            XC2_PHASE_SPAWN -> {
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = true, allowFire = false,
                )
                raiseXc2PlayerShield(player)
                if (player != null && enemy != null && ctx.elapsed - xc2PhaseStartedAt >= XC2_SPAWN_SETTLE_SECONDS) {
                    when {
                        system == null || system.id != XC2_SYSTEM_ID -> {
                            ctx.failureReason = "xc2 system id=${system?.id}, expect $XC2_SYSTEM_ID（ship_systems.csv 生成物未刷新）"
                            transitionXc2Phase(XC2_PHASE_FAILED)
                        }

                        weapon == null -> {
                            ctx.failureReason = "xc2 starfall wing not found on xc_002（内置主炮槽位装配缺失）"
                            transitionXc2Phase(XC2_PHASE_FAILED)
                        }

                        player.variant?.hasHullMod(XC2_HULLMOD_ID) != true -> {
                            ctx.failureReason = "xc2 hullmod $XC2_HULLMOD_ID missing（.ship builtInMods 未生效）"
                            transitionXc2Phase(XC2_PHASE_FAILED)
                        }

                        else -> transitionXc2Phase(XC2_PHASE_SHIFT)
                    }
                }
            }

            XC2_PHASE_SHIFT -> {
                // 必须先检测激活，再恢复固定站位，否则会清除起跳推力。
                if (xc2ActivatedAt < 0f && player != null && system != null &&
                    system.state != ShipSystemAPI.SystemState.IDLE
                ) {
                    xc2ActivatedAt = ctx.elapsed
                    ctx.log.info(
                        "[ASTD-Automation] xc2 activated: state=${system.state} attempts=$xc2ActivateAttempts " +
                                "player=(${"%.1f".format(player.location.x)}, ${"%.1f".format(player.location.y)})" +
                                (
                                        if (enemy != null) {
                                            " enemy=(${"%.1f".format(enemy.location.x)}, ${"%.1f".format(enemy.location.y)})" +
                                                    " enemyVel=${"%.1f".format(enemy.velocity.length())}" +
                                                    " surfaceDist=${"%.1f".format(
                                                        MathUtils.getDistance(player.location, enemy.location) - enemy.collisionRadius,
                                                    )}"
                                        } else ""
                                        )
                    )
                }
                val sinceActivate = if (xc2ActivatedAt >= 0f) ctx.elapsed - xc2ActivatedAt else -1f
                val pinAt = when {
                    sinceActivate < 0f -> XC2_PLAYER_ANCHOR
                    sinceActivate <= XC2_SHIFT_PIN_FREE_SECONDS -> null
                    else -> XC2_PLAYER_ARRIVAL
                }
                val clearWindow = sinceActivate >= 0f && sinceActivate <= XC2_CLEAR_WINDOW_SECONDS
                stabilizeXc2Ships(
                    engine, pinPlayerAt = pinAt, playerFacing = 0f,
                    blockSystem = xc2ActivatedAt >= 0f, healEnemy = false, allowFire = false,
                    pinEnemy = !clearWindow,
                )
                if (player != null && enemy != null && system != null && weapon != null) {
                    val systemState = system.state
                    if (xc2ActivatedAt < 0f) raiseXc2PlayerShield(player)
                    if (xc2ActivatedAt < 0f && player.shield?.isOn == true) {
                        xc2PlayerShieldOnBeforeShift = true
                    }
                    if (xc2ActivatedAt >= 0f && xc2PlayerShieldOnBeforeShift && player.shield?.isOn != true) {
                        xc2PlayerShieldForcedOff = true
                    }
                    if (xc2ActivatedAt < 0f && systemState == ShipSystemAPI.SystemState.IDLE && system.cooldownRemaining <= 0f) {
                        xc2ActivateAttempts++
                        player.useSystem()
                    }
                    if (sinceActivate >= 0f) {
                        if (clearWindow && !xc2ClearWindowLogged) {
                            xc2ClearWindowLogged = true
                            ctx.log.info(
                                "[ASTD-Automation] xc2 clear window: enemy=(${"%.1f".format(enemy.location.x)}, " +
                                        "${"%.1f".format(enemy.location.y)}) vel=${"%.2f".format(enemy.velocity.length())} " +
                                        "distFromAnchor=${"%.2f".format(MathUtils.getDistance(enemy.location, XC2_CLEAR_ENEMY_ANCHOR))} " +
                                        "controlsLocked=${enemy.controlsLocked()} shipAI=${enemy.shipAI != null}",
                            )
                        }
                        xc2ShiftDisplacementMax = maxOf(
                            xc2ShiftDisplacementMax,
                            MathUtils.getDistance(player.location, XC2_PLAYER_ANCHOR),
                        )
                        if (clearWindow) {
                            xc2ClearDisplacementMax = maxOf(
                                xc2ClearDisplacementMax,
                                MathUtils.getDistance(enemy.location, XC2_CLEAR_ENEMY_ANCHOR),
                            )
                            xc2ClearHpLoss = maxOf(xc2ClearHpLoss, enemy.maxHitpoints - enemy.hitpoints)
                        }
                        if (!xc2DisplacementAsserted && sinceActivate >= XC2_SHIFT_ASSERT_SECONDS) {
                            xc2DisplacementAsserted = true
                            when {
                                xc2ShiftDisplacementMax !in XC2_SHIFT_DISPLACEMENT_MIN..XC2_SHIFT_DISPLACEMENT_MAX -> {
                                    ctx.failureReason = "xc2 shift displacement=${"%.0f".format(xc2ShiftDisplacementMax)}" +
                                            " ∉ [$XC2_SHIFT_DISPLACEMENT_MIN, $XC2_SHIFT_DISPLACEMENT_MAX]（沿飞行向量折跃 " +
                                            "${ImaginaryWingsTuning.SHIFT_DISTANCE.toInt()}su，界由 SHIFT_DISTANCE ±100 派生）"
                                    transitionXc2Phase(XC2_PHASE_FAILED)
                                }

                                xc2ClearDisplacementMax < XC2_CLEAR_MIN_DISPLACEMENT -> {
                                    ctx.failureReason = "xc2 clear push displacement=${"%.1f".format(xc2ClearDisplacementMax)}" +
                                            " < $XC2_CLEAR_MIN_DISPLACEMENT（起跳 ${ImaginaryWingsTuning.CLEAR_RADIUS.toInt()}su 净空未推开靶舰）"
                                    transitionXc2Phase(XC2_PHASE_FAILED)
                                }

                                xc2ClearHpLoss > XC2_CLEAR_HP_TOLERANCE -> {
                                    ctx.failureReason = "xc2 clear dealt damage=${"%.0f".format(xc2ClearHpLoss)}" +
                                            " > $XC2_CLEAR_HP_TOLERANCE（净空只可推开、不应结算伤害）"
                                    transitionXc2Phase(XC2_PHASE_FAILED)
                                }

                                !xc2PlayerShieldOnBeforeShift -> {
                                    ctx.failureReason = "xc2 shield 未在折跃前升起" +
                                            "（舞台前置失败，护盾保持未被验证）"
                                    transitionXc2Phase(XC2_PHASE_FAILED)
                                }

                                xc2PlayerShieldForcedOff -> {
                                    ctx.failureReason = "xc2 shift 激活后护盾被关闭（系统不强制关盾，护盾应保持开启）"
                                    transitionXc2Phase(XC2_PHASE_FAILED)
                                }

                                else -> {
                                    ctx.log.info(
                                        "[ASTD-Automation] xc2 shift evidence: displacement=${"%.0f".format(xc2ShiftDisplacementMax)} " +
                                                "clearPush=${"%.1f".format(xc2ClearDisplacementMax)} " +
                                                "clearHpLoss=${"%.1f".format(xc2ClearHpLoss)} " +
                                                "shieldHeld=${xc2PlayerShieldOnBeforeShift}",
                                    )
                                    player.shield?.let { if (it.isOn) it.toggleOff() }
                                    transitionXc2Phase(XC2_PHASE_EXCHANGE)
                                }
                            }
                        }
                    }
                    if (xc2Phase == XC2_PHASE_SHIFT && xc2ActivatedAt < 0f &&
                        ctx.elapsed - xc2PhaseStartedAt >= XC2_ACTIVATE_TIMEOUT
                    ) {
                        ctx.failureReason = "xc2 activate timeout: ${XC2_ACTIVATE_TIMEOUT.toInt()}s 内系统未点亮" +
                                "（attempts=$xc2ActivateAttempts state=${system.state} cd=${"%.1f".format(system.cooldownRemaining)}）"
                        transitionXc2Phase(XC2_PHASE_FAILED)
                    }
                }
            }

            XC2_PHASE_EXCHANGE -> {
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_WEAPON_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = true, allowFire = false,
                    enemyAnchor = XC2_WEAPON_ENEMY_ANCHOR,
                )
                if (player != null && enemy != null && system != null) {
                    val pool = ImaginaryCharge.of(player)
                    if (!xc2ExchangeArmed) {
                        xc2ExchangeArmed = true
                        system.setAmmo(0)
                        pool.charge = XC2_EXCHANGE_CHARGE
                        xc2ChargeBeforeExchange = pool.charge
                        xc2AmmoBeforeExchange = system.ammo
                        ctx.log.info(
                            "[ASTD-Automation] xc2 exchange armed: ammo=${system.ammo}/${system.maxAmmo} " +
                                    "charge=${"%.1f".format(pool.charge)}",
                        )
                    } else if (xc2ChargeDropAtExchange < 0f && system.ammo > xc2AmmoBeforeExchange) {
                        xc2ChargeDropAtExchange = xc2ChargeBeforeExchange - pool.charge
                        ctx.log.info(
                            "[ASTD-Automation] xc2 exchange observed: ammo=${system.ammo}/${system.maxAmmo} " +
                                    "chargeDrop=${"%.1f".format(xc2ChargeDropAtExchange)}",
                        )
                    }
                    val elapsed = ctx.elapsed - xc2PhaseStartedAt
                    if (xc2ChargeDropAtExchange >= 0f) {
                        when {
                            xc2ChargeDropAtExchange !in XC2_EXCHANGE_DROP_MIN..XC2_EXCHANGE_DROP_MAX -> {
                                ctx.failureReason = "xc2 exchange charge drop=${"%.1f".format(xc2ChargeDropAtExchange)}" +
                                        " ∉ [$XC2_EXCHANGE_DROP_MIN, $XC2_EXCHANGE_DROP_MAX]（兑换按阈值扣固定充能换一次系统次数）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            else -> {
                                stageXc2ShuttleTarget(enemy)
                                transitionXc2Phase(XC2_PHASE_SHUTTLE)
                            }
                        }
                    } else if (elapsed >= XC2_EXCHANGE_WINDOW_SECONDS) {
                        ctx.failureReason = "xc2 exchange missing: ammo=${system.ammo}/${system.maxAmmo} " +
                                "charge=${"%.1f".format(pool.charge)}（次数为 0 且充能超阈值时未兑换）"
                        transitionXc2Phase(XC2_PHASE_FAILED)
                    }
                }
            }

            XC2_PHASE_SHUTTLE -> {
                if (xc2EmpProbe == null && enemy != null) {
                    val probe = Xc2EmpProbe { direct, shieldHit, power ->
                        if (shieldHit) {
                            xc2EmpShieldHitEvents++
                        } else if (direct) {
                            xc2EmpDirectHits++
                            closeXc2EmpBurst()
                            xc2EmpBurstPower = power
                        } else {
                            xc2EmpArcTotal++
                            xc2EmpBurstCurrent++
                            xc2EmpArcMaxBurst = maxOf(xc2EmpArcMaxBurst, xc2EmpBurstCurrent)
                        }
                    }
                    enemy.addListener(probe)
                    xc2EmpProbe = probe
                }
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_WEAPON_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = false, allowFire = true,
                    enemyAnchor = XC2_WEAPON_ENEMY_ANCHOR, enemyShieldUp = false,
                    preservePlayerAI = true,
                )
                if (enemy?.shield?.isOn == true) xc2EnemyShieldOnFrames++
                if (player != null && enemy != null && system != null && weapon != null) {
                    accumulateXc2WeaponDamage(enemy)
                    val pool = ImaginaryCharge.of(player)
                    trackXc2HitCharge(pool, DifficultyTuningImpl.valueFor(StarfallWingTuning.HIT_GAIN, true))
                    player.shipTarget = enemy
                    weapon.currAngle = Misc.getAngleInDegrees(weapon.location, enemy.location)
                    // 逐帧重置冷却会把开火周期反复归零，导致零弹体，只能按帧请求强火。
                    weapon.setForceFireOneFrame(true)
                    trackXc2Shuttles(engine, weapon)
                    if (!xc2WeaponDiagLogged) {
                        xc2WeaponDiagLogged = true
                        ctx.log.info(
                            "[ASTD-Automation] xc2 weapon diag: disabled=${weapon.isDisabled} " +
                                    "permDisabled=${weapon.isPermanentlyDisabled} cd=${"%.2f".format(weapon.cooldownRemaining)} " +
                                    "ammo=${if (weapon.usesAmmo()) weapon.ammo else -1} slot=${weapon.slot?.id} " +
                                    "arc=${weapon.slot?.arc} type=${weapon.slot?.weaponType} " +
                                    "ai=${player.shipAI != null} " +
                                    "dist=${"%.0f".format(MathUtils.getDistance(player.location, enemy.location))}",
                        )
                    }
                    if (ctx.elapsed - xc2PhaseStartedAt >= XC2_SHUTTLE_EVAL_SECONDS) {
                        val powerFloor = DifficultyTuningImpl.valueFor(StarfallWingTuning.POWER_MIN, true) -
                                XC2_POWER_TOLERANCE
                        val powerCeiling = DifficultyTuningImpl.valueFor(StarfallWingTuning.POWER_MAX, true) +
                                ImaginaryWingsTuning.CHARGE_MAX * StarfallWingTuning.CHARGE_FRACTION *
                                DifficultyTuningImpl.valueFor(StarfallWingTuning.CHARGE_CONVERSION, true) +
                                XC2_POWER_TOLERANCE
                        val hitGain = DifficultyTuningImpl.valueFor(StarfallWingTuning.HIT_GAIN, true)
                        closeXc2EmpBurst()
                        val arcsPerHit = if (xc2EmpDirectHits > 0) {
                            xc2EmpArcTotal.toFloat() / xc2EmpDirectHits
                        } else {
                            -1f
                        }
                        val damagePerFactorPowerSpread = if (xc2ShuttleSeen > 0) {
                            xc2DamagePerFactorPowerMax - xc2DamagePerFactorPowerMin
                        } else {
                            -1f
                        }
                        val speedRatioSpread = xc2SpeedRatioMax - xc2SpeedRatioMin
                        when {
                            xc2ShuttleSeen < XC2_EXPECT_SHUTTLES -> {
                                ctx.failureReason = "xc2 shuttles=$xc2ShuttleSeen < $XC2_EXPECT_SHUTTLES" +
                                        "（主炮未产出携带发射状态的星梭）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2ShuttleTypes.size < StarfallWingTuning.ShuttleType.entries.size -> {
                                ctx.failureReason = "xc2 shuttle types=$xc2ShuttleTypes" +
                                        "，期望 ${StarfallWingTuning.ShuttleType.entries}（四色未覆盖）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2MissileTypeViolations > 0 -> {
                                ctx.failureReason = "xc2 shuttles instantiated as MissileAPI=$xc2MissileTypeViolations" +
                                        "（星梭必须是普通射弹，否则会被点防当导弹拦截）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2CollisionClassViolations > 0 -> {
                                ctx.failureReason = "xc2 shuttle collision class violations=$xc2CollisionClassViolations" +
                                        "（星梭碰撞类别必须是 ${CollisionClass.PROJECTILE_NO_FF}）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2DamageTypeMismatch > 0 -> {
                                ctx.failureReason = "xc2 shuttle damage type mismatch=$xc2DamageTypeMismatch" +
                                        "（射弹伤害类型必须等于所抽颜色的伤害类型）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            (xc2ShuttleFlux[StarfallWingTuning.ShuttleType.BLUE] ?: 0f) <= 0f -> {
                                ctx.failureReason = "xc2 blue shuttle fluxComponent missing: " +
                                        "flux=${xc2ShuttleFlux}（蓝色星梭应携带额外 EMP 伤害）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2ShuttleFlux.any { (type, flux) ->
                                type != StarfallWingTuning.ShuttleType.BLUE && flux > 0f
                            } -> {
                                ctx.failureReason = "xc2 non-blue shuttle carries fluxComponent: " +
                                        "flux=${xc2ShuttleFlux}（其余三色不应有 EMP 分量）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2EmpDirectHits <= 0 -> {
                                ctx.failureReason = "xc2 emp 未在承伤侧观测到" +
                                        "（蓝色星梭应携带 ${XC2_BLUE_EMP_RATIO.toInt()} 倍伤害的 EMP）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2EmpShieldHitEvents > 0 -> {
                                ctx.failureReason = "xc2 emp shieldHit=$xc2EmpShieldHitEvents" +
                                        "（靶舰护盾未压下，EMP 被护盾拦截；shieldOnFrames=$xc2EnemyShieldOnFrames）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2EmpBurstViolations > 0 || xc2EmpBurstsClosed != xc2EmpDirectHits -> {
                                ctx.failureReason = "xc2 emp power burst mismatch: violations=$xc2EmpBurstViolations " +
                                    "bursts=$xc2EmpBurstsClosed directHits=$xc2EmpDirectHits"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2PowerMin < powerFloor || xc2PowerMax > powerCeiling -> {
                                ctx.failureReason = "xc2 shuttle power=[${"%.2f".format(xc2PowerMin)}, " +
                                        "${"%.2f".format(xc2PowerMax)}] ⊄ " +
                                        "[${"%.2f".format(powerFloor)}, ${"%.2f".format(powerCeiling)}]" +
                                        "（威力 = 难度表随机区间 + 已消耗充能 × 换算率）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2BlueEmpRatioViolations > 0 -> {
                                ctx.failureReason = "xc2 blue emp ratio violations=$xc2BlueEmpRatioViolations" +
                                        "（蓝色星梭 EMP 必须为该发伤害的 ${XC2_BLUE_EMP_RATIO.toInt()} 倍）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            damagePerFactorPowerSpread > xc2DamagePerFactorPowerMax * XC2_DAMAGE_SPREAD_TOLERANCE -> {
                                ctx.failureReason = "xc2 damage/(power×颜色系数) spread=${"%.2f".format(damagePerFactorPowerSpread)}" +
                                        " > ${"%.2f".format(xc2DamagePerFactorPowerMax * XC2_DAMAGE_SPREAD_TOLERANCE)}" +
                                        "（伤害 = 面板 × 颜色系数 × 威力 × 难度系数，跨发恒定）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2SpeedSamples < XC2_EXPECT_SPEED_SAMPLES -> {
                                ctx.failureReason = "xc2 shuttle speed samples=$xc2SpeedSamples" +
                                        " < $XC2_EXPECT_SPEED_SAMPLES（星梭未进入可采样的正常飞行段）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            speedRatioSpread > XC2_SPEED_RATIO_TOLERANCE -> {
                                ctx.failureReason = "xc2 shuttle velocity/speedFactor spread=${"%.1f".format(speedRatioSpread)}" +
                                        " > $XC2_SPEED_RATIO_TOLERANCE（实际速度应为基准速度 × 弹速倍率，velocity/speedFactor 跨发恒定）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2RangeMismatch > 0 -> {
                                ctx.failureReason = "xc2 shuttle range mismatch=$xc2RangeMismatch" +
                                        "（星梭开始滑行前的有效射程应等于主炮射程）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            !xc2HitChargeJumpSeen -> {
                                ctx.failureReason = "xc2 hit charge jump missing（命中回复 " +
                                        "${hitGain.toInt()}% 虚数充能未观测到）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2WeaponDamageAccum < XC2_EXPECT_WEAPON_HP_DROP -> {
                                ctx.failureReason = "xc2 shuttle damage shortfall: hpDrop=${"%.0f".format(xc2WeaponDamageAccum)}" +
                                        " < $XC2_EXPECT_WEAPON_HP_DROP（星梭直击与范围伤害未落账）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] xc2 shuttle evidence: seen=$xc2ShuttleSeen " +
                                            "types=$xc2ShuttleTypes power=[${"%.2f".format(xc2PowerMin)}, " +
                                            "${"%.2f".format(xc2PowerMax)}] samples=$xc2SpeedSamples " +
                                            "velocityRatio=[${"%.1f".format(xc2SpeedRatioMin)}, " +
                                            "${"%.1f".format(xc2SpeedRatioMax)}] hpDrop=${"%.0f".format(xc2WeaponDamageAccum)} " +
                                            "damagePerPower=[${"%.1f".format(xc2DamagePerFactorPowerMin)}, " +
                                            "${"%.1f".format(xc2DamagePerFactorPowerMax)}] " +
                                            "flux=$xc2ShuttleFlux empHits=$xc2EmpDirectHits arcs=$xc2EmpArcTotal " +
                                            "arcsPerHit=${"%.2f".format(arcsPerHit)} bursts=$xc2EmpBurstsClosed " +
                                            "burstRange=[${if (xc2EmpBurstsClosed > 0) xc2EmpArcMinBurst else -1}, " +
                                            "$xc2EmpArcMaxBurst]",
                                )
                                transitionXc2Phase(XC2_PHASE_COAST)
                            }
                        }
                    }
                }
            }

            XC2_PHASE_COAST -> {
                if (xc2CoastBaseline.isEmpty()) {
                    xc2CoastBaseline = engine.projectiles.mapTo(HashSet()) { System.identityHashCode(it) }
                }
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_WEAPON_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = true, allowFire = true,
                    enemyAnchor = XC2_COAST_ENEMY_ANCHOR,
                )
                if (player != null && enemy != null && weapon != null) {
                    player.shipTarget = null
                    val coaster = xc2CoastProjectile ?: findCoastShuttle(engine)?.also {
                        xc2CoastProjectile = it
                        xc2CoastLaunchedAt = ctx.elapsed
                        xc2CoastLaunchOrigin = Vector2f(it.location)
                        xc2CoastLaunchFacing = it.facing
                        ctx.log.info(
                            "[ASTD-Automation] xc2 coast launch: loc=(${"%.0f".format(it.location.x)}, " +
                                    "${"%.0f".format(it.location.y)}) facing=${"%.0f".format(it.facing)} " +
                                    "enemyDist=${"%.0f".format(MathUtils.getDistance(player.location, enemy.location))} " +
                                    "enemyPhased=${enemy.isPhased}",
                        )
                    }
                    if (coaster == null) {
                        weapon.currAngle = 0f
                        val tracker = weapon.ammoTracker
                        if (tracker != null && tracker.usesAmmo() && tracker.ammo <= 0) {
                            tracker.setAmmo(tracker.maxAmmo)
                        }
                        weapon.setForceFireOneFrame(true)
                    }
                    if (coaster != null) {
                        weapon.setForceNoFireOneFrame(true)
                        trackCoast(coaster, ctx)
                        driveCoastMissile(engine, enemy, coaster, amount)
                        if (!engine.isEntityInPlay(coaster) && !xc2CoastEvaluated) {
                            xc2CoastEvaluated = true
                            if (xc2CoastFadeAt < 0f && xc2CoastMissileContacts > 0) {
                                ctx.failureReason = "xc2 coaster consumed by missile at contact " +
                                        "#$xc2CoastMissileContacts（passThroughMissiles 未生效：星梭被敌导弹抵消）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            } else {
                                evaluateCoast(ctx, player, weapon)
                            }
                        }
                    } else if (ctx.elapsed - xc2PhaseStartedAt >= XC2_COAST_SPAWN_TIMEOUT) {
                        ctx.failureReason = "xc2 coast shuttle not spawned（主炮强火未产出星梭）"
                        transitionXc2Phase(XC2_PHASE_FAILED)
                    }
                }
            }

            XC2_PHASE_IMPACT -> {
                xc2EmpProbe?.let { enemy?.removeListener(it) }
                xc2EmpProbe = null
                if (player != null && enemy != null && weapon != null) {
                    val probe = xc2ImpactProbe ?: StarwingImpactProbe(ctx, player, enemy, weapon).also { xc2ImpactProbe = it }
                    if (probe.advance()) {
                        probe.close()
                        transitionXc2Phase(XC2_PHASE_TARGET)
                    } else if (ctx.failureReason != null) {
                        transitionXc2Phase(XC2_PHASE_FAILED)
                    }
                }
            }

            XC2_PHASE_TARGET -> {
                if (player != null && enemy != null && weapon != null) {
                    val probe = xc2TargetProbe ?: StarwingTargetProbe(ctx, player, enemy, weapon).also { xc2TargetProbe = it }
                    if (probe.advance()) {
                        probe.close()
                        transitionXc2Phase(XC2_PHASE_COMPLETED)
                    } else if (ctx.failureReason != null) {
                        transitionXc2Phase(XC2_PHASE_FAILED)
                    }
                }
            }

            XC2_PHASE_COMPLETED -> {
                xc2EmpProbe?.let { enemy?.removeListener(it) }
                xc2EmpProbe = null
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_PLAYER_ARRIVAL, playerFacing = 0f,
                    blockSystem = true, healEnemy = true, allowFire = false,
                )
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "xc2 ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            xc2Phase == XC2_PHASE_FAILED -> "Failed"
            xc2Phase != XC2_PHASE_COMPLETED &&
                    ctx.elapsed - xc2PhaseStartedAt > XC2_PHASE_TIMEOUT -> {
                ctx.failureReason = "xc2 phase timeout: $xc2Phase（displacement=${"%.0f".format(xc2ShiftDisplacementMax)} " +
                        "clearPush=${"%.1f".format(xc2ClearDisplacementMax)} shuttles=$xc2ShuttleSeen " +
                        "types=${xc2ShuttleTypes.size} coastFade=${"%.2f".format(if (xc2CoastFadeAt >= 0f) ctx.elapsed - xc2CoastFadeAt else -1f)}）"
                "Failed"
            }

            xc2Phase == XC2_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Failed") {
            if (xc2Phase != XC2_PHASE_FAILED) transitionXc2Phase(XC2_PHASE_FAILED)
            xc2EmpProbe?.let { enemy?.removeListener(it) }
            xc2EmpProbe = null
            xc2ImpactProbe?.close()
            xc2TargetProbe?.close()
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: xc_002_rift_shift_basic shift/clear/exchange/shuttle/coast/impact/target evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findXc2Player(ctx.engine) }) { lockXc2Camera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val xc2Player = findXc2Player(engine)
        val xc2System = xc2Player?.system
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        json.appendLine("  \"xc2Phase\": \"$xc2Phase\",")
        json.appendLine("  \"xc2ImpactCase\": ${jsonString(xc2ImpactProbe?.caseName)},")
        json.appendLine("  \"xc2ImpactCasesPassed\": ${xc2ImpactProbe?.completedCases ?: 0},")
        json.appendLine("  \"xc2TargetStage\": ${jsonString(xc2TargetProbe?.stageName)},")
        json.appendLine("  \"xc2TargetStagesPassed\": ${xc2TargetProbe?.passedStages ?: 0},")
        json.appendLine("  \"xc2SystemId\": ${jsonString(xc2System?.id)},")
        json.appendLine("  \"xc2SystemState\": ${jsonString(xc2System?.state?.name)},")
        json.appendLine("  \"xc2SystemCooldownRemaining\": ${formatFloat(xc2System?.cooldownRemaining ?: -1f)},")
        json.appendLine("  \"xc2SystemAmmo\": ${xc2System?.ammo ?: -1},")
        json.appendLine("  \"xc2ActivateAttempts\": $xc2ActivateAttempts,")
        json.appendLine("  \"xc2ShiftDisplacementMax\": ${formatFloat(xc2ShiftDisplacementMax)},")
        json.appendLine("  \"xc2ClearDisplacementMax\": ${formatFloat(xc2ClearDisplacementMax)},")
        json.appendLine("  \"xc2ClearHpLoss\": ${formatFloat(xc2ClearHpLoss)},")
        json.appendLine("  \"xc2ExchangeChargeDrop\": ${formatFloat(xc2ChargeDropAtExchange)},")
        json.appendLine("  \"xc2ShuttleSeen\": $xc2ShuttleSeen,")
        json.appendLine("  \"xc2ShuttleTypes\": ${jsonStringList(xc2ShuttleTypes.map { it.name })},")
        json.appendLine("  \"xc2ShuttlePowerMin\": ${formatFloat(if (xc2ShuttleSeen > 0) xc2PowerMin else -1f)},")
        json.appendLine("  \"xc2ShuttlePowerMax\": ${formatFloat(if (xc2ShuttleSeen > 0) xc2PowerMax else -1f)},")
        json.appendLine(
            "  \"xc2ShuttleSpeedRatioSpread\": ${
                formatFloat(if (xc2ShuttleSeen > 0) xc2SpeedRatioMax - xc2SpeedRatioMin else -1f)
            },",
        )
        json.appendLine("  \"xc2ShuttleRangeMismatch\": $xc2RangeMismatch,")
        json.appendLine("  \"xc2MissileTypeViolations\": $xc2MissileTypeViolations,")
        json.appendLine("  \"xc2CollisionClassViolations\": $xc2CollisionClassViolations,")
        json.appendLine("  \"xc2DamageTypeMismatch\": $xc2DamageTypeMismatch,")
        json.appendLine("  \"xc2SpeedSamples\": $xc2SpeedSamples,")
        json.appendLine("  \"xc2HitChargeJumpSeen\": $xc2HitChargeJumpSeen,")
        json.appendLine("  \"xc2EmpDirectHits\": $xc2EmpDirectHits,")
        json.appendLine("  \"xc2EmpArcTotal\": $xc2EmpArcTotal,")
        json.appendLine("  \"xc2EmpArcBursts\": $xc2EmpBurstsClosed,")
        json.appendLine("  \"xc2EmpPowerBurstViolations\": $xc2EmpBurstViolations,")
        json.appendLine(
            "  \"xc2EmpArcBurstMin\": ${if (xc2EmpBurstsClosed > 0) xc2EmpArcMinBurst else -1},",
        )
        json.appendLine("  \"xc2EmpArcMaxBurst\": $xc2EmpArcMaxBurst,")
        json.appendLine("  \"xc2EmpShieldHitEvents\": $xc2EmpShieldHitEvents,")
        json.appendLine("  \"xc2EnemyShieldOnFrames\": $xc2EnemyShieldOnFrames,")
        json.appendLine("  \"xc2PlayerShieldOnBeforeShift\": $xc2PlayerShieldOnBeforeShift,")
        json.appendLine("  \"xc2PlayerShieldForcedOff\": $xc2PlayerShieldForcedOff,")
        json.appendLine("  \"xc2BlueEmpRatioViolations\": $xc2BlueEmpRatioViolations,")
        json.appendLine(
            "  \"xc2ShuttleDamagePerPowerSpread\": ${
                formatFloat(
                    if (xc2ShuttleSeen > 0) xc2DamagePerFactorPowerMax - xc2DamagePerFactorPowerMin else -1f
                )
            },",
        )
        json.appendLine(
            "  \"xc2WeaponHpDrop\": ${
                formatFloat(
                    if (!xc2WeaponStageBuffed) -1f else xc2WeaponDamageAccum
                )
            },",
        )
        json.appendLine("  \"xc2PlayerCurrFlux\": ${formatFloat(xc2Player?.fluxTracker?.currFlux ?: -1f)},")
    }

    private companion object {
        private const val XC2_PHASE_SPAWN = "SPAWN"
        private const val XC2_PHASE_SHIFT = "SHIFT"
        private const val XC2_PHASE_EXCHANGE = "EXCHANGE"
        private const val XC2_PHASE_SHUTTLE = "SHUTTLE"
        private const val XC2_PHASE_COAST = "COAST"
        private const val XC2_PHASE_IMPACT = "IMPACT"
        private const val XC2_PHASE_TARGET = "TARGET"
        private const val XC2_PHASE_COMPLETED = "COMPLETED"
        private const val XC2_PHASE_FAILED = "FAILED"
        private const val XC2_PLAYER_HULL = "astd_xc_002"
        private const val XC2_ENEMY_HULL = "dominator"
        private const val XC2_SYSTEM_ID = "astd_rift_shift"
        private const val XC2_WEAPON_ID = "astd_starfall_wing"
        private const val XC2_HULLMOD_ID = "astd_imaginary_wings"

        private val XC2_PLAYER_ANCHOR = Vector2f(-800f, -100f)
        private val XC2_PLAYER_ARRIVAL = Vector2f(XC2_PLAYER_ANCHOR.x + ImaginaryWingsTuning.SHIFT_DISTANCE, XC2_PLAYER_ANCHOR.y)

        private val XC2_CLEAR_ENEMY_ANCHOR = Vector2f(XC2_PLAYER_ANCHOR.x - 200f, XC2_PLAYER_ANCHOR.y)

        private val XC2_WEAPON_PLAYER_ANCHOR = Vector2f(-800f, 300f)
        private val XC2_WEAPON_ENEMY_ANCHOR = Vector2f(-300f, 300f)

        private val XC2_COAST_ENEMY_ANCHOR = Vector2f(800f, 300f)
        private val XC2_CAMERA_CENTER = Vector2f(-400f, 100f)
        private const val XC2_CAMERA_VISIBLE_HEIGHT = 1800f
        private const val XC2_SPAWN_SETTLE_SECONDS = 0.6f

        private const val XC2_ACTIVATE_TIMEOUT = 10f
        private const val XC2_SHIFT_PIN_FREE_SECONDS = 1.0f
        private const val XC2_SHIFT_ASSERT_SECONDS = 1.5f
        private const val XC2_SHIFT_DISPLACEMENT_TOLERANCE = 100f
        private const val XC2_SHIFT_DISPLACEMENT_MIN = ImaginaryWingsTuning.SHIFT_DISTANCE - XC2_SHIFT_DISPLACEMENT_TOLERANCE
        private const val XC2_SHIFT_DISPLACEMENT_MAX = ImaginaryWingsTuning.SHIFT_DISTANCE + XC2_SHIFT_DISPLACEMENT_TOLERANCE
        private const val XC2_CLEAR_WINDOW_SECONDS = 0.6f
        private const val XC2_CLEAR_MIN_DISPLACEMENT = 5f
        private const val XC2_CLEAR_HP_TOLERANCE = 0.5f

        private const val XC2_EXCHANGE_CHARGE =
            (ImaginaryWingsTuning.EXCHANGE_THRESHOLD + ImaginaryWingsTuning.CHARGE_MAX) * 0.5f
        private const val XC2_EXCHANGE_WINDOW_SECONDS = 3f
        private const val XC2_EXCHANGE_DROP_TOLERANCE = 10f
        private const val XC2_EXCHANGE_DROP_MIN = ImaginaryWingsTuning.EXCHANGE_COST - XC2_EXCHANGE_DROP_TOLERANCE
        private const val XC2_EXCHANGE_DROP_MAX = ImaginaryWingsTuning.EXCHANGE_COST + XC2_EXCHANGE_DROP_TOLERANCE

        private const val XC2_SHUTTLE_EVAL_SECONDS = 12f
        private const val XC2_EXPECT_SHUTTLES = 12
        private const val XC2_POWER_TOLERANCE = 0.01f
        private const val XC2_SPEED_RATIO_TOLERANCE = 2f
        private const val XC2_SPEED_SAMPLE_MIN_FRACTION = 0.05f
        private const val XC2_SPEED_SAMPLE_MAX_FRACTION = 0.9f
        private const val XC2_EXPECT_SPEED_SAMPLES = 4
        private const val XC2_RANGE_TOLERANCE = 1f
        private const val XC2_HIT_CHARGE_JUMP_FRACTION = 0.5f
        private const val XC2_BLUE_EMP_RATIO = 2f
        private const val XC2_BLUE_EMP_RATIO_TOLERANCE = 0.01f
        private const val XC2_DAMAGE_SPREAD_TOLERANCE = 0.01f
        private const val XC2_EXPECT_WEAPON_HP_DROP = 300f
        private const val XC2_COAST_FADE_SECONDS = 0.5f
        private const val XC2_COAST_FADE_TOLERANCE = 0.1f
        private const val XC2_COAST_MIN_DISPLACEMENT = 100f
        private const val XC2_COAST_MIN_FADE_SPEED = 100f
        private const val XC2_COAST_MIN_LATERAL = 5f
        private const val XC2_COAST_SPAWN_TIMEOUT = 3f
        private const val XC2_COAST_RANGE_RATIO_TOLERANCE = 0.15f
        private const val XC2_COAST_MISSILE_WEAPON = "harpoon"
        private const val XC2_COAST_MISSILE_LEAD = 0.6f
        private const val XC2_COAST_MIN_MISSILE_CONTACTS = 3
        private const val XC2_PHASE_TIMEOUT = 120f

        private const val XC2_STAGE_ENEMY_HULL_BUFFER = 500000f
    }
}

private class Xc2EmpProbe(
    private val onEmp: (direct: Boolean, shieldHit: Boolean, power: Float) -> Unit,
) : DamageTakenModifier, DamageListener {
    private var nativePower: Float? = null
    private var nativeShieldHit = false

    override fun modifyDamageTaken(
        param: Any?,
        target: CombatEntityAPI?,
        damage: DamageAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
    ): String? {
        val projectile = param as? DamagingProjectileAPI
        val shot = projectile?.customData?.get(StarfallWingTuning.SHOT_STATE_KEY) as? StarfallWingShot
        nativePower = if (shot?.type == StarfallWingTuning.ShuttleType.BLUE && damage != null && damage.fluxComponent > 0f) shot.power else null
        nativeShieldHit = shieldHit
        return null
    }

    // 普通伤害为零时原版跳过伤害修改监听，但仍报告结算结果。
    override fun reportDamageApplied(source: Any?, target: CombatEntityAPI?, result: ApplyDamageResultAPI?) {
        val power = nativePower
        nativePower = null
        if (result == null) return
        if (power != null) {
            if (nativeShieldHit || result.empDamage > 0f) onEmp(true, nativeShieldHit, power)
        } else if (result.empDamage > 0f && result.damageToHull == 0f && result.totalDamageToArmor == 0f &&
            result.damageToShields == 0f && result.overMaxDamageToShields == 0f
        ) {
            onEmp(false, false, 0f)
        }
    }
}

private object NoOpMissileAi : MissileAIPlugin {
    override fun advance(amount: Float) {}
}

// DCR 类型隔离在此对象，调用前必须确认模组已启用。
private object Xc2DcrProbe {

    class Report(
        val entryCount: Int,
        val ownEntries: Int,
        val gunTotal: Float,
        val gunEmp: Float,
        val unknownWeapons: Int,
    )

    fun collect(memberId: String, gunWeaponName: String): Report {
        val result = EveryFrameDamageDetector.completeCombatAndReset(false)
        val damages = result?.damages ?: emptyArray()
        var own = 0
        var gunTotal = 0f
        var gunEmp = 0f
        var unknown = 0
        for (damage in damages) {
            if (damage == null) continue
            if (damage.firingShip?.id != memberId) continue
            own++
            val name = damage.weaponName ?: ""
            if (name.startsWith(UNKNOWN_PREFIX)) unknown++
            if (name == gunWeaponName) {
                gunTotal += damage.shieldDamage + damage.armorDamage + damage.hullDamage
                gunEmp += damage.empDamage
            }
        }
        return Report(damages.size, own, gunTotal, gunEmp, unknown)
    }

    private const val UNKNOWN_PREFIX = "Unknown"
}
