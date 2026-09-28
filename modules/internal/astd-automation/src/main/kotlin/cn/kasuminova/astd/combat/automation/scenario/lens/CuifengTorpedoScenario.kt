package cn.kasuminova.astd.combat.automation.scenario.lens

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoAI
import cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoStrikeImpl
import cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoVfx
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 摧锋鱼雷场景（玩家版基础验证）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class CuifengTorpedoScenario : AbstractAutomationScenario() {
    // ==== 摧锋鱼雷场景状态（相位机 MOUNT → STRIKE → COMPLETED；玩家版基础验证，无敌版三档） ====
    private var cuifengPhase = CUIFENG_PHASE_MOUNT
    private var cuifengPhaseStartedAt = 0f

    // COMPLETED 截图门控：最近一次摧锋命中特效时刻（截图帧需含十字辉星/星云/新鲜拖尾）。
    private var cuifengLastImpactVfxAt = -1f
    private var cuifengLastTrackedImpactVfxCount = 0

    override val scenarioId: String = ASTDInGameAutomationScenario.CUIFENG_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isCuifengEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockCuifengCamera(engine)
        // 硬辐推进/自适应增伤浮字仅 devMode 渲染（2026-07-29 审批裁定先例）：本场景为 dev-only 舞台，
        // 开启 devMode 以目检命中浮字（进程被早退杀掉，设置不落盘）。
        Global.getSettings().isDevMode = true
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findCuifengPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.CUIFENG_SCENARIO_ID} combat plugin initialized")
    }

    private fun findCuifengPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == CUIFENG_PLAYER_HULL && !ship.isFighter }

    private fun findCuifengCruiser(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == CUIFENG_CRUISER_HULL && !ship.isFighter }

    private fun findCuifengFrigate(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == CUIFENG_FRIGATE_HULL && !ship.isFighter }

    private fun findCuifengTorpedo(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.CUIFENG_TORPEDO_WEAPON_ID }

    private fun findCuifengLauncher(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.CUIFENG_LAUNCHER_WEAPON_ID }

    private fun cuifengTeleCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private fun lockCuifengCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, CUIFENG_CAMERA_CENTER, CUIFENG_CAMERA_VISIBLE_HEIGHT)
    }

    /** 强制部署 mission reserves（玩家狮鹫 / 敌方猎鹰级巡洋舰 / 敌方猎犬级护卫舰均非旗舰，范式同 deploySmReserveShips）。 */
    private fun deployCuifengReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == CUIFENG_PLAYER_HULL -> CUIFENG_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == CUIFENG_CRUISER_HULL -> CUIFENG_CRUISER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == CUIFENG_FRIGATE_HULL -> CUIFENG_FRIGATE_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionCuifengPhase(next: String) {
        ctx.log.info("[ASTD-Automation] cuifeng phase $cuifengPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        cuifengPhase = next
        cuifengPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeSmShips）：三舰逐帧奶 + 辐能清零 + 钉死锚点 +
     * force fire 独占驱动（autofire 关闭）。玩家武器瞄准敌方巡洋舰（反舰口径最近目标，
     * 鱼雷 AI 自选目标与发射指向一致）；巡洋舰盾常开（护盾命中硬辐推进观测面），
     * 猎犬级护卫舰无盾贴身摆放（150su 全额面板 AOE 连带观测面）。
     */
    private fun stabilizeCuifengShips(engine: CombatEngineAPI, playerFire: Boolean) {
        val player = findCuifengPlayer(engine)
        val cruiser = findCuifengCruiser(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, CUIFENG_PLAYER_ANCHOR, 0f, allowFire = true, preserveAI = true)
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            player.fluxTracker.hardFlux = 0f
            player.shield?.let { if (it.isOn) it.toggleOff() }
            setCuifengAutofire(player, false)
            player.shipTarget = cruiser
            for (weapon in listOfNotNull(findCuifengTorpedo(player), findCuifengLauncher(player))) {
                if (cruiser != null) weapon.currAngle = Misc.getAngleInDegrees(weapon.location, cruiser.location)
                weapon.setForceFireOneFrame(playerFire)
            }
        }
        if (cruiser != null && !cruiser.isHulk) {
            stabilizeShip(cruiser, CUIFENG_CRUISER_ANCHOR, 180f, allowFire = false)
            cruiser.hitpoints = cruiser.maxHitpoints
            cruiser.fluxTracker.currFlux = 0f
            cruiser.fluxTracker.hardFlux = 0f
            // 盾常开：鱼雷直击落盾触发硬辐推进（辐能逐帧清零不影响遥测计数，只保舞台不超载）。
            cruiser.shield?.let { if (!it.isOn) it.toggleOn() }
        }
        val frigate = findCuifengFrigate(engine)
        if (frigate != null && !frigate.isHulk) {
            stabilizeShip(frigate, CUIFENG_FRIGATE_ANCHOR, 180f, allowFire = false)
            frigate.hitpoints = frigate.maxHitpoints
            frigate.fluxTracker.currFlux = 0f
            frigate.fluxTracker.hardFlux = 0f
            frigate.shield?.let { if (it.isOn) it.toggleOff() }
        }
    }

    /** 摧锋武器组 autofire 总开关（范式同 setSmAutofire）：force fire 独占驱动时关闭。 */
    private fun setCuifengAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none {
                    it.id == ASTDInGameAutomationScenario.CUIFENG_TORPEDO_WEAPON_ID ||
                            it.id == ASTDInGameAutomationScenario.CUIFENG_LAUNCHER_WEAPON_ID
                }
            ) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployCuifengReserveShips(engine)
        lockCuifengCamera(engine)

        val player = findCuifengPlayer(engine)
        val cruiser = findCuifengCruiser(engine)
        val frigate = findCuifengFrigate(engine)
        val torpedo = findCuifengTorpedo(player)
        val launcher = findCuifengLauncher(player)

        val targetSelected = cuifengTeleCount(engine, CuifengTorpedoAI.TELE_TARGET_SELECTED)
        val governedFrames = cuifengTeleCount(engine, CuifengTorpedoAI.TELE_GOVERNED_FRAMES)
        val adaptiveHits = cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_ADAPTIVE_HITS)
        val hardFluxPushes = cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_HARD_FLUX_PUSHES)
        val aoeHits = cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_AOE_HITS)
        val aoeShipHits = cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_AOE_SHIP_HITS)
        val impactVfx = cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_IMPACT_VFX)
        val crossFlare = cuifengTeleCount(engine, CuifengTorpedoVfx.TELEMETRY_CROSS_FLARE)
        val nebulaBurst = cuifengTeleCount(engine, CuifengTorpedoVfx.TELEMETRY_NEBULA_BURST)

        when (cuifengPhase) {
            CUIFENG_PHASE_MOUNT -> {
                stabilizeCuifengShips(engine, playerFire = false)
                if (ctx.elapsed - cuifengPhaseStartedAt >= CUIFENG_MOUNT_SETTLE_SECONDS) {
                    val torpedoSlot = torpedo?.slot?.id
                    val launcherSlot = launcher?.slot?.id
                    val torpedoRange = torpedo?.spec?.maxRange ?: -1f
                    val launcherRange = launcher?.spec?.maxRange ?: -1f
                    val torpedoOp = try {
                        torpedo?.spec?.getOrdnancePointCost(null, null) ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                    val launcherOp = try {
                        launcher?.spec?.getOrdnancePointCost(null, null) ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                    val tagsOk = torpedo?.spec?.tags?.containsAll(CUIFENG_REQUIRED_TAGS) == true &&
                            launcher?.spec?.tags?.containsAll(CUIFENG_REQUIRED_TAGS) == true
                    val slotOk = torpedo?.slot?.slotSize == WeaponAPI.WeaponSize.SMALL &&
                            torpedo?.slot?.weaponType == WeaponAPI.WeaponType.MISSILE &&
                            launcher?.slot?.slotSize == WeaponAPI.WeaponSize.MEDIUM &&
                            launcher?.slot?.weaponType == WeaponAPI.WeaponType.MISSILE
                    when {
                        torpedo == null || launcher == null ||
                                torpedoSlot != CUIFENG_PLAYER_TORPEDO_SLOT || launcherSlot != CUIFENG_PLAYER_LAUNCHER_SLOT -> {
                            ctx.failureReason = "cuifeng mount mismatch: torpedoSlot=$torpedoSlot launcherSlot=$launcherSlot"
                            transitionCuifengPhase(CUIFENG_PHASE_FAILED)
                        }

                        !slotOk -> {
                            ctx.failureReason =
                                "cuifeng slot type/size mismatch: torpedo=${torpedo.slot?.slotSize}/${torpedo.slot?.weaponType} launcher=${launcher.slot?.slotSize}/${launcher.slot?.weaponType}"
                            transitionCuifengPhase(CUIFENG_PHASE_FAILED)
                        }

                        kotlin.math.abs(torpedoRange - CUIFENG_EXPECT_RANGE) > CUIFENG_RANGE_TOLERANCE ||
                                kotlin.math.abs(launcherRange - CUIFENG_EXPECT_RANGE) > CUIFENG_RANGE_TOLERANCE -> {
                            ctx.failureReason = "cuifeng range=$torpedoRange/$launcherRange, expect $CUIFENG_EXPECT_RANGE"
                            transitionCuifengPhase(CUIFENG_PHASE_FAILED)
                        }

                        torpedo.spec?.maxAmmo != CUIFENG_TORPEDO_AMMO || launcher.spec?.maxAmmo != CUIFENG_LAUNCHER_AMMO -> {
                            ctx.failureReason = "cuifeng spec maxAmmo=${torpedo.spec?.maxAmmo}/${launcher.spec?.maxAmmo}, expect $CUIFENG_TORPEDO_AMMO/$CUIFENG_LAUNCHER_AMMO"
                            transitionCuifengPhase(CUIFENG_PHASE_FAILED)
                        }

                        kotlin.math.abs(torpedoOp - CUIFENG_TORPEDO_OP) > 0.01f || kotlin.math.abs(launcherOp - CUIFENG_LAUNCHER_OP) > 0.01f -> {
                            ctx.failureReason = "cuifeng OP=$torpedoOp/$launcherOp, expect $CUIFENG_TORPEDO_OP/$CUIFENG_LAUNCHER_OP"
                            transitionCuifengPhase(CUIFENG_PHASE_FAILED)
                        }

                        !tagsOk -> {
                            ctx.failureReason = "cuifeng tags 缺 no_drop 两件套: torpedo=${torpedo.spec?.tags} launcher=${launcher.spec?.tags}"
                            transitionCuifengPhase(CUIFENG_PHASE_FAILED)
                        }

                        !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.CUIFENG_PROJECTILE_SPEC_ID) -> {
                            ctx.failureReason = "cuifeng projectile VFX 未登记: torpedo shot"
                            transitionCuifengPhase(CUIFENG_PHASE_FAILED)
                        }

                        else -> {
                            ctx.log.info(
                                "[ASTD-Automation] cuifeng mount ok: slots=$torpedoSlot/$launcherSlot range=$torpedoRange " +
                                        "ammo=${torpedo.spec?.maxAmmo}/${launcher.spec?.maxAmmo} " +
                                        "OP=$torpedoOp/$launcherOp tags=no_drop 两件套",
                            )
                            transitionCuifengPhase(CUIFENG_PHASE_STRIKE)
                        }
                    }
                }
            }

            CUIFENG_PHASE_STRIKE -> {
                stabilizeCuifengShips(engine, playerFire = true)
                // 携弹续航（dev 舞台）：2+5 携弹耗尽即补满，令弹流持续到证据齐备（辉星撞线相位同款口径）。
                torpedo?.let { if (it.ammo <= 0) it.resetAmmo() }
                launcher?.let { if (it.ammo <= 0) it.resetAmmo() }
                if (targetSelected >= 1 && adaptiveHits >= 1 && impactVfx >= 1 && hardFluxPushes >= 1) {
                    ctx.log.info(
                        "[ASTD-Automation] cuifeng strike evidence: sel=$targetSelected gov=$governedFrames " +
                                "adaptive=$adaptiveHits hardFlux=$hardFluxPushes aoe=$aoeHits/$aoeShipHits " +
                                "vfx=$impactVfx flare=$crossFlare nebula=$nebulaBurst" +
                                "（反舰目标选择 + 自适应增伤 + 护盾硬辐推进 + 150su AOE + 十字辉星/星云）",
                    )
                    transitionCuifengPhase(CUIFENG_PHASE_COMPLETED)
                }
            }

            CUIFENG_PHASE_COMPLETED -> {
                stabilizeCuifengShips(engine, playerFire = true)
                // 截图舞台携弹续航：令鱼雷弹流与命中特效持续入帧。
                torpedo?.let { if (it.ammo <= 0) it.resetAmmo() }
                launcher?.let { if (it.ammo <= 0) it.resetAmmo() }
            }
        }

        // 最近一次摧锋命中特效时刻（COMPLETED 截图门控：事件近期发生才上报，令十字辉星/星云/拖尾入帧）
        if (impactVfx > cuifengLastTrackedImpactVfxCount) {
            cuifengLastTrackedImpactVfxCount = impactVfx
            cuifengLastImpactVfxAt = ctx.elapsed
        }

        val state = when {
            player == null || cruiser == null || frigate == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "cuifeng ships missing: player=${player != null}, cruiser=${cruiser != null}, frigate=${frigate != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            cuifengPhase == CUIFENG_PHASE_FAILED -> "Failed"
            cuifengPhase != CUIFENG_PHASE_COMPLETED &&
                    ctx.elapsed - cuifengPhaseStartedAt > CUIFENG_PHASE_TIMEOUT -> {
                ctx.failureReason =
                    "cuifeng phase timeout: $cuifengPhase（sel=$targetSelected gov=$governedFrames adaptive=$adaptiveHits hardFlux=$hardFluxPushes aoe=$aoeHits/$aoeShipHits vfx=$impactVfx flare=$crossFlare nebula=$nebulaBurst）"
                "Failed"
            }

            cuifengPhase == CUIFENG_PHASE_COMPLETED -> {
                val recentEvent = cuifengLastImpactVfxAt >= 0f && ctx.elapsed - cuifengLastImpactVfxAt <= CUIFENG_COMPLETED_EVENT_WINDOW
                if (recentEvent || ctx.elapsed - cuifengPhaseStartedAt >= CUIFENG_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: cuifeng_torpedo_basic mount/strike (target-select/adaptive/hard-flux/aoe/vfx) evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, launcher)
        }
    }

    // 捕获帧间隔 0.6s：鱼雷弹流/十字辉星/爆炸星云在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findCuifengPlayer(ctx.engine) }) { lockCuifengCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val cuifengPlayer = findCuifengPlayer(engine)
        val cuifengTorpedo = findCuifengTorpedo(cuifengPlayer)
        val cuifengLauncher = findCuifengLauncher(cuifengPlayer)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（摧锋鱼雷烟测检查点：装配 / 反舰目标选择+调速器 / 自适应增伤+硬辐推进+AOE+特效）----
        json.appendLine("  \"cuifengPhase\": \"$cuifengPhase\",")
        json.appendLine("  \"cuifengTorpedoSlotId\": ${jsonString(cuifengTorpedo?.slot?.id)},")
        json.appendLine("  \"cuifengLauncherSlotId\": ${jsonString(cuifengLauncher?.slot?.id)},")
        json.appendLine("  \"cuifengWeaponRange\": ${formatFloat(cuifengTorpedo?.range ?: -1f)},")
        json.appendLine("  \"cuifengTorpedoAmmo\": ${cuifengTorpedo?.ammo ?: -1},")
        json.appendLine("  \"cuifengLauncherAmmo\": ${cuifengLauncher?.ammo ?: -1},")
        json.appendLine("  \"cuifengTargetSelected\": ${cuifengTeleCount(engine, CuifengTorpedoAI.TELE_TARGET_SELECTED)},")
        json.appendLine("  \"cuifengGovernedFrames\": ${cuifengTeleCount(engine, CuifengTorpedoAI.TELE_GOVERNED_FRAMES)},")
        json.appendLine("  \"cuifengLastSpeedFactor\": ${formatFloat(engine.customData[CuifengTorpedoAI.TELE_LAST_SPEED_FACTOR] as? Float ?: -1f)},")
        json.appendLine("  \"cuifengAdaptiveHits\": ${cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_ADAPTIVE_HITS)},")
        json.appendLine("  \"cuifengHardFluxPushes\": ${cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_HARD_FLUX_PUSHES)},")
        json.appendLine("  \"cuifengAoeHits\": ${cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_AOE_HITS)},")
        json.appendLine("  \"cuifengAoeShipHits\": ${cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_AOE_SHIP_HITS)},")
        json.appendLine("  \"cuifengImpactVfx\": ${cuifengTeleCount(engine, CuifengTorpedoStrikeImpl.TELE_IMPACT_VFX)},")
        json.appendLine("  \"cuifengCrossFlare\": ${cuifengTeleCount(engine, CuifengTorpedoVfx.TELEMETRY_CROSS_FLARE)},")
        json.appendLine("  \"cuifengNebulaBurst\": ${cuifengTeleCount(engine, CuifengTorpedoVfx.TELEMETRY_NEBULA_BURST)},")
        json.appendLine("  \"cuifengLastAdaptiveBonusP\": ${formatFloat(engine.customData[CuifengTorpedoStrikeImpl.TELE_LAST_ADAPTIVE_BONUS + CuifengTorpedoStrikeImpl.TELE_OWNER_PLAYER] as? Float ?: -1f)},")
        json.appendLine("  \"cuifengLastAdaptiveBonusE\": ${formatFloat(engine.customData[CuifengTorpedoStrikeImpl.TELE_LAST_ADAPTIVE_BONUS + CuifengTorpedoStrikeImpl.TELE_OWNER_ENEMY] as? Float ?: -1f)},")
        json.appendLine("  \"cuifengLastHardFluxP\": ${formatFloat(engine.customData[CuifengTorpedoStrikeImpl.TELE_LAST_HARD_FLUX + CuifengTorpedoStrikeImpl.TELE_OWNER_PLAYER] as? Float ?: -1f)},")
        json.appendLine("  \"cuifengLastHardFluxE\": ${formatFloat(engine.customData[CuifengTorpedoStrikeImpl.TELE_LAST_HARD_FLUX + CuifengTorpedoStrikeImpl.TELE_OWNER_ENEMY] as? Float ?: -1f)},")
        json.appendLine("  \"cuifengLastAoeVictim\": ${jsonString(engine.customData[CuifengTorpedoStrikeImpl.TELE_LAST_AOE_VICTIM + CuifengTorpedoStrikeImpl.TELE_OWNER_PLAYER] as? String)},")
        json.appendLine("  \"cuifengDevMode\": ${Global.getSettings().isDevMode},")
        json.appendLine("  \"cuifengOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.CUIFENG_PROJECTILE_SPEC_ID }},")
    }

    private companion object {
        // 摧锋鱼雷场景：相位机、锚点与期望证据（玩家版基础验证，不照搬辉星敌版三档）。
        private const val CUIFENG_PHASE_MOUNT = "MOUNT"
        private const val CUIFENG_PHASE_STRIKE = "STRIKE"
        private const val CUIFENG_PHASE_COMPLETED = "COMPLETED"
        private const val CUIFENG_PHASE_FAILED = "FAILED"
        private const val CUIFENG_PLAYER_HULL = "gryphon"
        private const val CUIFENG_CRUISER_HULL = "eagle"
        private const val CUIFENG_FRIGATE_HULL = "hound"
        private const val CUIFENG_PLAYER_LAUNCHER_SLOT = "WS 008"
        private const val CUIFENG_PLAYER_TORPEDO_SLOT = "WS 010"
        private val CUIFENG_PLAYER_ANCHOR = Vector2f(-700f, 0f)
        private val CUIFENG_CRUISER_ANCHOR = Vector2f(600f, 0f)

        // 护卫舰靶舰贴身巡洋舰摆放（150su 全额面板 AOE 连带观测面；猎犬级无盾）。
        private val CUIFENG_FRIGATE_ANCHOR = Vector2f(740f, 110f)
        private val CUIFENG_CAMERA_CENTER = Vector2f(150f, 30f)
        private const val CUIFENG_CAMERA_VISIBLE_HEIGHT = 1700f
        private const val CUIFENG_MOUNT_SETTLE_SECONDS = 0.6f

        // MOUNT 相位校验：射程 1600 / ammo 2/5 / OP 8/16 / no_drop 两件套（weapon_data.csv 口径）。
        private const val CUIFENG_EXPECT_RANGE = 1600f
        private const val CUIFENG_RANGE_TOLERANCE = 5f
        private const val CUIFENG_TORPEDO_AMMO = 2
        private const val CUIFENG_LAUNCHER_AMMO = 5
        private const val CUIFENG_TORPEDO_OP = 8f
        private const val CUIFENG_LAUNCHER_OP = 16f
        private val CUIFENG_REQUIRED_TAGS = setOf("no_drop", "no_drop_salvage")

        // COMPLETED 截图门控：命中特效近 2.5s 内发生才上报（十字辉星/星云/拖尾入帧）；保底舞台超时。
        private const val CUIFENG_COMPLETED_EVENT_WINDOW = 2.5f
        private const val CUIFENG_COMPLETED_STAGE_TIMEOUT = 30f
        private const val CUIFENG_PHASE_TIMEOUT = 90f
    }
}
