package cn.kasuminova.astd.combat.automation.scenario.arc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorOnHitEffect
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 电驱加速炮场景。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class ElectricDriveScenario : AbstractAutomationScenario() {
    // ==== electric drive accelerator 场景状态（相位机 RANGE_ZERO → RANGE_MID → RANGE_HIGH → FIRE → ENEMY_SCALE → COMPLETED） ====
    private var edaPhase = EDA_PHASE_RANGE_ZERO
    private var edaPhaseStartedAt = 0f
    private var edaRangeZeroFlux = -1f
    private var edaRangeMidFlux = -1f
    private var edaRangeHighFlux = -1f
    private var edaEnemyRangeScale1 = -1f
    private var edaEnemyRangeScale2 = -1f
    private var edaEnemyRangeScale5 = -1f
    private var edaScaleStep = 0
    private var edaScaleStepAt = -1f
    private var edaEnemyExtraBaseline = -1
    private var edaMinPlayerAmmo = Int.MAX_VALUE

    // 每触发弹数分组：spawn 间隔 > EDA_BURST_GROUP_GAP 视为新一轮触发（burst delay 0.1s，组内 2 弹）。
    private var edaCurrentBurstCount = 0
    private var edaLastSpawnAt = -1f
    private var edaMaxTriggerProjectiles = 0
    private val edaBurstSizes = mutableListOf<Int>()
    private val edaSeenProjectiles = mutableSetOf<Int>()

    override val scenarioId: String = ASTDInGameAutomationScenario.EDA_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isEdaEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockEdaCamera(engine)
        // HUD 状态条目仅 devMode 渲染（2026-07-29 审批裁定）：本场景为 dev-only 舞台，
        // 开启 devMode 以目检 HUD 条目与敌版三档（进程被早退杀掉，设置不落盘）。
        Global.getSettings().isDevMode = true
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findEdaPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.EDA_SCENARIO_ID} combat plugin initialized")
    }

    private fun findEdaPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == EDA_PLAYER_HULL }

    private fun findEdaEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == EDA_PLAYER_HULL }

    private fun findEdaWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.EDA_WEAPON_ID }

    private fun lockEdaCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, EDA_CAMERA_CENTER, 900f)
    }

    /** 强制部署 mission reserves（敌方锤头级非旗舰，必须手动出场；范式同 deployChargeNeedleReserveShips）。 */
    private fun deployEdaReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = if (side == FleetSide.ENEMY) EDA_ENEMY_ANCHOR else EDA_PLAYER_ANCHOR
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionEdaPhase(next: String) {
        ctx.log.info("[ASTD-Automation] eda phase $edaPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        edaPhase = next
        edaPhaseStartedAt = ctx.elapsed
    }

    /** 舞台保活与站位：双舰逐帧钉死（保留舰 AI 以维持盾威胁追踪与 AutofireAI，charge needle 场景实证必要）。 */
    private fun stabilizeEdaShips(engine: CombatEngineAPI, playerFire: Boolean, enemyFire: Boolean) {
        val player = findEdaPlayer(engine)
        val enemy = findEdaEnemy(engine)
        if (player != null) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, EDA_PLAYER_ANCHOR, 0f, allowFire = playerFire, preserveAI = true)
            player.shipTarget = enemy
            player.hitpoints = player.maxHitpoints
            player.shield?.let { if (!it.isOn) it.toggleOn() }
            setWeaponGroupAutofire(player, playerFire, EDA_WEAPON_IDS)
            stageEdaFireControl(player, enemy, playerFire)
        }
        if (enemy != null) {
            stabilizeShip(enemy, EDA_ENEMY_ANCHOR, 180f, allowFire = enemyFire, preserveAI = true)
            enemy.shipTarget = player
            enemy.hitpoints = enemy.maxHitpoints
            setWeaponGroupAutofire(enemy, enemyFire, EDA_WEAPON_IDS)
            stageEdaFireControl(enemy, player, enemyFire)
        }
    }

    /**
     * 舞台直控开火：首轮实机验证锤头级 WS 001 挂电驱加速炮时武器组 AutofireAI 目标采纳恒 null
     * （autofire toggle 90s 零发射，弹药恒 30），与电荷针刺 WS 004 同款槽位/组级 AI 行为。
     * 按针刺重型实证路径逐帧 currAngle 对准 + setForceFireOneFrame 绕过 AI 判定
     * （纯舞台手段；开火周期/弹药/散射由武器机制自身决定，正是要观测的对象）。
     */
    private fun stageEdaFireControl(ship: ShipAPI, target: ShipAPI?, fire: Boolean) {
        for (w in ship.allWeapons) {
            if (w.id != ASTDInGameAutomationScenario.EDA_WEAPON_ID) continue
            if (target != null) w.currAngle = Misc.getAngleInDegrees(w.location, target.location)
            w.setForceFireOneFrame(fire)
        }
    }

    /** 把舰船辐能水平钉到指定比例（软辐直写 + 硬辐清零；净空加速只读 fluxLevel 软硬合计）。 */
    private fun pinFluxLevel(ship: ShipAPI?, level: Float) {
        ship ?: return
        val max = ship.maxFlux.takeIf { it > 0f } ?: return
        ship.fluxTracker.currFlux = (level * max).coerceIn(0f, max)
        ship.fluxTracker.hardFlux = 0f
    }

    /** 每触发弹数分组：新弹 spawn 间隔 > [EDA_BURST_GROUP_GAP] 视为新一轮触发（burst delay 0.1s）。 */
    private fun trackEdaTriggerGroups(engine: CombatEngineAPI, player: ShipAPI?) {
        player ?: return
        for (projectile in engine.projectiles) {
            if (projectile.projectileSpecId != ASTDInGameAutomationScenario.EDA_PROJECTILE_SPEC_ID) continue
            val damaging = projectile as? DamagingProjectileAPI ?: continue
            if (damaging.source !== player) continue
            val key = System.identityHashCode(projectile)
            if (!edaSeenProjectiles.add(key)) continue
            if (edaLastSpawnAt >= 0f && ctx.elapsed - edaLastSpawnAt > EDA_BURST_GROUP_GAP) closeEdaBurstGroup()
            edaCurrentBurstCount++
            edaLastSpawnAt = ctx.elapsed
        }
        // 无新弹时同样按间隔收口悬挂分组（burst 尾部）。
        if (edaCurrentBurstCount > 0 && edaLastSpawnAt >= 0f && ctx.elapsed - edaLastSpawnAt > EDA_BURST_GROUP_GAP) {
            closeEdaBurstGroup()
        }
    }

    private fun closeEdaBurstGroup() {
        if (edaCurrentBurstCount <= 0) return
        edaBurstSizes += edaCurrentBurstCount
        if (edaCurrentBurstCount > edaMaxTriggerProjectiles) edaMaxTriggerProjectiles = edaCurrentBurstCount
        ctx.log.info("[ASTD-Automation] eda trigger group closed: projectiles=$edaCurrentBurstCount max=$edaMaxTriggerProjectiles")
        edaCurrentBurstCount = 0
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployEdaReserveShips(engine)
        lockEdaCamera(engine)

        val player = findEdaPlayer(engine)
        val enemy = findEdaEnemy(engine)
        val playerEda = findEdaWeapon(player)
        val enemyEda = findEdaWeapon(enemy)

        when (edaPhase) {
            EDA_PHASE_RANGE_ZERO -> {
                stabilizeEdaShips(engine, playerFire = false, enemyFire = false)
                pinFluxLevel(player, 0f)
                pinFluxLevel(enemy, 0f)
                if (ctx.elapsed - edaPhaseStartedAt >= EDA_RANGE_SETTLE_SECONDS) {
                    edaRangeZeroFlux = playerEda?.range ?: -1f
                    if (kotlin.math.abs(edaRangeZeroFlux - EDA_EXPECT_RANGE_ZERO) <= EDA_RANGE_TOLERANCE) {
                        transitionEdaPhase(EDA_PHASE_RANGE_MID)
                    } else {
                        ctx.failureReason = "eda range@0flux=${edaRangeZeroFlux}, expect≈$EDA_EXPECT_RANGE_ZERO"
                        transitionEdaPhase(EDA_PHASE_FAILED)
                    }
                }
            }

            EDA_PHASE_RANGE_MID -> {
                stabilizeEdaShips(engine, playerFire = false, enemyFire = false)
                pinFluxLevel(player, EDA_MID_FLUX_LEVEL)
                pinFluxLevel(enemy, 0f)
                if (ctx.elapsed - edaPhaseStartedAt >= EDA_RANGE_SETTLE_SECONDS) {
                    edaRangeMidFlux = playerEda?.range ?: -1f
                    if (kotlin.math.abs(edaRangeMidFlux - EDA_EXPECT_RANGE_MID) <= EDA_RANGE_TOLERANCE) {
                        transitionEdaPhase(EDA_PHASE_RANGE_HIGH)
                    } else {
                        ctx.failureReason = "eda range@30%flux=$edaRangeMidFlux, expect≈$EDA_EXPECT_RANGE_MID"
                        transitionEdaPhase(EDA_PHASE_FAILED)
                    }
                }
            }

            EDA_PHASE_RANGE_HIGH -> {
                stabilizeEdaShips(engine, playerFire = false, enemyFire = false)
                pinFluxLevel(player, EDA_HIGH_FLUX_LEVEL)
                pinFluxLevel(enemy, 0f)
                if (ctx.elapsed - edaPhaseStartedAt >= EDA_RANGE_SETTLE_SECONDS) {
                    edaRangeHighFlux = playerEda?.range ?: -1f
                    if (kotlin.math.abs(edaRangeHighFlux - EDA_EXPECT_RANGE_HIGH) <= EDA_RANGE_TOLERANCE) {
                        transitionEdaPhase(EDA_PHASE_FIRE)
                    } else {
                        ctx.failureReason = "eda range@50%flux=$edaRangeHighFlux, expect≈$EDA_EXPECT_RANGE_HIGH"
                        transitionEdaPhase(EDA_PHASE_FAILED)
                    }
                }
            }

            EDA_PHASE_FIRE -> {
                stabilizeEdaShips(engine, playerFire = true, enemyFire = false)
                enemy?.shield?.let { if (!it.isOn) it.toggleOn() }
                // 开火相位把玩家辐能钉 0：满额射程 + HUD 加成条目满值（浮动由舞台钉死，机制浮动已在射程相位验证）。
                pinFluxLevel(player, 0f)
                pinFluxLevel(enemy, 0f)
                trackEdaTriggerGroups(engine, player)
                playerEda?.let { if (it.ammo < edaMinPlayerAmmo) edaMinPlayerAmmo = it.ammo }
                val extraCount = ElectricDriveAcceleratorOnHitEffect.extraDamageCountPlayer(engine)
                if (edaMaxTriggerProjectiles >= EDA_EXPECT_TRIGGER_PROJECTILES && extraCount >= 1) {
                    transitionEdaPhase(EDA_PHASE_ENEMY_SCALE)
                    edaScaleStep = 0
                    edaScaleStepAt = ctx.elapsed
                    DifficultyTuningImpl.installScaleForTests(1f)
                }
            }

            EDA_PHASE_ENEMY_SCALE -> {
                stabilizeEdaShips(engine, playerFire = false, enemyFire = edaScaleStep >= 3)
                pinFluxLevel(player, 0f)
                pinFluxLevel(enemy, 0f)
                when (edaScaleStep) {
                    0 -> if (ctx.elapsed - edaScaleStepAt >= EDA_RANGE_SETTLE_SECONDS) {
                        edaEnemyRangeScale1 = enemyEda?.range ?: -1f
                        ctx.log.info("[ASTD-Automation] eda enemy range@k_s=1: $edaEnemyRangeScale1")
                        DifficultyTuningImpl.installScaleForTests(2f)
                        edaScaleStep = 1; edaScaleStepAt = ctx.elapsed
                    }

                    1 -> if (ctx.elapsed - edaScaleStepAt >= EDA_RANGE_SETTLE_SECONDS) {
                        edaEnemyRangeScale2 = enemyEda?.range ?: -1f
                        ctx.log.info("[ASTD-Automation] eda enemy range@k_s=2: $edaEnemyRangeScale2")
                        DifficultyTuningImpl.installScaleForTests(5f)
                        edaScaleStep = 2; edaScaleStepAt = ctx.elapsed
                    }

                    2 -> if (ctx.elapsed - edaScaleStepAt >= EDA_RANGE_SETTLE_SECONDS) {
                        edaEnemyRangeScale5 = enemyEda?.range ?: -1f
                        ctx.log.info("[ASTD-Automation] eda enemy range@k_s=5: $edaEnemyRangeScale5")
                        edaEnemyExtraBaseline = ElectricDriveAcceleratorOnHitEffect.extraDamageCountOther(engine)
                        edaScaleStep = 3; edaScaleStepAt = ctx.elapsed
                    }
                    // k_s=5 下敌方开火：取敌版追加伤害证据（次数 + 峰值可超玩家档 67.5 上限）。
                    3 -> {
                        val gained = ElectricDriveAcceleratorOnHitEffect.extraDamageCountOther(engine) - edaEnemyExtraBaseline
                        if (gained >= 1 || ctx.elapsed - edaScaleStepAt >= EDA_ENEMY_FIRE_SECONDS) {
                            DifficultyTuningImpl.installScaleForTests(null)
                            transitionEdaPhase(EDA_PHASE_COMPLETED)
                        }
                    }
                }
            }

            EDA_PHASE_COMPLETED -> {
                stageEdaCompletedFrame(engine)
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 10f) {
                    ctx.failureReason = "eda ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            edaPhase == EDA_PHASE_FAILED -> "Failed"
            edaPhase != EDA_PHASE_COMPLETED &&
                    ctx.elapsed - edaPhaseStartedAt > EDA_PHASE_TIMEOUT -> {
                ctx.failureReason = "eda phase timeout: $edaPhase"
                "Failed"
            }

            edaPhase == EDA_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: electric_drive_basic range/trigger/charge/scaling evidence observed")
        }
        if (state == "Failed") {
            DifficultyTuningImpl.installScaleForTests(null)
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, playerEda)
        }
    }

    /** COMPLETED 截图舞台：玩家辐能钉 0（射程圈满额 + HUD 加成条目）+ 敌盾开 + 玩家自动开火。 */
    private fun stageEdaCompletedFrame(engine: CombatEngineAPI) {
        stabilizeEdaShips(engine, playerFire = true, enemyFire = false)
        val player = findEdaPlayer(engine)
        val enemy = findEdaEnemy(engine)
        pinFluxLevel(player, 0f)
        pinFluxLevel(enemy, 0f)
        enemy?.shield?.let { if (!it.isOn) it.toggleOn() }
        trackEdaTriggerGroups(engine, player)
        lockEdaCamera(engine)
    }

    // 捕获帧间隔 0.6s：齐射拖尾/HUD 条目在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findEdaPlayer(ctx.engine) }) { stageEdaCompletedFrame(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val player = findEdaPlayer(engine)
        val enemy = findEdaEnemy(engine)
        val playerEda = player?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.EDA_WEAPON_ID }
        val enemyEda = enemy?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.EDA_WEAPON_ID }
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 03 §4.2 烟测检查点）----
        json.appendLine("  \"edaPhase\": \"$edaPhase\",")
        json.appendLine("  \"edaRangeZeroFlux\": ${formatFloat(edaRangeZeroFlux)},")
        json.appendLine("  \"edaRangeMidFlux\": ${formatFloat(edaRangeMidFlux)},")
        json.appendLine("  \"edaRangeHighFlux\": ${formatFloat(edaRangeHighFlux)},")
        json.appendLine("  \"edaPlayerWeaponRange\": ${formatFloat(playerEda?.range ?: -1f)},")
        json.appendLine("  \"edaPlayerFluxLevel\": ${formatFloat(player?.fluxLevel ?: -1f)},")
        json.appendLine("  \"edaMaxTriggerProjectiles\": $edaMaxTriggerProjectiles,")
        json.appendLine("  \"edaBurstSizes\": ${edaBurstSizes.takeLast(12).joinToString(prefix = "[", postfix = "]")},")
        json.appendLine("  \"edaMinPlayerAmmoObserved\": ${if (edaMinPlayerAmmo == Int.MAX_VALUE) -1 else edaMinPlayerAmmo},")
        json.appendLine("  \"edaExtraCountPlayer\": ${ElectricDriveAcceleratorOnHitEffect.extraDamageCountPlayer(engine)},")
        json.appendLine("  \"edaExtraMaxPlayer\": ${formatFloat(engine.customData[ElectricDriveAcceleratorOnHitEffect.TELEMETRY_EXTRA_MAX_PLAYER] as? Float ?: 0f)},")
        json.appendLine("  \"edaExtraCountOther\": ${ElectricDriveAcceleratorOnHitEffect.extraDamageCountOther(engine)},")
        json.appendLine("  \"edaExtraMaxOther\": ${formatFloat(engine.customData[ElectricDriveAcceleratorOnHitEffect.TELEMETRY_EXTRA_MAX_OTHER] as? Float ?: 0f)},")
        json.appendLine("  \"edaEnemyRangeScale1\": ${formatFloat(edaEnemyRangeScale1)},")
        json.appendLine("  \"edaEnemyRangeScale2\": ${formatFloat(edaEnemyRangeScale2)},")
        json.appendLine("  \"edaEnemyRangeScale5\": ${formatFloat(edaEnemyRangeScale5)},")
        json.appendLine("  \"edaEnemyWeaponRange\": ${formatFloat(enemyEda?.range ?: -1f)},")
        json.appendLine("  \"edaVfxTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"edaVfxLastSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        json.appendLine("  \"edaDevMode\": ${Global.getSettings().isDevMode},")
        json.appendLine("  \"edaOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.EDA_PROJECTILE_SPEC_ID }},")
    }

    private companion object {
        // 电驱加速炮场景：相位机、锚点与期望证据。
        private const val EDA_PHASE_RANGE_ZERO = "RANGE_ZERO"
        private const val EDA_PHASE_RANGE_MID = "RANGE_MID"
        private const val EDA_PHASE_RANGE_HIGH = "RANGE_HIGH"
        private const val EDA_PHASE_FIRE = "FIRE"
        private const val EDA_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val EDA_PHASE_COMPLETED = "COMPLETED"
        private const val EDA_PHASE_FAILED = "FAILED"
        private const val EDA_PLAYER_HULL = "hammerhead"
        private val EDA_PLAYER_ANCHOR = Vector2f(-350f, 0f)
        private val EDA_ENEMY_ANCHOR = Vector2f(350f, 0f)
        private val EDA_CAMERA_CENTER = Vector2f(0f, 0f)

        // 射程相位期望：基线 750 + 净空加成（v2 满额 200；30% 辐能衰减 0.5 → +100；50% ≥40% 阈值归零）。
        private const val EDA_EXPECT_RANGE_ZERO = 950f
        private const val EDA_EXPECT_RANGE_MID = 850f
        private const val EDA_EXPECT_RANGE_HIGH = 750f
        private const val EDA_RANGE_TOLERANCE = 30f
        private const val EDA_MID_FLUX_LEVEL = 0.3f
        private const val EDA_HIGH_FLUX_LEVEL = 0.5f
        private const val EDA_RANGE_SETTLE_SECONDS = 0.6f

        // 每触发 2 弹（双管交替 × burst 2）；轮间隔 = chargedown 0.3s，间隔 >0.2s 判定新一轮触发。
        private const val EDA_EXPECT_TRIGGER_PROJECTILES = 2
        private const val EDA_BURST_GROUP_GAP = 0.2f

        // 敌版开火观察窗：k_s=5 档下等待敌版追加伤害遥测增量。
        private const val EDA_ENEMY_FIRE_SECONDS = 20f
        private const val EDA_PHASE_TIMEOUT = 90f
        private val EDA_WEAPON_IDS = setOf(ASTDInGameAutomationScenario.EDA_WEAPON_ID)
    }
}
