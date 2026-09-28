package cn.kasuminova.astd.combat.automation.scenario.arc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveFuseScript
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 正电子冲击波场景（规格 06 §4.2 烟测检查点）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class PositronShockwaveScenario : AbstractAutomationScenario() {
    // ==== positron shockwave 场景状态（相位机 MOUNT → PASS_THROUGH → SPLASH → FUSE → COMPLETED） ====
    private var psPhase = PS_PHASE_MOUNT
    private var psPhaseStartedAt = 0f

    // SPLASH 相位基线：进入相位时的锥面舰船命中计数与近炸引爆计数（增量即本相位证据）。
    private var psSplashShipHitsBaseline = 0
    private var psSplashFuseBaseline = 0
    private var psSplashMaxRangeBaseline = 0

    // FUSE 相位导弹投喂节流与左右舷交替。
    private var psMissileFeedAt = -1f
    private var psMissileFeedSide = 1

    // COMPLETED 截图门控：最近一次近炸引爆时刻（截图帧需含锥面 VFX/浮字）。
    private var psLastFuseDetonateAt = -1f
    private var psLastTrackedFuseCount = 0

    override val scenarioId: String = ASTDInGameAutomationScenario.PS_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPsEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockPsCamera(engine)
        // 引爆计数浮字仅 devMode 渲染（2026-07-29 审批裁定）：本场景为 dev-only 舞台，
        // 开启 devMode 以目检「近炸命中 ×n」浮字（进程被早退杀掉，设置不落盘）。
        Global.getSettings().isDevMode = true
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findPsPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.PS_SCENARIO_ID} combat plugin initialized")
    }

    private fun findPsPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner == 0 && it.hullSpec?.hullId == PS_PLAYER_HULL && !it.isFighter }

    private fun findPsTarget(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == PS_TARGET_HULL && !it.isFighter }

    private fun findPsWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.PS_WEAPON_ID }

    private fun lockPsCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, PS_CAMERA_CENTER, PS_CAMERA_VISIBLE_HEIGHT)
    }

    /** 强制部署 mission reserves（玩家野狼 + 无武装警戒级靶舰均非旗舰，按舰体分配锚点）。 */
    private fun deployPsReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val hullId = member.hullId ?: continue
                val anchor = when {
                    side == FleetSide.PLAYER && hullId == PS_PLAYER_HULL -> PS_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && hullId == PS_TARGET_HULL -> PS_TARGET_IMPACT_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionPsPhase(next: String) {
        ctx.log.info("[ASTD-Automation] ps phase $psPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        psPhase = next
        psPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeQjShips）：保留舰 AI + 逐帧 currAngle 对准正东 +
     * setForceFireOneFrame 绕 AutofireAI 死锁（01/03/05 实证路径）；玩家辐能逐帧清零。
     * 警戒靶舰盾舞台性常关；PASS_THROUGH 相位不逐帧奶（HP 即「无触碰体积」观测面），
     * 进入相位时奶满一次。
     */
    private fun stabilizePsShips(engine: CombatEngineAPI, fire: Boolean) {
        val player = findPsPlayer(engine)
        val target = findPsTarget(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, PS_PLAYER_ANCHOR, 0f, allowFire = fire, preserveAI = true)
            player.shipTarget = target
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            setPsAutofire(player, false)
            val w = findPsWeapon(player)
            if (w != null) {
                w.currAngle = 0f
                w.setForceFireOneFrame(fire)
            }
        }
        if (target != null && !target.isHulk) {
            val anchor = when (psPhase) {
                PS_PHASE_MOUNT, PS_PHASE_IMPACT -> PS_TARGET_IMPACT_ANCHOR
                else -> PS_TARGET_SPLASH_ANCHOR
            }
            stabilizeShip(target, anchor, 180f, allowFire = false, preserveAI = true)
            target.shipTarget = null
            if (psPhase != PS_PHASE_IMPACT) target.hitpoints = target.maxHitpoints
            target.shield?.toggleOff()
        }
    }

    /** 正电子武器组 autofire 总开关（范式同 setQjAutofire）：force fire 独占驱动时关闭。 */
    private fun setPsAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id == ASTDInGameAutomationScenario.PS_WEAPON_ID }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /** 撞舰相位观测准备：整格剥除靶舰装甲（破片伤害全被装甲吸收时舰体读数恒满，无法观测结算）。 */
    private fun stripPsTargetArmor(ship: ShipAPI) {
        val grid = ship.armorGrid.grid
        for (x in grid.indices) {
            for (y in grid[x].indices) {
                ship.armorGrid.setArmorValue(x, y, 0f)
            }
        }
    }

    /**
     * FUSE/COMPLETED 相位导弹投喂：每 [PS_MISSILE_FEED_INTERVAL] 从靶舰右前方 820su 处左右舷交替
     * 生成一发鱼叉（weapon=null + weaponId 直接生成导弹实体，范式同 lens phase-2 投喂），
     * 初速 250su/s 指向玩家锚点。spawn 返回 null 视为 weaponId 不可用——记失败原因转 FAILED。
     */
    private fun feedPsMissiles(engine: CombatEngineAPI) {
        if (ctx.elapsed < psMissileFeedAt) return
        psMissileFeedAt = ctx.elapsed + PS_MISSILE_FEED_INTERVAL
        val source = findPsTarget(engine) ?: return
        psMissileFeedSide = -psMissileFeedSide
        val spawn = Vector2f(PS_MISSILE_SPAWN_X, PS_MISSILE_SPAWN_Y * psMissileFeedSide)
        val angle = Misc.getAngleInDegrees(spawn, PS_PLAYER_ANCHOR)
        val rad = Math.toRadians(angle.toDouble())
        val vel = Vector2f(
            (kotlin.math.cos(rad) * PS_MISSILE_INITIAL_SPEED).toFloat(),
            (kotlin.math.sin(rad) * PS_MISSILE_INITIAL_SPEED).toFloat(),
        )
        val spawned = engine.spawnProjectile(source, null, PS_FEED_MISSILE_ID, spawn, angle, vel)
        if (spawned == null) {
            ctx.failureReason = "ps missile spawn returned null for weaponId=$PS_FEED_MISSILE_ID"
            transitionPsPhase(PS_PHASE_FAILED)
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployPsReserveShips(engine)
        lockPsCamera(engine)

        val player = findPsPlayer(engine)
        val target = findPsTarget(engine)
        val weapon = findPsWeapon(player)
        val fuseCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_DETONATE_FUSE)
        val maxRangeCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_DETONATE_MAX_RANGE)
        val shipHits = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_SHIP_HITS)
        val missileHits = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_MISSILE_HITS)
        val floatyCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_FLOATY)
        val coneVfxCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_VFX)
        val impactCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_DETONATE_IMPACT)

        when (psPhase) {
            PS_PHASE_MOUNT -> {
                stabilizePsShips(engine, fire = false)
                if (ctx.elapsed - psPhaseStartedAt >= PS_MOUNT_SETTLE_SECONDS) {
                    val slot = weapon?.slot?.id
                    val range = weapon?.range ?: -1f
                    val hintsPd = weapon?.spec?.aiHints?.contains(WeaponAPI.AIHints.PD) == true
                    when {
                        slot != PS_PLAYER_SLOT || kotlin.math.abs(range - PS_EXPECT_RANGE) > PS_RANGE_TOLERANCE -> {
                            ctx.failureReason = "ps mount mismatch: slot=$slot range=$range(expect $PS_EXPECT_RANGE)"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        !hintsPd -> {
                            ctx.failureReason = "ps aiHints missing PD（装配面板 hints 校验）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        else -> {
                            // 进入撞舰相位：靶舰奶满一次作伤害观测基线（本相位不逐帧奶）；
                            // 装甲整格剥除——警戒级装甲全额吸收破片伤害会令舰体读数恒满
                            // （2026-09 撞舰裁定后首次实机暴露），与逐帧 toggleOff 护盾同口径剥除防御层，
                            // 直读撞舰引爆 + 锥面结算的舰体伤害
                            target?.hitpoints = target.maxHitpoints
                            target?.let { stripPsTargetArmor(it) }
                            transitionPsPhase(PS_PHASE_IMPACT)
                        }
                    }
                }
            }

            PS_PHASE_IMPACT -> {
                stabilizePsShips(engine, fire = true)
                if (impactCount >= PS_IMPACT_DETONATIONS) {
                    val targetDamaged = target != null && !target.isHulk &&
                            target.hitpoints < target.maxHitpoints - PS_IMPACT_MIN_DAMAGE
                    when {
                        fuseCount != 0 -> {
                            ctx.failureReason = "ps impact fuse=$fuseCount, expect 0（无导弹环境不应有近炸引爆）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        maxRangeCount != 0 -> {
                            ctx.failureReason = "ps impact max-range=$maxRangeCount, expect 0（靶舰锚在 400su 弹道上，弹体应撞舰引爆而非飞到 600su 自爆）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        !targetDamaged -> {
                            ctx.failureReason =
                                "ps impact target hp=${target?.hitpoints}/${target?.maxHitpoints}（撞舰引爆 + 锥面结算应对舰船造成可观伤害）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        else -> {
                            psSplashShipHitsBaseline = shipHits
                            psSplashFuseBaseline = fuseCount
                            psSplashMaxRangeBaseline = maxRangeCount
                            transitionPsPhase(PS_PHASE_SPLASH)
                        }
                    }
                }
            }

            PS_PHASE_SPLASH -> {
                stabilizePsShips(engine, fire = true)
                if (maxRangeCount - psSplashMaxRangeBaseline >= PS_SPLASH_DETONATIONS) {
                    when {
                        shipHits - psSplashShipHitsBaseline < 1 -> {
                            ctx.failureReason =
                                "ps splash ship hits delta=${shipHits - psSplashShipHitsBaseline}, expect>=1（700su 处舰船应被满射程自爆锥面波及）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        fuseCount - psSplashFuseBaseline != 0 -> {
                            ctx.failureReason = "ps splash fuse delta=${fuseCount - psSplashFuseBaseline}, expect 0（舰船蹭波及但不触发近炸）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        else -> transitionPsPhase(PS_PHASE_FUSE)
                    }
                }
            }

            PS_PHASE_FUSE -> {
                stabilizePsShips(engine, fire = true)
                feedPsMissiles(engine)
                if (fuseCount >= PS_FUSE_DETONATIONS && missileHits >= PS_FUSE_MISSILE_HITS) {
                    when {
                        floatyCount < 1 -> {
                            ctx.failureReason = "ps floaty=$floatyCount, expect>=1（devMode 引爆计数浮字「近炸命中 ×n」）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        coneVfxCount < 1 -> {
                            ctx.failureReason = "ps cone vfx=$coneVfxCount, expect>=1（引爆锥面 VFX）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }

                        else -> transitionPsPhase(PS_PHASE_COMPLETED)
                    }
                }
            }

            PS_PHASE_COMPLETED -> {
                stabilizePsShips(engine, fire = true)
                feedPsMissiles(engine)
            }
        }

        // 最近一次近炸引爆时刻（COMPLETED 截图门控：引爆近期发生才上报，令锥面/浮字入帧）
        if (fuseCount > psLastTrackedFuseCount) {
            psLastTrackedFuseCount = fuseCount
            psLastFuseDetonateAt = ctx.elapsed
        }

        val state = when {
            player == null || target == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "ps ships missing: player=${player != null}, target=${target != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            psPhase == PS_PHASE_FAILED -> "Failed"
            psPhase != PS_PHASE_COMPLETED &&
                    ctx.elapsed - psPhaseStartedAt > PS_PHASE_TIMEOUT -> {
                ctx.failureReason = "ps phase timeout: $psPhase"
                "Failed"
            }

            psPhase == PS_PHASE_COMPLETED -> {
                val recentDetonate = psLastFuseDetonateAt >= 0f && ctx.elapsed - psLastFuseDetonateAt <= PS_COMPLETED_DETONATE_WINDOW
                if (recentDetonate || ctx.elapsed - psPhaseStartedAt >= PS_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: positron_shockwave_basic pass-through/max-range/splash/fuse evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, weapon)
        }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val psPlayer = findPsPlayer(engine)
        val psTarget = findPsTarget(engine)
        val psWeapon = findPsWeapon(psPlayer)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 06 §4.2 烟测检查点）----
        json.appendLine("  \"psPhase\": \"$psPhase\",")
        json.appendLine("  \"psSlotId\": ${jsonString(psWeapon?.slot?.id)},")
        json.appendLine("  \"psWeaponRange\": ${formatFloat(psWeapon?.range ?: -1f)},")
        json.appendLine("  \"psHintsPd\": ${psWeapon?.spec?.aiHints?.contains(WeaponAPI.AIHints.PD) == true},")
        json.appendLine("  \"psTargetHitpoints\": ${formatFloat(psTarget?.hitpoints ?: -1f)},")
        json.appendLine("  \"psTargetMaxHitpoints\": ${formatFloat(psTarget?.maxHitpoints ?: -1f)},")
        json.appendLine(
            "  \"psDetonateFuse\": ${
                PositronShockwaveFuseScript.telemetryCount(
                    engine,
                    PositronShockwaveFuseScript.TELEMETRY_DETONATE_FUSE
                )
            },"
        )
        json.appendLine(
            "  \"psDetonateMaxRange\": ${
                PositronShockwaveFuseScript.telemetryCount(
                    engine,
                    PositronShockwaveFuseScript.TELEMETRY_DETONATE_MAX_RANGE
                )
            },"
        )
        json.appendLine(
            "  \"psLastDetonateDist\": ${
                formatFloat(
                    PositronShockwaveFuseScript.telemetryFloat(
                        engine,
                        PositronShockwaveFuseScript.TELEMETRY_LAST_DETONATE_DIST
                    )
                )
            },"
        )
        json.appendLine(
            "  \"psConeShipHits\": ${
                PositronShockwaveFuseScript.telemetryCount(
                    engine,
                    PositronShockwaveFuseScript.TELEMETRY_CONE_SHIP_HITS
                )
            },"
        )
        json.appendLine(
            "  \"psConeMissileHits\": ${
                PositronShockwaveFuseScript.telemetryCount(
                    engine,
                    PositronShockwaveFuseScript.TELEMETRY_CONE_MISSILE_HITS
                )
            },"
        )
        json.appendLine(
            "  \"psConeFighterHits\": ${
                PositronShockwaveFuseScript.telemetryCount(
                    engine,
                    PositronShockwaveFuseScript.TELEMETRY_CONE_FIGHTER_HITS
                )
            },"
        )
        json.appendLine("  \"psConeVfx\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_VFX)},")
        json.appendLine("  \"psFloaty\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_FLOATY)},")
        json.appendLine("  \"psDevMode\": ${Global.getSettings().isDevMode},")
        json.appendLine("  \"psOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.PS_PROJECTILE_SPEC_ID }},")
        json.appendLine("  \"psEnemyMissilesInPlay\": ${engine.missiles.count { it.owner != 0 }},")
    }

    private companion object {
        // 正电子冲击波场景：相位机、锚点与期望证据（规格 06 §4.2 烟测检查点）。
        private const val PS_PHASE_MOUNT = "MOUNT"
        private const val PS_PHASE_IMPACT = "IMPACT"
        private const val PS_PHASE_SPLASH = "SPLASH"
        private const val PS_PHASE_FUSE = "FUSE"
        private const val PS_PHASE_COMPLETED = "COMPLETED"
        private const val PS_PHASE_FAILED = "FAILED"
        private const val PS_PLAYER_HULL = "wolf"
        private const val PS_TARGET_HULL = "vigilance"
        private const val PS_PLAYER_SLOT = "WS 001"
        private val PS_PLAYER_ANCHOR = Vector2f(0f, 0f)

        // 撞舰相位靶舰锚点（400su 在弹道上）；波及相位移至 700su（满射程 600 引爆点前方 100，锥长 250 内）。
        private val PS_TARGET_IMPACT_ANCHOR = Vector2f(400f, 0f)
        private val PS_TARGET_SPLASH_ANCHOR = Vector2f(700f, 0f)
        private val PS_CAMERA_CENTER = Vector2f(400f, 0f)
        private const val PS_CAMERA_VISIBLE_HEIGHT = 950f
        private const val PS_MOUNT_SETTLE_SECONDS = 0.6f

        // MOUNT 相位校验：射程断言基线 600（无射程向 hullmod 干扰）。
        private const val PS_EXPECT_RANGE = 600f
        private const val PS_RANGE_TOLERANCE = 5f

        // IMPACT（2026-09 修订：弹体识别舰船碰撞，撞舰即时引爆）：靶舰锚在弹道上，
        // 撞舰引爆 ≥3 次且目标掉血 ≥50；期间近炸/满射程计数必须恒 0（弹体不应飞过 400su 靶舰）。
        private const val PS_IMPACT_DETONATIONS = 3
        private const val PS_IMPACT_MIN_DAMAGE = 50f

        // SPLASH：相位内两次满射程自爆，锥面舰船命中计数 +1（700su 靶舰在 600 引爆点锥内）。
        private const val PS_SPLASH_DETONATIONS = 2

        // FUSE：近炸引爆 ≥2 次、锥面导弹命中 ≥3（成片清除证据）。
        private const val PS_FUSE_DETONATIONS = 2
        private const val PS_FUSE_MISSILE_HITS = 3

        // 导弹投喂：鱼叉（vanilla MRM），0.9s 一发，820su 处左右舷交替，初速 250su/s 指向玩家。
        private const val PS_FEED_MISSILE_ID = "harpoon"
        private const val PS_MISSILE_FEED_INTERVAL = 0.9f
        private const val PS_MISSILE_SPAWN_X = 820f
        private const val PS_MISSILE_SPAWN_Y = 80f
        private const val PS_MISSILE_INITIAL_SPEED = 250f

        // COMPLETED 截图门控：近炸引爆近 1.2s 内发生才上报（锥面 VFX/浮字入帧）；保底舞台超时。
        private const val PS_COMPLETED_DETONATE_WINDOW = 1.2f
        private const val PS_COMPLETED_STAGE_TIMEOUT = 25f
        private const val PS_PHASE_TIMEOUT = 60f
    }
}
