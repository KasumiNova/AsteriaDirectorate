package cn.kasuminova.astd.combat.automation.scenario.lens

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.PL_STAT_PROBE_ID
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceConeStrike
import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceVfx
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f

/**
 * 贯星之矛场景（规格 09 §4.2 烟测检查点）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class PiercingLanceScenario : AbstractAutomationScenario() {
    // ==== piercing lance 场景状态（相位机 MOUNT → CYCLE → CLUSTER → ENEMY_SCALE → COMPLETED） ====
    private var plPhase = PL_PHASE_MOUNT
    private var plPhaseStartedAt = 0f

    // MOUNT：能量结算探针分步（0=装配断言+基线 / 1=能量加成断言 / 2=实弹加成反证）。
    private var plMountStep = 0
    private var plMountStepAt = -1f
    private var plProbeR0 = -1f
    private var plProbeR1 = -1f
    private var plProbeR2 = -1f

    // CYCLE：出膛计时（7s 循环证据）与充能窗口观测（2s 充能条可读证据）。
    private val plSeenProjectiles = mutableSetOf<Int>()
    private val plSpawnTimes = mutableListOf<Float>()
    private var plChargeObserved = false
    private var plChargeStartAt = -1f
    private var plFirstChargeToShotSeconds = -1f
    private var plCycleIntervalSeconds = -1f

    // CYCLE：弹体 VFX 驱动接管闩（Static Trail 拖尾在线证据，弹体在飞窗口外 trackedCount 归零故闩存）。
    private var plVfxDriverSeen = false

    // CLUSTER：相位基线（锥面命中/浮字增量即本相位证据）。
    private var plClusterConeHitsBaseline = 0
    private var plClusterFloatyBaseline = 0
    private var plClusterMaxLastConeHits = 0

    // ENEMY_SCALE：敌版三档逐档观测（installScaleForTests 1/2/5 → 半角 20/25/40、锥长 300/375/600、伤害 2500/3125/5000）。
    private var plScaleStep = 0
    private var plScaleStepAt = -1f
    private var plScaleResolveBaseline = 0
    private var plScaleMaxConeHits = 0
    private var plScaleFpsTicks = 0
    private var plScaleFpsWallStartNanos = 0L
    private var plScaleFps = -1f

    // COMPLETED 截图门控：最近一次锥面结算时刻（截图帧需含大光柱/锥面/浮字）。
    private var plLastResolveAt = -1f
    private var plLastTrackedResolveCount = 0

    override val scenarioId: String = ASTDInGameAutomationScenario.PL_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPlEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockPlCamera(engine)
        // 锥面破片浮字与敌版三档仅 devMode 渲染（2026-07-29 审批裁定先例）：本场景为 dev-only 舞台，
        // 开启 devMode 以目检命中浮字/大光柱/锥面特效（进程被早退杀掉，设置不落盘）。
        Global.getSettings().isDevMode = true
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findPlShipA(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.PL_SCENARIO_ID} combat plugin initialized")
    }

    private fun findPlShipA(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == PL_PLAYER_A_HULL && !ship.isFighter }

    private fun findPlShipB(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == PL_PLAYER_B_HULL && !ship.isFighter }

    private fun findPlDecoys(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { ship -> ship.owner == 0 && ship.hullSpec?.hullId == PL_DECOY_HULL && !ship.isFighter }
            .sortedBy { System.identityHashCode(it) }

    private fun findPlEnemyTargets(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { ship -> ship.owner != 0 && ship.hullSpec?.hullId == PL_ENEMY_TARGET_HULL && !ship.isFighter }
            .sortedBy { System.identityHashCode(it) }

    private fun findPlEnemyLance(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == PL_ENEMY_LANCE_HULL && !ship.isFighter }

    private fun findPlLance(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.PL_WEAPON_ID }

    private fun plTeleCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private fun plTeleFloat(engine: CombatEngineAPI, key: String): Float = engine.customData[key] as? Float ?: -1f

    private fun lockPlCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try {
            Display.getWidth().takeIf { it > 0 } ?: 2560
        } catch (_: Throwable) {
            2560
        }
        val displayHeight = try {
            Display.getHeight().takeIf { it > 0 } ?: 1440
        } catch (_: Throwable) {
            1440
        }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleWidth = PL_CAMERA_VISIBLE_HEIGHT * displayAspect
        // 敌版三档相位起镜头切到东侧敌版舞台（x≈2500），此前锁定西侧主舞台。
        val center = if (plPhase == PL_PHASE_ENEMY_SCALE || plPhase == PL_PHASE_COMPLETED) {
            PL_CAMERA_CENTER_ENEMY
        } else {
            PL_CAMERA_CENTER_MAIN
        }
        viewport.isExternalControl = true
        viewport.set(
            center.x - visibleWidth * 0.5f,
            center.y - PL_CAMERA_VISIBLE_HEIGHT * 0.5f,
            visibleWidth,
            PL_CAMERA_VISIBLE_HEIGHT,
        )
        viewport.isEverythingNearViewport = true
    }

    /** 强制部署 mission reserves（范式同 deploySmReserveShips；三靶/两僚按身份哈希序分配锚点）。 */
    private fun deployPlReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            val members = manager.reservesCopy.toList()
                .sortedBy { member -> PL_DEPLOY_ORDER.indexOf(member.hullId).let { if (it < 0) Int.MAX_VALUE else it } }
            var decoyIndex = 0
            var targetIndex = 0
            for (member in members) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == PL_PLAYER_A_HULL -> PL_A_ANCHOR
                    side == FleetSide.PLAYER && member.hullId == PL_PLAYER_B_HULL -> PL_B_ANCHOR
                    side == FleetSide.PLAYER && member.hullId == PL_DECOY_HULL ->
                        if (decoyIndex++ == 0) PL_D1_PARK_ANCHOR else PL_D2_PARK_ANCHOR

                    side == FleetSide.ENEMY && member.hullId == PL_ENEMY_TARGET_HULL -> when (targetIndex++) {
                        0 -> PL_E1_ANCHOR
                        1 -> PL_E2_PARK_ANCHOR
                        else -> PL_E3_PARK_ANCHOR
                    }

                    side == FleetSide.ENEMY && member.hullId == PL_ENEMY_LANCE_HULL -> PL_ENEMY_LANCE_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY && member.hullId == PL_ENEMY_LANCE_HULL) PL_ENEMY_LANCE_FACING else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionPlPhase(next: String) {
        ctx.log.info("[ASTD-Automation] pl phase $plPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        plPhase = next
        plPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeSmShips）：八舰逐帧奶 + 辐能清零 + 钉死锚点 +
     * force fire 独占驱动（autofire 关闭）。E2/E3 与 D1/D2 锚点随相位切换（CYCLE 集群靶停远场，
     * CLUSTER 起并入弹道线；ENEMY_SCALE 起僚舰并入敌版弹道线）。
     * 敌方开火带部署免疫闸（GD/SS/HIP/SM 实机判例：reserves 手动 spawn 舰船部署后数秒内脚本
     * applyDamage 可能全额无效）。
     */
    private fun stabilizePlShips(engine: CombatEngineAPI, playerFire: Boolean, enemyFire: Boolean) {
        val shipA = findPlShipA(engine)
        val shipB = findPlShipB(engine)
        val decoys = findPlDecoys(engine)
        val targets = findPlEnemyTargets(engine)
        val enemyLance = findPlEnemyLance(engine)
        val clusterAnchored = plPhase != PL_PHASE_MOUNT && plPhase != PL_PHASE_CYCLE
        val enemyStageAnchored = plPhase == PL_PHASE_ENEMY_SCALE || plPhase == PL_PHASE_COMPLETED

        if (shipA != null && !shipA.isHulk) {
            engine.setPlayerShipExternal(shipA)
            stabilizeShip(shipA, PL_A_ANCHOR, 0f, allowFire = true, preserveAI = true)
            shipA.hitpoints = shipA.maxHitpoints
            shipA.fluxTracker.currFlux = 0f
            shipA.fluxTracker.hardFlux = 0f
            shipA.shield?.let { if (it.isOn) it.toggleOff() }
            setPlAutofire(shipA, false)
            val target = targets.firstOrNull()
            shipA.shipTarget = target
            findPlLance(shipA)?.let { weapon ->
                if (target != null) weapon.currAngle = Misc.getAngleInDegrees(weapon.location, target.location)
                weapon.setForceFireOneFrame(playerFire)
            }
        }
        // B：能量槽装配证明件，全程不开火（autofire 关闭 + 不 force fire），仅钉锚点奶血。
        if (shipB != null && !shipB.isHulk) {
            stabilizeShip(shipB, PL_B_ANCHOR, 0f, allowFire = false, preserveAI = true)
            shipB.hitpoints = shipB.maxHitpoints
            shipB.fluxTracker.currFlux = 0f
            shipB.fluxTracker.hardFlux = 0f
            shipB.shield?.let { if (it.isOn) it.toggleOff() }
            setPlAutofire(shipB, false)
        }
        decoys.forEachIndexed { index, decoy ->
            if (decoy.isHulk) return@forEachIndexed
            val staged = if (enemyStageAnchored) {
                if (index == 0) PL_D1_ANCHOR else PL_D2_ANCHOR
            } else {
                if (index == 0) PL_D1_PARK_ANCHOR else PL_D2_PARK_ANCHOR
            }
            stabilizeShip(decoy, staged, 0f, allowFire = false, preserveAI = true)
            decoy.hitpoints = decoy.maxHitpoints
            decoy.fluxTracker.currFlux = 0f
            decoy.fluxTracker.hardFlux = 0f
            decoy.shield?.let { if (it.isOn) it.toggleOff() }
        }
        targets.forEachIndexed { index, target ->
            if (target.isHulk) return@forEachIndexed
            val anchor = when (index) {
                0 -> PL_E1_ANCHOR
                1 -> if (clusterAnchored) PL_E2_CLUSTER_ANCHOR else PL_E2_PARK_ANCHOR
                else -> if (clusterAnchored) PL_E3_CLUSTER_ANCHOR else PL_E3_PARK_ANCHOR
            }
            stabilizeShip(target, anchor, 180f, allowFire = false, preserveAI = true)
            target.hitpoints = target.maxHitpoints
            target.fluxTracker.currFlux = 0f
            target.fluxTracker.hardFlux = 0f
            target.shield?.let { if (it.isOn) it.toggleOff() }
        }
        if (enemyLance != null && !enemyLance.isHulk) {
            stabilizeShip(enemyLance, PL_ENEMY_LANCE_ANCHOR, PL_ENEMY_LANCE_FACING, allowFire = true, preserveAI = true)
            enemyLance.hitpoints = enemyLance.maxHitpoints
            enemyLance.fluxTracker.currFlux = 0f
            enemyLance.fluxTracker.hardFlux = 0f
            enemyLance.shield?.let { if (it.isOn) it.toggleOff() }
            setPlAutofire(enemyLance, false)
            val target = decoys.firstOrNull()
            enemyLance.shipTarget = target
            findPlLance(enemyLance)?.let { weapon ->
                if (target != null) weapon.currAngle = Misc.getAngleInDegrees(weapon.location, target.location)
                val gated = enemyFire && ctx.elapsed - plScaleStepAt >= PL_ENEMY_SETTLE_SECONDS
                weapon.setForceFireOneFrame(gated)
            }
        }
    }

    /**
     * 贯星舞台外来实体清扫：第三方 mod 会向 mission 战斗注入中立战机/导弹（实机判例：中立 sarissa
     * 战机群 owner=100 + 归属不明导弹 owner=1 游荡进弹道线，2026-07-29 两次实机各 9/5 个锥面连带）。
     * 本场景全部自有舰船仅为 onslaught/champion/enforcer 三舰体、无任何战机与导弹武器，
     * 故每帧移除此三舰体以外的舰船（含战机）与全部导弹，保证 CYCLE「锥内零连带」舞台语义确定性。
     */
    private fun sweepPlForeignEntities(engine: CombatEngineAPI) {
        for (ship in ArrayList(engine.ships)) {
            val hullId = ship.hullSpec?.hullId
            if (hullId in PL_SCENARIO_HULLS && !ship.isFighter) continue
            ctx.log.info("[ASTD-Automation] pl 舞台外来舰船移除: hull=$hullId owner=${ship.owner} fighter=${ship.isFighter} loc=(${ship.location.x.toInt()},${ship.location.y.toInt()})")
            engine.removeEntity(ship)
        }
        for (missile in ArrayList(engine.missiles)) {
            ctx.log.info("[ASTD-Automation] pl 舞台外来导弹移除: spec=${missile.projectileSpecId} owner=${missile.owner} loc=(${missile.location.x.toInt()},${missile.location.y.toInt()})")
            engine.removeEntity(missile)
        }
    }

    /** 贯星武器组 autofire 总开关（范式同 setSmAutofire）：force fire 独占驱动时关闭。 */
    private fun setPlAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id == ASTDInGameAutomationScenario.PL_WEAPON_ID }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployPlReserveShips(engine)
        sweepPlForeignEntities(engine)
        lockPlCamera(engine)

        val shipA = findPlShipA(engine)
        val shipB = findPlShipB(engine)
        val lanceA = findPlLance(shipA)
        val lanceB = findPlLance(shipB)

        val resolves = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_RESOLVE)
        val coneHits = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_CONE_HITS)
        val floaty = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_FLOATY)
        val exemptViolations = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_DIRECT_EXEMPT_VIOLATION)
        val lastConeHits = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_LAST_CONE_HITS)
        val lastHalfAngle = plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_HALF_ANGLE)
        val lastRange = plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_RANGE)
        val lastDamage = plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_DAMAGE)
        val impactFlashes = plTeleCount(engine, PiercingLanceVfx.TELEMETRY_IMPACT_FLASH)
        val pillars = plTeleCount(engine, PiercingLanceVfx.TELEMETRY_PILLAR)
        val coneVfx = plTeleCount(engine, PiercingLanceVfx.TELEMETRY_CONE_VFX)

        when (plPhase) {
            PL_PHASE_MOUNT -> {
                stabilizePlShips(engine, playerFire = false, enemyFire = false)
                if (ctx.elapsed - plPhaseStartedAt >= PL_MOUNT_SETTLE_SECONDS) {
                    when (plMountStep) {
                        0 -> {
                            val slotA = lanceA?.slot
                            val slotB = lanceB?.slot
                            val spec = lanceA?.spec
                            val op = try {
                                spec?.getOrdnancePointCost(null, null) ?: -1f
                            } catch (_: Throwable) {
                                -1f
                            }
                            val tagsOk = spec?.tags?.containsAll(PL_REQUIRED_TAGS) == true
                            when {
                                lanceA == null || lanceB == null -> {
                                    ctx.failureReason = "pl mount missing: lanceA=${lanceA != null} lanceB=${lanceB != null}"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                slotA?.id != PL_A_SLOT || slotA?.weaponType != WeaponAPI.WeaponType.BALLISTIC ||
                                        slotA.slotSize != WeaponAPI.WeaponSize.LARGE -> {
                                    ctx.failureReason =
                                        "pl A slot mismatch: ${slotA?.id}/${slotA?.weaponType}/${slotA?.slotSize}（应为 $PL_A_SLOT BALLISTIC LARGE）"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                slotB?.id != PL_B_SLOT || slotB?.weaponType != WeaponAPI.WeaponType.ENERGY ||
                                        slotB.slotSize != WeaponAPI.WeaponSize.LARGE -> {
                                    ctx.failureReason =
                                        "pl B slot mismatch: ${slotB?.id}/${slotB?.weaponType}/${slotB?.slotSize}（应为 $PL_B_SLOT ENERGY LARGE）"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                spec?.type != WeaponAPI.WeaponType.ENERGY ||
                                        spec.mountType != WeaponAPI.WeaponType.HYBRID -> {
                                    ctx.failureReason =
                                        "pl spec type/mountType mismatch: ${spec?.type}/${spec?.mountType}（应为 ENERGY 结算 + HYBRID 挂载）"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                kotlin.math.abs((spec?.maxRange ?: -1f) - PL_EXPECT_RANGE) > PL_RANGE_TOLERANCE -> {
                                    ctx.failureReason = "pl spec maxRange=${spec?.maxRange}, expect $PL_EXPECT_RANGE"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                kotlin.math.abs(lanceA.cooldown - PL_EXPECT_COOLDOWN) > PL_COOLDOWN_TOLERANCE -> {
                                    ctx.failureReason = "pl cooldown=${lanceA.cooldown}, expect $PL_EXPECT_COOLDOWN"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                kotlin.math.abs(op - PL_EXPECT_OP) > 0.01f -> {
                                    ctx.failureReason = "pl OP=$op, expect $PL_EXPECT_OP"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                !tagsOk -> {
                                    ctx.failureReason = "pl tags 缺 no_drop 两件套: ${spec?.tags}"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID) -> {
                                    ctx.failureReason = "pl projectile VFX 未登记: ${ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID}"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                shipA == null -> {
                                    ctx.failureReason = "pl shipA missing for stat probe"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }

                                else -> {
                                    // 能量结算探针步骤 1：能量射程加成 +50%（检查点 1「按能量结算」正向证据）。
                                    plProbeR0 = lanceA.range
                                    shipA.mutableStats.energyWeaponRangeBonus.modifyPercent(PL_STAT_PROBE_ID, PL_STAT_PROBE_PERCENT)
                                    plMountStep = 1
                                    plMountStepAt = ctx.elapsed
                                    ctx.log.info("[ASTD-Automation] pl mount ok: A=${slotA.id}/${slotA.weaponType} B=${slotB?.id}/${slotB?.weaponType} spec=${spec.type}/${spec.mountType} range=${spec.maxRange} cooldown=${lanceA.cooldown} OP=$op r0=$plProbeR0")
                                }
                            }
                        }

                        1 -> if (ctx.elapsed - plMountStepAt >= PL_STAT_PROBE_SETTLE_SECONDS) {
                            if (lanceA == null || shipA == null) {
                                ctx.failureReason = "pl 探针步骤 1 舰船/武器丢失: lanceA=${lanceA != null} shipA=${shipA != null}"
                                transitionPlPhase(PL_PHASE_FAILED)
                            } else {
                                plProbeR1 = lanceA.range
                                shipA.mutableStats.energyWeaponRangeBonus.unmodifyPercent(PL_STAT_PROBE_ID)
                                shipA.mutableStats.ballisticWeaponRangeBonus.modifyPercent(PL_STAT_PROBE_ID, PL_STAT_PROBE_PERCENT)
                                plMountStep = 2
                                plMountStepAt = ctx.elapsed
                            }
                        }

                        else -> if (ctx.elapsed - plMountStepAt >= PL_STAT_PROBE_SETTLE_SECONDS) {
                            if (lanceA == null || shipA == null) {
                                ctx.failureReason = "pl 探针步骤 2 舰船/武器丢失: lanceA=${lanceA != null} shipA=${shipA != null}"
                                transitionPlPhase(PL_PHASE_FAILED)
                            } else {
                                plProbeR2 = lanceA.range
                                shipA.mutableStats.ballisticWeaponRangeBonus.unmodifyPercent(PL_STAT_PROBE_ID)
                                when {
                                    // 能量加成生效（+50% → ≥1.3× 宽松界）。
                                    plProbeR1 < plProbeR0 * PL_ENERGY_PROBE_MIN_RATIO -> {
                                        ctx.failureReason = "pl 能量结算探针失败：energyWeaponRangeBonus +50% 后 range $plProbeR0 → $plProbeR1（未生效）"
                                        transitionPlPhase(PL_PHASE_FAILED)
                                    }
                                    // 实弹加成不得生效（反证：按能量结算而非实弹）。
                                    kotlin.math.abs(plProbeR2 - plProbeR0) > PL_BALLISTIC_PROBE_TOLERANCE -> {
                                        ctx.failureReason =
                                            "pl 能量结算探针反证失败：ballisticWeaponRangeBonus +50% 后 range $plProbeR0 → $plProbeR2（实弹加成不应生效）"
                                        transitionPlPhase(PL_PHASE_FAILED)
                                    }

                                    else -> {
                                        ctx.log.info("[ASTD-Automation] pl energy settlement probe ok: r0=$plProbeR0 r1(energy+50%)=$plProbeR1 r2(ballistic+50%)=$plProbeR2")
                                        transitionPlPhase(PL_PHASE_CYCLE)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PL_PHASE_CYCLE -> {
                stabilizePlShips(engine, playerFire = true, enemyFire = false)
                // 充能窗口观测：chargeLevel ∈ (0,1) 即原版充能条在推进（2s 充能可读证据）。
                val chargeLevel = lanceA?.chargeLevel ?: 0f
                if (chargeLevel > 0.001f && chargeLevel < 0.999f) {
                    plChargeObserved = true
                    if (plChargeStartAt < 0f) plChargeStartAt = ctx.elapsed
                }
                // 出膛计时：玩家贯星弹体 spawn 时间序列（7s 循环证据）+ 首充耗时。
                for (projectile in engine.projectiles) {
                    if (projectile.projectileSpecId != ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID || projectile.owner != 0) continue
                    val id = System.identityHashCode(projectile)
                    if (!plSeenProjectiles.add(id)) continue
                    plSpawnTimes += ctx.elapsed
                    if (plSpawnTimes.size == 1 && plChargeStartAt >= 0f) {
                        plFirstChargeToShotSeconds = ctx.elapsed - plChargeStartAt
                    }
                    if (plSpawnTimes.size >= 2) {
                        plCycleIntervalSeconds = plSpawnTimes[plSpawnTimes.size - 1] - plSpawnTimes[plSpawnTimes.size - 2]
                    }
                }
                // 弹体 VFX 驱动接管闩：本场景唯一弹种为贯星弹（其余舰船全程不开火）。
                val vfxTelemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
                if (vfxTelemetry.trackedCount > 0 &&
                    vfxTelemetry.lastProjectileSpecId == ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID
                ) {
                    plVfxDriverSeen = true
                }
                if (resolves >= PL_CYCLE_MIN_HITS && plSpawnTimes.size >= PL_CYCLE_MIN_HITS) {
                    when {
                        !plChargeObserved -> {
                            ctx.failureReason = "pl 充能窗口未观测到（chargeLevel 恒 0 或恒 1，2s 充能条不可读）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }

                        plFirstChargeToShotSeconds < PL_FIRST_CHARGE_MIN || plFirstChargeToShotSeconds > PL_FIRST_CHARGE_MAX -> {
                            ctx.failureReason =
                                "pl 首充耗时=${"%.2f".format(plFirstChargeToShotSeconds)}s, expect ∈ [$PL_FIRST_CHARGE_MIN, $PL_FIRST_CHARGE_MAX]（2s 充能）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }

                        plCycleIntervalSeconds < PL_CYCLE_INTERVAL_MIN || plCycleIntervalSeconds > PL_CYCLE_INTERVAL_MAX -> {
                            ctx.failureReason =
                                "pl 出膛间隔=${"%.2f".format(plCycleIntervalSeconds)}s, expect ∈ [$PL_CYCLE_INTERVAL_MIN, $PL_CYCLE_INTERVAL_MAX]（2s 充能 + 5s 冷却 = 7s 循环）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }

                        !plVfxDriverSeen -> {
                            ctx.failureReason = "pl 弹体 VFX 驱动未观测（Static Trail 拖尾未接管弹体观感）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }

                        impactFlashes < 1 || pillars < 1 || coneVfx < 1 -> {
                            ctx.failureReason = "pl 命中三层特效计数不足: flash=$impactFlashes pillar=$pillars coneVfx=$coneVfx"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }

                        coneHits != 0 || floaty != 0 -> {
                            ctx.failureReason = "pl 命中单体出现连带: coneHits=$coneHits floaty=$floaty（单体靶锥内应零连带）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }

                        kotlin.math.abs(lastHalfAngle - 25f) > PL_SCALE_TOLERANCE ||
                                kotlin.math.abs(lastRange - 375f) > PL_SCALE_TOLERANCE ||
                                kotlin.math.abs(lastDamage - 3125f) > PL_SCALE_TOLERANCE -> {
                            ctx.failureReason = "pl 玩家恒 v2 读数偏差: halfAngle=$lastHalfAngle range=$lastRange damage=$lastDamage（应 25/375/3125）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }

                        else -> {
                            ctx.log.info(
                                "[ASTD-Automation] pl cycle ok: firstCharge=${"%.2f".format(plFirstChargeToShotSeconds)}s " +
                                        "interval=${"%.2f".format(plCycleIntervalSeconds)}s shots=${plSpawnTimes.size} " +
                                        "flash=$impactFlashes pillar=$pillars coneVfx=$coneVfx 单体零连带 v2=25/375/3125",
                            )
                            plClusterConeHitsBaseline = coneHits
                            plClusterFloatyBaseline = floaty
                            plClusterMaxLastConeHits = 0
                            transitionPlPhase(PL_PHASE_CLUSTER)
                        }
                    }
                }
            }

            PL_PHASE_CLUSTER -> {
                stabilizePlShips(engine, playerFire = true, enemyFire = false)
                plClusterMaxLastConeHits = maxOf(plClusterMaxLastConeHits, lastConeHits)
                val coneDelta = coneHits - plClusterConeHitsBaseline
                val floatyDelta = floaty - plClusterFloatyBaseline
                if (plClusterMaxLastConeHits >= PL_CLUSTER_MIN_CONE_HITS && floatyDelta >= PL_CLUSTER_MIN_CONE_HITS) {
                    if (exemptViolations != 0) {
                        ctx.failureReason = "pl 命中本体豁免契约被破坏: violations=$exemptViolations"
                        transitionPlPhase(PL_PHASE_FAILED)
                    } else {
                        ctx.log.info("[ASTD-Automation] pl cluster ok: lastConeHits=$plClusterMaxLastConeHits coneDelta=$coneDelta floatyDelta=$floatyDelta exemptViolations=0")
                        plScaleStep = 0
                        plScaleResolveBaseline = resolves
                        DifficultyTuningImpl.installScaleForTests(PL_SCALE_KS[0])
                        plScaleStepAt = ctx.elapsed
                        plScaleMaxConeHits = 0
                        transitionPlPhase(PL_PHASE_ENEMY_SCALE)
                    }
                }
            }

            PL_PHASE_ENEMY_SCALE -> {
                // 敌版逐档：玩家停火（LAST_* 读数唯一归因敌版），敌版贯星打僚舰集群。
                stabilizePlShips(engine, playerFire = false, enemyFire = true)
                plScaleMaxConeHits = maxOf(plScaleMaxConeHits, lastConeHits)
                if (plScaleStep == PL_SCALE_KS.lastIndex) {
                    plScaleFpsTicks++
                }
                if (resolves > plScaleResolveBaseline && ctx.elapsed - plScaleStepAt >= PL_ENEMY_SETTLE_SECONDS) {
                    val expectHalfAngle = PL_SCALE_EXPECTED_HALF_ANGLE[plScaleStep]
                    val expectRange = PL_SCALE_EXPECTED_RANGE[plScaleStep]
                    val expectDamage = PL_SCALE_EXPECTED_DAMAGE[plScaleStep]
                    if (kotlin.math.abs(lastHalfAngle - expectHalfAngle) > PL_SCALE_TOLERANCE ||
                        kotlin.math.abs(lastRange - expectRange) > PL_SCALE_TOLERANCE ||
                        kotlin.math.abs(lastDamage - expectDamage) > PL_SCALE_TOLERANCE
                    ) {
                        ctx.failureReason =
                            "pl 敌版 k_s=${PL_SCALE_KS[plScaleStep]} 读数偏差: halfAngle=$lastHalfAngle range=$lastRange damage=$lastDamage（应 $expectHalfAngle/$expectRange/$expectDamage）"
                        transitionPlPhase(PL_PHASE_FAILED)
                    } else if (plScaleStep < PL_SCALE_KS.lastIndex) {
                        ctx.log.info("[ASTD-Automation] pl enemy scale k_s=${PL_SCALE_KS[plScaleStep]} ok: $lastHalfAngle/$lastRange/$lastDamage")
                        plScaleStep++
                        DifficultyTuningImpl.installScaleForTests(PL_SCALE_KS[plScaleStep])
                        plScaleResolveBaseline = resolves
                        plScaleStepAt = ctx.elapsed
                        if (plScaleStep == PL_SCALE_KS.lastIndex) {
                            plScaleFpsTicks = 0
                            plScaleFpsWallStartNanos = System.nanoTime()
                        }
                    } else {
                        // 破晓档：僚舰必须被锥面波及（80°/600su 放大证据）+ 帧率采样收口。
                        val wallSeconds = (System.nanoTime() - plScaleFpsWallStartNanos) / 1_000_000_000f
                        if (wallSeconds > 0f) plScaleFps = plScaleFpsTicks / wallSeconds
                        when {
                            plScaleMaxConeHits < 1 -> {
                                ctx.failureReason = "pl 破晓档锥面未波及僚舰: maxConeHits=$plScaleMaxConeHits（80°/600su 放大未生效）"
                                transitionPlPhase(PL_PHASE_FAILED)
                            }

                            plScaleFps > 0f && plScaleFps < PL_MIN_FPS -> {
                                ctx.failureReason = "pl 破晓档帧率塌陷: fps=${"%.1f".format(plScaleFps)} < $PL_MIN_FPS"
                                transitionPlPhase(PL_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] pl enemy scale k_s=5 ok: 40/600/5000 coneHits=$plScaleMaxConeHits fps=${
                                        "%.1f".format(
                                            plScaleFps
                                        )
                                    }"
                                )
                                transitionPlPhase(PL_PHASE_COMPLETED)
                            }
                        }
                    }
                }
            }

            PL_PHASE_COMPLETED -> {
                stabilizePlShips(engine, playerFire = true, enemyFire = true)
            }
        }

        // 最近一次锥面结算时刻（COMPLETED 截图门控：事件近期发生才上报，令大光柱/锥面/浮字入帧）
        if (resolves > plLastTrackedResolveCount) {
            plLastTrackedResolveCount = resolves
            plLastResolveAt = ctx.elapsed
        }

        val state = when {
            shipA == null || shipB == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "pl ships missing: shipA=${shipA != null}, shipB=${shipB != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            plPhase == PL_PHASE_FAILED -> "Failed"
            plPhase != PL_PHASE_COMPLETED &&
                    ctx.elapsed - plPhaseStartedAt > plPhaseTimeout() -> {
                ctx.failureReason =
                    "pl phase timeout: $plPhase（resolves=$resolves coneHits=$coneHits floaty=$floaty shots=${plSpawnTimes.size} scaleStep=$plScaleStep chargeObserved=$plChargeObserved）"
                "Failed"
            }

            plPhase == PL_PHASE_COMPLETED -> {
                val recentEvent = plLastResolveAt >= 0f && ctx.elapsed - plLastResolveAt <= PL_COMPLETED_EVENT_WINDOW
                if (recentEvent || ctx.elapsed - plPhaseStartedAt >= PL_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            DifficultyTuningImpl.installScaleForTests(null)
            ctx.log.info("[ASTD-Automation] Completed: piercing_lance_basic mount/cycle/cluster/enemy-scale evidence observed")
        }
        if (state == "Failed") {
            DifficultyTuningImpl.installScaleForTests(null)
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, shipA)
            ctx.writeTelemetry(state, shipA, lanceA)
        }
    }

    /** 分相位超时：ENEMY_SCALE 需三档 ×（部署免疫闸 + 7s 循环）故放宽。 */
    private fun plPhaseTimeout(): Float = when (plPhase) {
        PL_PHASE_ENEMY_SCALE -> PL_ENEMY_SCALE_PHASE_TIMEOUT
        else -> PL_PHASE_TIMEOUT
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val plShipA = findPlShipA(engine)
        val plShipB = findPlShipB(engine)
        val plLanceA = findPlLance(plShipA)
        val plLanceB = findPlLance(plShipB)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 09 §4.2 烟测检查点）----
        json.appendLine("  \"plPhase\": \"$plPhase\",")
        json.appendLine("  \"plLanceASlotId\": ${jsonString(plLanceA?.slot?.id)},")
        json.appendLine("  \"plLanceASlotType\": ${jsonString(plLanceA?.slot?.weaponType?.name)},")
        json.appendLine("  \"plLanceBSlotId\": ${jsonString(plLanceB?.slot?.id)},")
        json.appendLine("  \"plLanceBSlotType\": ${jsonString(plLanceB?.slot?.weaponType?.name)},")
        json.appendLine("  \"plSpecType\": ${jsonString(plLanceA?.spec?.type?.name)},")
        json.appendLine("  \"plSpecMountType\": ${jsonString(plLanceA?.spec?.mountType?.name)},")
        json.appendLine("  \"plProbeR0\": ${formatFloat(plProbeR0)},")
        json.appendLine("  \"plProbeR1\": ${formatFloat(plProbeR1)},")
        json.appendLine("  \"plProbeR2\": ${formatFloat(plProbeR2)},")
        json.appendLine("  \"plChargeObserved\": $plChargeObserved,")
        json.appendLine("  \"plFirstChargeToShotSeconds\": ${formatFloat(plFirstChargeToShotSeconds)},")
        json.appendLine("  \"plCycleIntervalSeconds\": ${formatFloat(plCycleIntervalSeconds)},")
        json.appendLine("  \"plShotsFired\": ${plSpawnTimes.size},")
        json.appendLine("  \"plVfxDriverSeen\": $plVfxDriverSeen,")
        json.appendLine("  \"plResolves\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_RESOLVE)},")
        json.appendLine("  \"plImpactFlashes\": ${plTeleCount(engine, PiercingLanceVfx.TELEMETRY_IMPACT_FLASH)},")
        json.appendLine("  \"plPillars\": ${plTeleCount(engine, PiercingLanceVfx.TELEMETRY_PILLAR)},")
        json.appendLine("  \"plConeVfx\": ${plTeleCount(engine, PiercingLanceVfx.TELEMETRY_CONE_VFX)},")
        json.appendLine("  \"plConeHits\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_CONE_HITS)},")
        json.appendLine("  \"plFloaty\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_FLOATY)},")
        json.appendLine("  \"plExemptViolations\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_DIRECT_EXEMPT_VIOLATION)},")
        json.appendLine("  \"plLastConeHits\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_LAST_CONE_HITS)},")
        json.appendLine("  \"plLastHalfAngle\": ${formatFloat(plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_HALF_ANGLE))},")
        json.appendLine("  \"plLastRange\": ${formatFloat(plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_RANGE))},")
        json.appendLine("  \"plLastDamage\": ${formatFloat(plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_DAMAGE))},")
        json.appendLine("  \"plClusterMaxLastConeHits\": $plClusterMaxLastConeHits,")
        json.appendLine("  \"plScaleStep\": $plScaleStep,")
        json.appendLine("  \"plScaleMaxConeHits\": $plScaleMaxConeHits,")
        json.appendLine("  \"plScaleFps\": ${formatFloat(plScaleFps)},")
        json.appendLine("  \"plOwnLanceProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID && it.owner == 0 }},")
        json.appendLine("  \"plDevMode\": ${Global.getSettings().isDevMode},")
    }

    private companion object {
        // 贯星之矛场景：相位机、锚点与期望证据（规格 09 §4.2 烟测检查点）。
        private const val PL_PHASE_MOUNT = "MOUNT"
        private const val PL_PHASE_CYCLE = "CYCLE"
        private const val PL_PHASE_CLUSTER = "CLUSTER"
        private const val PL_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val PL_PHASE_COMPLETED = "COMPLETED"
        private const val PL_PHASE_FAILED = "FAILED"
        private const val PL_PLAYER_A_HULL = "onslaught"
        private const val PL_PLAYER_B_HULL = "champion"
        private const val PL_DECOY_HULL = "enforcer"
        private const val PL_ENEMY_TARGET_HULL = "enforcer"
        private const val PL_ENEMY_LANCE_HULL = "champion"

        // A=onslaught 前向大型实弹炮塔（angle 0 / arc 150）；B/敌版=champion 前向大型能量炮塔（angle 0 / arc 130）。
        private const val PL_A_SLOT = "WS 019"
        private const val PL_B_SLOT = "WS 008"

        // reserves 部署序：mission addToFleet 顺序与 hull 计数（三靶两僚按序分锚点）。
        private val PL_DEPLOY_ORDER = listOf("onslaught", "champion", "enforcer")

        // 舞台自有舰体集合：本场景全部舰船（A/B 射手 + 两僚 + 三靶 + 敌版射手）仅这三种舰体；
        // 其余舰船（含全部战机）与导弹一律视为第三方 mod 舞台污染，由 sweepPlForeignEntities 逐帧移除。
        private val PL_SCENARIO_HULLS = setOf("onslaught", "champion", "enforcer")

        // 西侧主舞台：A 主射手 + E1 单体靶 + E2/E3 集群靶（v2 锥 375su/半角 25° 几何：命中点≈(45,0) 起算）。
        private val PL_A_ANCHOR = Vector2f(-950f, 0f)
        private val PL_B_ANCHOR = Vector2f(-950f, -600f)
        private val PL_E1_ANCHOR = Vector2f(150f, 0f)
        private val PL_E2_CLUSTER_ANCHOR = Vector2f(355f, 120f)
        private val PL_E3_CLUSTER_ANCHOR = Vector2f(355f, -120f)
        private val PL_E2_PARK_ANCHOR = Vector2f(1500f, 300f)
        private val PL_E3_PARK_ANCHOR = Vector2f(1500f, 500f)

        // 东侧敌版舞台：敌版贯星北射南（facing 270），僚舰 D1/D2 沿敌版弹道线（v5 锥 600su/半角 40° 几何）。
        private val PL_ENEMY_LANCE_ANCHOR = Vector2f(2500f, 800f)
        private const val PL_ENEMY_LANCE_FACING = 270f
        private val PL_D1_ANCHOR = Vector2f(2500f, 0f)
        private val PL_D2_ANCHOR = Vector2f(2630f, -180f)
        private val PL_D1_PARK_ANCHOR = Vector2f(2500f, 2000f)
        private val PL_D2_PARK_ANCHOR = Vector2f(2630f, 2180f)
        private val PL_CAMERA_CENTER_MAIN = Vector2f(-200f, 0f)
        private val PL_CAMERA_CENTER_ENEMY = Vector2f(2500f, 100f)
        private const val PL_CAMERA_VISIBLE_HEIGHT = 1500f

        // MOUNT 相位校验：射程 1000 / 冷却 5s / OP 30 / no_drop 两件套（weapon_data.csv 口径）。
        private const val PL_MOUNT_SETTLE_SECONDS = 1.0f
        private const val PL_EXPECT_RANGE = 1000f
        private const val PL_RANGE_TOLERANCE = 1f
        private const val PL_EXPECT_COOLDOWN = 5.0f
        private const val PL_COOLDOWN_TOLERANCE = 0.5f
        private const val PL_EXPECT_OP = 30f
        private val PL_REQUIRED_TAGS = setOf("no_drop", "no_drop_salvage")

        private const val PL_STAT_PROBE_PERCENT = 50f
        private const val PL_STAT_PROBE_SETTLE_SECONDS = 0.3f
        private const val PL_ENERGY_PROBE_MIN_RATIO = 1.3f
        private const val PL_BALLISTIC_PROBE_TOLERANCE = 1f

        // CYCLE：首充 2s（窗口 [1.2, 3.0]）；出膛间隔 7s = 充能 2s + 冷却 5s（窗口 [6.0, 8.5]，帧粒度宽松界）。
        private const val PL_CYCLE_MIN_HITS = 2
        private const val PL_FIRST_CHARGE_MIN = 1.2f
        private const val PL_FIRST_CHARGE_MAX = 3.0f
        private const val PL_CYCLE_INTERVAL_MIN = 6.0f
        private const val PL_CYCLE_INTERVAL_MAX = 8.5f

        // CLUSTER：单次锥面结算命中 ≥2（E2+E3 并入弹道线）+ 破片浮字 ≥2 + 本体豁免契约零破坏。
        private const val PL_CLUSTER_MIN_CONE_HITS = 2

        // ENEMY_SCALE：敌版三档逐档（installScaleForTests 1/2/5 → 半角 20/25/40、锥长 300/375/600、伤害 2500/3125/5000）。
        private val PL_SCALE_KS = floatArrayOf(1f, 2f, 5f)
        private val PL_SCALE_EXPECTED_HALF_ANGLE = floatArrayOf(20f, 25f, 40f)
        private val PL_SCALE_EXPECTED_RANGE = floatArrayOf(300f, 375f, 600f)
        private val PL_SCALE_EXPECTED_DAMAGE = floatArrayOf(2500f, 3125f, 5000f)
        private const val PL_SCALE_TOLERANCE = 0.01f

        // 敌方开火部署免疫闸（GD/SS/HIP/SM 实机判例同款）。
        private const val PL_ENEMY_SETTLE_SECONDS = 2.0f
        private const val PL_MIN_FPS = 45f

        // COMPLETED 截图门控：锥面结算事件近 2.5s 内发生才上报（大光柱/锥面/浮字入帧）；保底舞台超时。
        private const val PL_COMPLETED_EVENT_WINDOW = 2.5f
        private const val PL_COMPLETED_STAGE_TIMEOUT = 30f
        private const val PL_PHASE_TIMEOUT = 60f
        private const val PL_ENEMY_SCALE_PHASE_TIMEOUT = 120f
    }
}
