package cn.kasuminova.astd.combat.automation.scenario.lens

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmMissileAI
import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmStrikeImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 辉星 MRM 场景（规格 08 §4.2 烟测检查点）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class StellarMrmScenario : AbstractAutomationScenario() {
    // ==== stellar mrm 场景状态（相位机 MOUNT → PRIORITY → FIGHTER_HIT → SHIP_HIT → LINE_CROSS → ENEMY_SCALE → COMPLETED） ====
    private var smPhase = SM_PHASE_MOUNT
    private var smPhaseStartedAt = 0f

    // PRIORITY：发射舱单次两发证据（同帧/近帧 spawn 分组，间隔 >SM_BURST_GROUP_GAP 判定新一轮触发）。
    private val smSeenPodProjectiles = mutableSetOf<Int>()
    private var smPodLastSpawnAt = -1f
    private var smPodBurstCurrent = 0
    private var smPodBurstMax = 0

    // 备弹经济观测（min ammo 遥测）。
    private var smMinLauncherAmmo = Int.MAX_VALUE
    private var smMinPodAmmo = Int.MAX_VALUE

    // PRIORITY 帧率采样（多发齐射 AI + VFX 开销证据）。
    private var smFpsTicks = 0
    private var smFpsWallStartNanos = 0L
    private var smFps = -1f

    // FIGHTER_HIT：敌战机被瘫痪武器数峰值（战机武器熄火观测面）。
    private var smMaxFighterDisabled = 0

    // 战机武器/船体探针（第三轮烟测后追加）：EMP 结算 30 次熄火恒 0，需判定脚本伤害是否落到组件——
    // 武器血量比 <1 证明组件承伤通道可达；船体血量比 <1 证明脚本伤害整体可达。
    private var smMinWeaponHealthRatio = 1f
    private var smMaxDisabledDuration = 0f
    private var smMinFighterHullRatio = 1f

    // SHIP_HIT：相位基线与航母/残机清理守卫（令导弹只剩舰船可咬）。
    private var smShipHitExplosionsBaseline = 0
    private var smShipHitAoeBaseline = 0
    private var smShipHitShieldBaseline = 0
    private var smCarrierCleared = false

    // LINE_CROSS：阶段（0=投喂低结构 atropos / 1=投喂增压 harpoon）、投喂集合与判据。
    private var smLineCrossStage = 0
    private val smFedHighMissiles = mutableSetOf<Int>()
    private var smLineCrossBaseline = 0
    private var smHighHpHitConfirmed = false
    private var smFeedAt = -1f
    private var smFeedLane = 0

    // ENEMY_SCALE：敌版三档逐档观测（installScaleForTests 1/2/5 → 爆炸倍率 0.5/1.0/2.5）。
    private var smScaleStep = 0
    private var smScaleStepAt = -1f
    // COMPLETED 截图门控：最近一次辉星裂隙爆炸时刻（截图帧需含裂隙爆炸/双拖尾）。
    private var smLastExplosionAt = -1f
    private var smLastTrackedExplosionCount = 0

    override val scenarioId: String = ASTDInGameAutomationScenario.SM_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isSmEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockSmCamera(engine)
        // 增伤/AOE 浮字仅 devMode 渲染（2026-07-29 审批裁定先例）：本场景为 dev-only 舞台，
        // 开启 devMode 以目检命中浮字与敌版三档（进程被早退杀掉，设置不落盘）。
        Global.getSettings().isDevMode = true
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findSmPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.SM_SCENARIO_ID} combat plugin initialized")
    }

    private fun findSmPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == SM_PLAYER_HULL && !ship.isFighter }

    private fun findSmEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == SM_ENEMY_HULL && !ship.isFighter }

    private fun findSmCarrier(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == SM_CARRIER_HULL && !ship.isFighter }

    private fun findSmLauncher(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.SM_LAUNCHER_WEAPON_ID }

    private fun findSmPod(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.SM_POD_WEAPON_ID }

    private fun smTeleCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private fun smEnemyFighters(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { it.owner != 0 && it.isFighter && !it.isHulk }

    private fun lockSmCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, SM_CAMERA_CENTER, SM_CAMERA_VISIBLE_HEIGHT)
    }

    /** 强制部署 mission reserves（玩家狮鹫 / 敌方秃鹰航母 / 敌方狮鹫均非旗舰，范式同 deployHipReserveShips）。 */
    private fun deploySmReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == SM_PLAYER_HULL -> SM_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == SM_CARRIER_HULL -> SM_CARRIER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == SM_ENEMY_HULL -> SM_ENEMY_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionSmPhase(next: String) {
        ctx.log.info("[ASTD-Automation] sm phase $smPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        smPhase = next
        smPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeHipShips）：双狮鹫逐帧奶 + 辐能清零 + 钉死锚点 +
     * force fire 独占驱动（autofire 关闭）；秃鹰航母保留 AI 放联队（猎机目标源）。
     * 玩家武器瞄准取最近敌战机、无战机取敌方狮鹫（与猎杀优先级同序，保证弹道指向被选中目标）。
     * [aimOverride]：相位特例瞄准点（撞线相位指向最近敌导弹令弹道与投喂 lane 对头相交——
     * 只改发射指向，目标选择遥测仍仅战机/舰船两型，不破坏「不主动拦导弹」证据）。
     * 敌方开火带部署免疫闸（GD/SS/HIP 实机判例：reserves 手动 spawn 舰船部署后数秒内脚本
     * applyDamage 可能全额无效）。
     */
    private fun stabilizeSmShips(engine: CombatEngineAPI, playerFire: Boolean, enemyFire: Boolean, aimOverride: CombatEntityAPI? = null) {
        val player = findSmPlayer(engine)
        val enemy = findSmEnemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, SM_PLAYER_ANCHOR, 0f, allowFire = true, preserveAI = true)
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            player.fluxTracker.hardFlux = 0f
            // 玩家盾常关：敌版三档相位令敌弹落船体（爆炸恒触发 + 浮字目检）。
            player.shield?.let { if (it.isOn) it.toggleOff() }
            setSmAutofire(player, false)
            val aim: CombatEntityAPI? = aimOverride
                ?: smEnemyFighters(engine).minByOrNull { MathUtils.getDistance(player.location, it.location) }
                ?: enemy
            player.shipTarget = if (aimOverride == null) aim as? ShipAPI else enemy
            for (weapon in listOfNotNull(findSmLauncher(player), findSmPod(player))) {
                if (aim != null) weapon.currAngle = Misc.getAngleInDegrees(weapon.location, aim.location)
                weapon.setForceFireOneFrame(playerFire)
            }
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, SM_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = true)
            enemy.shipTarget = player
            enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            enemy.fluxTracker.hardFlux = 0f
            // 护盾策略：SHIP_HIT 相位敌盾开（验证撞击护盾爆炸照常），其余相位常关。
            val shieldOn = smPhase == SM_PHASE_SHIP_HIT
            enemy.shield?.let { if (shieldOn && !it.isOn) it.toggleOn(); if (!shieldOn && it.isOn) it.toggleOff() }
            setSmAutofire(enemy, false)
            findSmLauncher(enemy)?.let {
                if (player != null) it.currAngle = Misc.getAngleInDegrees(it.location, player.location)
                val gated = enemyFire && ctx.elapsed - smPhaseStartedAt >= SM_ENEMY_SETTLE_SECONDS
                it.setForceFireOneFrame(gated)
            }
        }
        // 航母：保留 AI 放阔剑联队；自身无武器（mission 已清槽），仅钉锚点奶血。
        val carrier = findSmCarrier(engine)
        if (carrier != null && !carrier.isHulk) {
            stabilizeShip(carrier, SM_CARRIER_ANCHOR, 180f, allowFire = false, preserveAI = true)
            carrier.hitpoints = carrier.maxHitpoints
            carrier.fluxTracker.currFlux = 0f
        }
    }

    /** 辉星武器组 autofire 总开关（范式同 setHipAutofire）：force fire 独占驱动时关闭。 */
    private fun setSmAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none {
                    it.id == ASTDInGameAutomationScenario.SM_LAUNCHER_WEAPON_ID ||
                            it.id == ASTDInGameAutomationScenario.SM_POD_WEAPON_ID
                }
            ) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /**
     * 撞线投喂（范式同 feedSsMissiles）：敌侧中场生成敌属导弹直线飞向玩家，与玩家导弹流对头相撞。
     * stage 0 喂低结构 atropos（hp=300 < v2 阈值 600，撞线者死）；stage 1 喂增压 2000HP harpoon
     * （> 阈值且连击安全余量，仅爆炸不移除——首次被命中时存活 + 撞线计数不增）。
     */
    private fun feedSmMissile(engine: CombatEngineAPI) {
        if (ctx.elapsed < smFeedAt) return
        smFeedAt = ctx.elapsed + SM_FEED_INTERVAL
        smFeedLane = (smFeedLane + 1) % SM_FEED_LANES
        val source = findSmEnemy(engine) ?: findSmPlayer(engine) ?: return
        val lowStage = smLineCrossStage == 0
        // stage 1 投喂点改在敌舰处（第七轮烟测实证：中场 lane 与导弹流交汇面太薄，80+ 投喂零命中；
        // 弹流全程指向敌舰，自敌舰迎面飞向玩家的投喂弹与弹流全程对头，相撞必然）。
        val spawn = if (lowStage) {
            val laneY = (smFeedLane - (SM_FEED_LANES - 1) * 0.5f) * SM_FEED_LANE_GAP
            Vector2f(SM_FEED_SPAWN_X, laneY)
        } else {
            Vector2f(source.location)
        }
        val angle = Misc.getAngleInDegrees(spawn, SM_PLAYER_ANCHOR)
        val rad = Math.toRadians(angle.toDouble())
        val vel = Vector2f(
            (kotlin.math.cos(rad) * SM_FEED_SPEED).toFloat(),
            (kotlin.math.sin(rad) * SM_FEED_SPEED).toFloat(),
        )
        val weaponId = if (lowStage) SM_FEED_MISSILE_LOW_ID else SM_FEED_MISSILE_HIGH_ID
        val spawned = engine.spawnProjectile(source, null, weaponId, spawn, angle, vel)
        if (spawned == null) {
            ctx.failureReason = "sm missile spawn returned null for weaponId=$weaponId"
            transitionSmPhase(SM_PHASE_FAILED)
            return
        }
        spawned.owner = 1
        if (!lowStage) {
            spawned.hitpoints = SM_HIGH_HP_BOOST
            smFedHighMissiles += System.identityHashCode(spawned)
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deploySmReserveShips(engine)
        lockSmCamera(engine)

        val player = findSmPlayer(engine)
        val enemy = findSmEnemy(engine)
        val launcher = findSmLauncher(player)
        val pod = findSmPod(player)

        val selFighter = smTeleCount(engine, StellarMrmMissileAI.TELE_SEL_FIGHTER)
        val selShip = smTeleCount(engine, StellarMrmMissileAI.TELE_SEL_SHIP)
        val firstTarget = engine.customData[StellarMrmMissileAI.TELE_FIRST_TARGET] as? String
        val bonus = smTeleCount(engine, StellarMrmStrikeImpl.TELE_BONUS_HITS)
        val empHits = smTeleCount(engine, StellarMrmStrikeImpl.TELE_EMP_HITS)
        val empArcs = smTeleCount(engine, StellarMrmStrikeImpl.TELE_EMP_ARCS)
        smTeleCount(engine, StellarMrmStrikeImpl.TELE_AOE_HITS)
        val aoeShipHits = smTeleCount(engine, StellarMrmStrikeImpl.TELE_AOE_SHIP_HITS)
        val shieldHits = smTeleCount(engine, StellarMrmStrikeImpl.TELE_SHIELD_HITS)
        val explosions = smTeleCount(engine, StellarMrmStrikeImpl.TELE_EXPLOSIONS)
        val lineCross = smTeleCount(engine, StellarMrmStrikeImpl.TELE_LINE_CROSS)

        when (smPhase) {
            SM_PHASE_MOUNT -> {
                stabilizeSmShips(engine, playerFire = false, enemyFire = false)
                if (ctx.elapsed - smPhaseStartedAt >= SM_MOUNT_SETTLE_SECONDS) {
                    val launcherSlot = launcher?.slot?.id
                    val podSlot = pod?.slot?.id
                    val launcherRange = launcher?.spec?.maxRange ?: -1f
                    val podRange = pod?.spec?.maxRange ?: -1f
                    val launcherOp = try {
                        launcher?.spec?.getOrdnancePointCost(null, null) ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                    val podOp = try {
                        pod?.spec?.getOrdnancePointCost(null, null) ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                    val tagsOk = launcher?.spec?.tags?.containsAll(SM_REQUIRED_TAGS) == true &&
                            pod?.spec?.tags?.containsAll(SM_REQUIRED_TAGS) == true
                    val slotOk = launcher?.slot?.slotSize == WeaponAPI.WeaponSize.SMALL &&
                            launcher?.slot?.weaponType == WeaponAPI.WeaponType.MISSILE &&
                            pod?.slot?.slotSize == WeaponAPI.WeaponSize.MEDIUM &&
                            pod?.slot?.weaponType == WeaponAPI.WeaponType.MISSILE
                    when {
                        launcher == null || pod == null ||
                                launcherSlot != SM_PLAYER_LAUNCHER_SLOT || podSlot != SM_PLAYER_POD_SLOT -> {
                            ctx.failureReason = "sm mount mismatch: launcherSlot=$launcherSlot podSlot=$podSlot"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }

                        !slotOk -> {
                            ctx.failureReason =
                                "sm slot type/size mismatch: launcher=${launcher.slot?.slotSize}/${launcher.slot?.weaponType} pod=${pod.slot?.slotSize}/${pod.slot?.weaponType}"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }

                        kotlin.math.abs(launcherRange - SM_EXPECT_RANGE) > SM_RANGE_TOLERANCE ||
                                kotlin.math.abs(podRange - SM_EXPECT_RANGE) > SM_RANGE_TOLERANCE -> {
                            ctx.failureReason = "sm range=$launcherRange/$podRange, expect $SM_EXPECT_RANGE"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }

                        launcher.spec?.maxAmmo != SM_LAUNCHER_AMMO || pod.spec?.maxAmmo != SM_POD_AMMO -> {
                            ctx.failureReason = "sm spec maxAmmo=${launcher.spec?.maxAmmo}/${pod.spec?.maxAmmo}, expect $SM_LAUNCHER_AMMO/$SM_POD_AMMO"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }

                        pod.spec?.burstSize != SM_POD_BURST -> {
                            ctx.failureReason = "sm pod burstSize=${pod.spec?.burstSize}, expect $SM_POD_BURST（发射舱单次两发）"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }

                        kotlin.math.abs(launcherOp - SM_LAUNCHER_OP) > 0.01f || kotlin.math.abs(podOp - SM_POD_OP) > 0.01f -> {
                            ctx.failureReason = "sm OP=$launcherOp/$podOp, expect $SM_LAUNCHER_OP/$SM_POD_OP"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }

                        !tagsOk -> {
                            ctx.failureReason = "sm tags 缺 no_drop 两件套: launcher=${launcher.spec?.tags} pod=${pod.spec?.tags}"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }

                        !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.SM_LAUNCHER_PROJECTILE_SPEC_ID) ||
                                !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.SM_POD_PROJECTILE_SPEC_ID) -> {
                            ctx.failureReason = "sm projectile VFX 未登记: launcher/pod shot"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }

                        else -> {
                            ctx.log.info(
                                "[ASTD-Automation] sm mount ok: slots=$launcherSlot/$podSlot range=$launcherRange " +
                                        "ammo=${launcher.spec?.maxAmmo}/${pod.spec?.maxAmmo} burst=${pod.spec?.burstSize} " +
                                        "OP=$launcherOp/$podOp tags=no_drop 两件套",
                            )
                            smFpsTicks = 0
                            smFpsWallStartNanos = System.nanoTime()
                            transitionSmPhase(SM_PHASE_PRIORITY)
                        }
                    }
                }
            }

            SM_PHASE_PRIORITY -> {
                // 战机在场才放行开火：首个目标选择必定发生在「战机+舰船同场」的局面下（优先追猎真验证）。
                val fightersPresent = smEnemyFighters(engine).isNotEmpty()
                stabilizeSmShips(engine, playerFire = fightersPresent, enemyFire = false)
                smFpsTicks++
                // 发射舱单次两发分组（spawn 间隔 >SM_BURST_GROUP_GAP 判定新一轮触发）。
                for (projectile in engine.projectiles) {
                    if (projectile.projectileSpecId != ASTDInGameAutomationScenario.SM_POD_PROJECTILE_SPEC_ID) continue
                    val id = System.identityHashCode(projectile)
                    if (!smSeenPodProjectiles.add(id)) continue
                    if (smPodLastSpawnAt >= 0f && ctx.elapsed - smPodLastSpawnAt > SM_BURST_GROUP_GAP) {
                        smPodBurstMax = maxOf(smPodBurstMax, smPodBurstCurrent)
                        smPodBurstCurrent = 0
                    }
                    smPodBurstCurrent++
                    smPodLastSpawnAt = ctx.elapsed
                }
                smPodBurstMax = maxOf(smPodBurstMax, smPodBurstCurrent)
                launcher?.let { smMinLauncherAmmo = minOf(smMinLauncherAmmo, it.ammo) }
                pod?.let { smMinPodAmmo = minOf(smMinPodAmmo, it.ammo) }
                if (fightersPresent && selFighter >= 1 && smPodBurstMax >= SM_POD_BURST &&
                    ctx.elapsed - smPhaseStartedAt >= SM_PRIORITY_MIN_SECONDS
                ) {
                    if (firstTarget != "fighter") {
                        ctx.failureReason = "sm first target=$firstTarget, expect fighter（战机+舰船同场首咬必须为战机）"
                        transitionSmPhase(SM_PHASE_FAILED)
                    } else {
                        val wallSeconds = (System.nanoTime() - smFpsWallStartNanos) / 1_000_000_000f
                        if (wallSeconds > 0f) smFps = smFpsTicks / wallSeconds
                        ctx.log.info(
                            "[ASTD-Automation] sm priority evidence: firstTarget=fighter selFighter=$selFighter " +
                                    "podBurstMax=$smPodBurstMax minAmmo=$smMinLauncherAmmo/$smMinPodAmmo " +
                                    "fps=${"%.1f".format(smFps)}",
                        )
                        transitionSmPhase(SM_PHASE_FIGHTER_HIT)
                    }
                }
            }

            SM_PHASE_FIGHTER_HIT -> {
                stabilizeSmShips(engine, playerFire = true, enemyFire = false)
                for (fighter in smEnemyFighters(engine)) {
                    smMaxFighterDisabled = maxOf(smMaxFighterDisabled, fighter.allWeapons.count { it.isDisabled })
                    if (fighter.maxHitpoints > 0f) {
                        smMinFighterHullRatio = minOf(smMinFighterHullRatio, fighter.hitpoints / fighter.maxHitpoints)
                    }
                    for (weapon in fighter.allWeapons) {
                        if (weapon.maxHealth > 0f) {
                            smMinWeaponHealthRatio = minOf(smMinWeaponHealthRatio, weapon.currHealth / weapon.maxHealth)
                        }
                        smMaxDisabledDuration = maxOf(smMaxDisabledDuration, weapon.disabledDuration)
                    }
                }
                if (bonus >= 1 && empHits >= 1 && empArcs >= 1 && smMaxFighterDisabled >= 1) {
                    ctx.log.info(
                        "[ASTD-Automation] sm fighter hit evidence: bonus=$bonus emp=$empHits arcs=$empArcs " +
                                "fighterDisabledMax=$smMaxFighterDisabled（战机武器熄火 + 逐武器电弧可读）",
                    )
                    smShipHitExplosionsBaseline = explosions
                    smShipHitAoeBaseline = aoeShipHits
                    smShipHitShieldBaseline = shieldHits
                    transitionSmPhase(SM_PHASE_SHIP_HIT)
                }
            }

            SM_PHASE_SHIP_HIT -> {
                // 清航母与残机：令导弹只剩舰船可咬（撞击舰船/护盾的真验证面）。
                if (!smCarrierCleared) {
                    smCarrierCleared = true
                    findSmCarrier(engine)?.let { engine.removeEntity(it) }
                    for (fighter in smEnemyFighters(engine)) engine.removeEntity(fighter)
                }
                stabilizeSmShips(engine, playerFire = true, enemyFire = false)
                if (explosions - smShipHitExplosionsBaseline >= SM_SHIP_HIT_MIN_EXPLOSIONS &&
                    aoeShipHits - smShipHitAoeBaseline >= 1 &&
                    shieldHits - smShipHitShieldBaseline >= 1
                ) {
                    ctx.log.info(
                        "[ASTD-Automation] sm ship hit evidence: explosionsDelta=${explosions - smShipHitExplosionsBaseline} " +
                                "aoeShipDelta=${aoeShipHits - smShipHitAoeBaseline} shieldDelta=${shieldHits - smShipHitShieldBaseline}" +
                                "（撞击舰船 AOE + 撞击护盾爆炸恒触发）",
                    )
                    smLineCrossStage = 0
                    smFeedAt = -1f
                    transitionSmPhase(SM_PHASE_LINE_CROSS)
                }
            }

            SM_PHASE_LINE_CROSS -> {
                // 撞线相位瞄准特例：发射指向最近敌导弹（lane 对头），保证有限携弹内必出相撞；
                // 目标选择遥测不因此出现导弹型（AI 自选目标，与发射指向无关）。
                val nearestFed = player?.let { p ->
                    engine.missiles.filter { it.owner != 0 }.minByOrNull { MathUtils.getDistance(p.location, it.location) }
                }
                stabilizeSmShips(engine, playerFire = true, enemyFire = false, aimOverride = nearestFed)
                // 撞线相位弹药续航（dev 舞台）：前序相位已耗尽 8+20 携弹，弹流中断则投喂弹
                // 无对头相撞机会（第五轮烟测实证 stage 1 弹尽超时）。
                launcher?.let { if (it.ammo <= 0) it.resetAmmo() }
                pod?.let { if (it.ammo <= 0) it.resetAmmo() }
                feedSmMissile(engine)
                // 撞线不追咬佐证：相位内目标选择遥测仍仅 战机/舰船 两型（TELE 键面无导弹型，
                // 编译期保证；此处日志固化运行时读数供 PR 证据）。
                if (smLineCrossStage == 0 && lineCross >= 1) {
                    smLineCrossBaseline = lineCross
                    smLineCrossStage = 1
                    smFeedAt = -1f
                    // 清全部在场敌导弹（不止已跟踪的低结构投喂弹），令 stage 1 撞线计数
                    // 差分只可能来自当帧后新投喂的增压弹。
                    for (missile in engine.missiles.toList()) {
                        if (missile.owner != 0) {
                            engine.removeEntity(missile)
                        }
                    }
                    ctx.log.info(
                        "[ASTD-Automation] sm line-cross evidence: atropos hp=300 < 阈值 600 同归于尽 lineCross=$lineCross " +
                                "selFighter=$selFighter selShip=$selShip（敌导弹海经过期间目标遥测无导弹型）",
                    )
                }
                if (smLineCrossStage == 1 && !smHighHpHitConfirmed) {
                    for (missile in engine.missiles) {
                        if (System.identityHashCode(missile) !in smFedHighMissiles) continue
                        if (missile.hitpoints < SM_HIGH_HP_BOOST && engine.isEntityInPlay(missile)) {
                            smHighHpHitConfirmed = true
                            ctx.log.info(
                                "[ASTD-Automation] sm high-hp evidence: harpoon ${SM_HIGH_HP_BOOST.toInt()}HP 被命中存活 hp=${"%.0f".format(missile.hitpoints)}" +
                                        " lineCrossDelta=${lineCross - smLineCrossBaseline}（> 阈值仅爆炸不移除）",
                            )
                        }
                    }
                }
                if (smLineCrossStage == 1 && smHighHpHitConfirmed) {
                    if (lineCross - smLineCrossBaseline > 0) {
                        ctx.failureReason = "sm high-hp stage lineCrossDelta=${lineCross - smLineCrossBaseline}, expect 0（增压弹远高于阈值不应触发撞线）"
                        transitionSmPhase(SM_PHASE_FAILED)
                    } else {
                        smScaleStep = 0
                        smScaleStepAt = -1f
                        DifficultyTuningImpl.installScaleForTests(1f)
                        transitionSmPhase(SM_PHASE_ENEMY_SCALE)
                    }
                }
            }

            SM_PHASE_ENEMY_SCALE -> {
                stabilizeSmShips(engine, playerFire = false, enemyFire = true)
                if (smScaleStepAt < 0f) smScaleStepAt = ctx.elapsed
                val expMultE = engine.customData[StellarMrmStrikeImpl.TELE_LAST_EXP_MULT + StellarMrmStrikeImpl.TELE_OWNER_ENEMY] as? Float
                val expected = SM_SCALE_EXPECTED_EXP_MULT[smScaleStep]
                if (expMultE != null && kotlin.math.abs(expMultE - expected) <= SM_SCALE_TOLERANCE) {
                    ctx.log.info(
                        "[ASTD-Automation] sm enemy scale evidence: k_s=${SM_SCALE_KS[smScaleStep]} expMult=$expMultE" +
                                "（敌版三档爆炸倍率 0.5/1.0/2.5 之 ${SM_SCALE_KS[smScaleStep]} 档）",
                    )
                    smScaleStep++
                    if (smScaleStep >= SM_SCALE_KS.size) {
                        DifficultyTuningImpl.installScaleForTests(null)
                        transitionSmPhase(SM_PHASE_COMPLETED)
                    } else {
                        DifficultyTuningImpl.installScaleForTests(SM_SCALE_KS[smScaleStep])
                        smScaleStepAt = -1f
                    }
                } else if (ctx.elapsed - smScaleStepAt > SM_SCALE_STEP_TIMEOUT) {
                    ctx.failureReason = "sm enemy scale timeout: k_s=${SM_SCALE_KS[smScaleStep]} lastExpMultE=$expMultE, expect $expected"
                    DifficultyTuningImpl.installScaleForTests(null)
                    transitionSmPhase(SM_PHASE_FAILED)
                }
            }

            SM_PHASE_COMPLETED -> {
                stabilizeSmShips(engine, playerFire = true, enemyFire = true)
                // 截图舞台弹药续航：令双方导弹流与裂隙爆炸持续入帧。
                launcher?.let { if (it.ammo <= 0) it.resetAmmo() }
                pod?.let { if (it.ammo <= 0) it.resetAmmo() }
                findSmLauncher(enemy)?.let { if (it.ammo <= 0) it.resetAmmo() }
            }
        }

        // 最近一次辉星爆炸时刻（COMPLETED 截图门控：事件近期发生才上报，令裂隙爆炸/双拖尾入帧）
        if (explosions > smLastTrackedExplosionCount) {
            smLastTrackedExplosionCount = explosions
            smLastExplosionAt = ctx.elapsed
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "sm ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            smPhase == SM_PHASE_FAILED -> "Failed"
            smPhase != SM_PHASE_COMPLETED &&
                    ctx.elapsed - smPhaseStartedAt > SM_PHASE_TIMEOUT -> {
                ctx.failureReason =
                    "sm phase timeout: $smPhase（selFighter=$selFighter selShip=$selShip bonus=$bonus emp=$empHits arcs=$empArcs explosions=$explosions lineCross=$lineCross disabledMax=$smMaxFighterDisabled weaponHpRatio=${
                        "%.2f".format(smMinWeaponHealthRatio)
                    } disabledDur=${"%.2f".format(smMaxDisabledDuration)} fighterHullRatio=${"%.2f".format(smMinFighterHullRatio)}）"
                "Failed"
            }

            smPhase == SM_PHASE_COMPLETED -> {
                val recentEvent = smLastExplosionAt >= 0f && ctx.elapsed - smLastExplosionAt <= SM_COMPLETED_EVENT_WINDOW
                if (recentEvent || ctx.elapsed - smPhaseStartedAt >= SM_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            DifficultyTuningImpl.installScaleForTests(null)
            ctx.log.info("[ASTD-Automation] Completed: stellar_mrm_basic mount/priority/fighter-hit/ship-hit/line-cross/enemy-scale evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, launcher)
        }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val smPlayer = findSmPlayer(engine)
        val smLauncher = findSmLauncher(smPlayer)
        val smPod = findSmPod(smPlayer)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 08 §4.2 烟测检查点）----
        json.appendLine("  \"smPhase\": \"$smPhase\",")
        json.appendLine("  \"smLauncherSlotId\": ${jsonString(smLauncher?.slot?.id)},")
        json.appendLine("  \"smPodSlotId\": ${jsonString(smPod?.slot?.id)},")
        json.appendLine("  \"smWeaponRange\": ${formatFloat(smLauncher?.range ?: -1f)},")
        json.appendLine("  \"smLauncherAmmo\": ${smLauncher?.ammo ?: -1},")
        json.appendLine("  \"smPodAmmo\": ${smPod?.ammo ?: -1},")
        json.appendLine("  \"smMinLauncherAmmo\": ${if (smMinLauncherAmmo == Int.MAX_VALUE) -1 else smMinLauncherAmmo},")
        json.appendLine("  \"smMinPodAmmo\": ${if (smMinPodAmmo == Int.MAX_VALUE) -1 else smMinPodAmmo},")
        json.appendLine("  \"smPodBurstMax\": $smPodBurstMax,")
        json.appendLine("  \"smFirstTarget\": ${jsonString(engine.customData[StellarMrmMissileAI.TELE_FIRST_TARGET] as? String)},")
        json.appendLine("  \"smSelFighter\": ${smTeleCount(engine, StellarMrmMissileAI.TELE_SEL_FIGHTER)},")
        json.appendLine("  \"smSelShip\": ${smTeleCount(engine, StellarMrmMissileAI.TELE_SEL_SHIP)},")
        json.appendLine("  \"smBonusHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_BONUS_HITS)},")
        json.appendLine("  \"smEmpHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_EMP_HITS)},")
        json.appendLine("  \"smEmpArcs\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_EMP_ARCS)},")
        json.appendLine("  \"smAoeHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_AOE_HITS)},")
        json.appendLine("  \"smAoeShipHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_AOE_SHIP_HITS)},")
        json.appendLine("  \"smShieldHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_SHIELD_HITS)},")
        json.appendLine("  \"smExplosions\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_EXPLOSIONS)},")
        json.appendLine("  \"smLineCross\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_LINE_CROSS)},")
        json.appendLine("  \"smLineCrossStage\": $smLineCrossStage,")
        json.appendLine("  \"smHighHpHitConfirmed\": $smHighHpHitConfirmed,")
        json.appendLine("  \"smMaxFighterDisabled\": $smMaxFighterDisabled,")
        json.appendLine("  \"smMinWeaponHealthRatio\": ${formatFloat(smMinWeaponHealthRatio)},")
        json.appendLine("  \"smMaxDisabledDuration\": ${formatFloat(smMaxDisabledDuration)},")
        json.appendLine("  \"smMinFighterHullRatio\": ${formatFloat(smMinFighterHullRatio)},")
        json.appendLine("  \"smLastFBonus\": ${formatFloat(engine.customData[StellarMrmStrikeImpl.TELE_LAST_F_BONUS + StellarMrmStrikeImpl.TELE_OWNER_PLAYER] as? Float ?: -1f)},")
        json.appendLine("  \"smLastWEmp\": ${formatFloat(engine.customData[StellarMrmStrikeImpl.TELE_LAST_W_EMP + StellarMrmStrikeImpl.TELE_OWNER_PLAYER] as? Float ?: -1f)},")
        json.appendLine("  \"smLastExpMultP\": ${formatFloat(engine.customData[StellarMrmStrikeImpl.TELE_LAST_EXP_MULT + StellarMrmStrikeImpl.TELE_OWNER_PLAYER] as? Float ?: -1f)},")
        json.appendLine("  \"smLastExpMultE\": ${formatFloat(engine.customData[StellarMrmStrikeImpl.TELE_LAST_EXP_MULT + StellarMrmStrikeImpl.TELE_OWNER_ENEMY] as? Float ?: -1f)},")
        json.appendLine("  \"smScaleStep\": $smScaleStep,")
        json.appendLine("  \"smFps\": ${formatFloat(smFps)},")
        json.appendLine("  \"smDevMode\": ${Global.getSettings().isDevMode},")
        json.appendLine("  \"smOwnLauncherProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.SM_LAUNCHER_PROJECTILE_SPEC_ID }},")
        json.appendLine("  \"smOwnPodProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.SM_POD_PROJECTILE_SPEC_ID }},")
        json.appendLine("  \"smEnemyMissilesInPlay\": ${engine.missiles.count { it.owner != 0 }},")
    }

    private companion object {
        // 辉星 MRM 场景：相位机、锚点与期望证据（规格 08 §4.2 烟测检查点）。
        private const val SM_PHASE_MOUNT = "MOUNT"
        private const val SM_PHASE_PRIORITY = "PRIORITY"
        private const val SM_PHASE_FIGHTER_HIT = "FIGHTER_HIT"
        private const val SM_PHASE_SHIP_HIT = "SHIP_HIT"
        private const val SM_PHASE_LINE_CROSS = "LINE_CROSS"
        private const val SM_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val SM_PHASE_COMPLETED = "COMPLETED"
        private const val SM_PHASE_FAILED = "FAILED"
        private const val SM_PLAYER_HULL = "gryphon"
        private const val SM_ENEMY_HULL = "gryphon"
        private const val SM_CARRIER_HULL = "condor"
        private const val SM_PLAYER_POD_SLOT = "WS 008"
        private const val SM_PLAYER_LAUNCHER_SLOT = "WS 010"
        private val SM_PLAYER_ANCHOR = Vector2f(-700f, 0f)
        private val SM_ENEMY_ANCHOR = Vector2f(900f, 0f)
        private val SM_CARRIER_ANCHOR = Vector2f(1200f, 400f)
        private val SM_CAMERA_CENTER = Vector2f(300f, 100f)
        private const val SM_CAMERA_VISIBLE_HEIGHT = 1500f
        private const val SM_MOUNT_SETTLE_SECONDS = 0.6f

        // MOUNT 相位校验：射程 2500 / ammo 8/20 / 发射舱 burst 2 / OP 4/10 / no_drop 两件套（weapon_data.csv 口径）。
        private const val SM_EXPECT_RANGE = 2500f
        private const val SM_RANGE_TOLERANCE = 5f
        private const val SM_LAUNCHER_AMMO = 8
        private const val SM_POD_AMMO = 20
        private const val SM_POD_BURST = 2
        private const val SM_LAUNCHER_OP = 4f
        private const val SM_POD_OP = 10f
        private val SM_REQUIRED_TAGS = setOf("no_drop", "no_drop_salvage")

        // PRIORITY：战机在场才开火 + 最短观察窗；发射舱两发分组间隔（burst delay 0，同帧两发常态）。
        private const val SM_PRIORITY_MIN_SECONDS = 4f
        private const val SM_BURST_GROUP_GAP = 0.5f

        // SHIP_HIT：相位内爆炸 ≥3 次且舰船 AOE 与护盾命中各 ≥1（撞击舰船/护盾爆炸恒触发证据）。
        private const val SM_SHIP_HIT_MIN_EXPLOSIONS = 3

        // LINE_CROSS 投喂：中场 lane 布点直线喂向玩家（与玩家导弹流对头相撞）。
        private const val SM_FEED_MISSILE_LOW_ID = "atropos"
        private const val SM_FEED_MISSILE_HIGH_ID = "harpoon"

        // 高结构增压值：阈值 600 之上必须不可达——stage 1 投喂点在敌舰处堆叠生成，弹流在
        // 敌舰附近命中的爆炸 AOE（半径 50、面板 100）会持续削低扎堆投喂弹血量；2000 口径
        // 被 AOE 削进 <600 撞线区间后遭流弹移除（被移除弹体不计入命中确认，同帧其它被波及
        // 弹体却满足确认），造成确定性伪失败（第八轮烟测实证）。20000 在阶段时长内需 ~97 次
        // AOE 削血才触及阈值，事实上不可达；确认采样仍在首次命中后下一帧完成。
        private const val SM_HIGH_HP_BOOST = 20000f
        private const val SM_FEED_INTERVAL = 0.6f
        private const val SM_FEED_LANES = 5
        private const val SM_FEED_LANE_GAP = 150f
        private const val SM_FEED_SPAWN_X = 100f
        private const val SM_FEED_SPEED = 200f

        // ENEMY_SCALE：敌版三档逐档（installScaleForTests 1/2/5 → 爆炸倍率 0.5/1.0/2.5）。
        private val SM_SCALE_KS = floatArrayOf(1f, 2f, 5f)
        private val SM_SCALE_EXPECTED_EXP_MULT = floatArrayOf(0.5f, 1.0f, 2.5f)
        private const val SM_SCALE_TOLERANCE = 0.001f
        private const val SM_SCALE_STEP_TIMEOUT = 30f

        // 敌方开火部署免疫闸（GD/SS/HIP 实机判例同款）。
        private const val SM_ENEMY_SETTLE_SECONDS = 4.0f

        // COMPLETED 截图门控：爆炸事件近 2.5s 内发生才上报（裂隙爆炸/双拖尾入帧）；保底舞台超时。
        private const val SM_COMPLETED_EVENT_WINDOW = 2.5f
        private const val SM_COMPLETED_STAGE_TIMEOUT = 30f
        private const val SM_PHASE_TIMEOUT = 90f
    }
}
