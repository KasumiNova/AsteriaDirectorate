package cn.kasuminova.astd.combat.automation.scenario.xc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot

/**
 * trail_pause_probe 暂停对照探针：与 xc_001 共用坠星残响舞台，但刻意不 unpause、不做取景曲线对齐，
 * 验证暂停期 VFX 驱动状态与恢复后 cadence（拆分前枢纽的 tpp 分支，行为原样迁移）。
 */
class TrailPauseProbeScenario : AbstractAutomationScenario() {

    // trail_pause_probe 探针状态：相位机 + 待捕获标签（由 renderCapture 消费完成截图）。
    private var tppPhase = TPP_PHASE_FIRE
    private var tppPhaseStartedAt = -1f
    private var tppProjectileFirstSeenAt = -1f
    private var tppCapturePending: String? = null

    override val scenarioId: String = ASTDInGameAutomationScenario.TPP_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.KEEP_PAUSED

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isTrailPauseProbeEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        ctx.starfallEcho.lockCamera(engine)
        val ship = ctx.starfallEcho.findStageShip(engine)
        ctx.starfallEcho.arrangeShips(engine, ship)
        ctx.writeDiagnostics("CombatReady", ship)
        ctx.writeTelemetry(
            "CombatReady",
            ship,
            ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
        )
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.TPP_SCENARIO_ID} combat plugin initialized")
    }

    /**
     * trail_pause_probe 相位机：FORCE_FIRE →（弹体飞行 [TPP_PRE_PAUSE_FLIGHT_SECONDS]）抓 BeforePause
     * → setPaused(true) 保持 [TPP_PAUSE_SECONDS] → 抓 DuringPause → setPaused(false)
     * → 飞行 [TPP_POST_RESUME_FLIGHT_SECONDS] → 抓 AfterResume → 第 4 次 "Completed" 写出终态触发早退。
     * 与默认坠星残响场景的差异：不调用 alignStarfallEchoProjectilesForEvidence（该取景驱动按 elapsed 改写弹体位置，
     * 暂停期 elapsed 继续推进会污染对照实验），弹体全程自由飞行。
     * 注意必须用全速 spawn（弹体 moveSpeed 直出），不能用取景曲线 spawn——
     * 后者的一次性取景曲线对齐会把弹速压到 ~340su/s，30Hz 节点间距从 96su 缩到 11su，
     * 恰好把要观测的「螺栓头 vs 拖尾头 cadence 滞后」藏没（2026-09 首轮探针教训）。
     */
    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine
        val ship = ctx.starfallEcho.findStageShip(engine)
        val weapon = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID }
        ctx.starfallEcho.lockCamera(engine)
        ctx.starfallEcho.arrangeShips(engine, ship)
        if (ship != null) {
            engine.setPlayerShipExternal(ship)
            ship.shipAI = null
            ship.setControlsLocked(false)
            ship.isHoldFireOneFrame = false
            ship.shipTarget = null
        }

        if (tppProjectileFirstSeenAt < 0f && ctx.starfallEcho.projectileObserved(engine)) {
            tppProjectileFirstSeenAt = ctx.elapsed
            ctx.log.info("[ASTD-Automation] TPP projectile first seen at elapsed=${ctx.elapsed}")
        }

        when (tppPhase) {
            TPP_PHASE_FIRE -> {
                if (ship != null && weapon != null && ctx.elapsed >= 0.5f) {
                    weapon.setRemainingCooldownTo(0f)
                    weapon.setForceFireOneFrame(true)
                }
                if (ship != null && weapon != null && ctx.elapsed >= 1.5f && tppProjectileFirstSeenAt < 0f && !ctx.starfallEcho.fallbackSpawned) {
                    ctx.starfallEcho.fallbackSpawned = true
                    ctx.starfallEcho.spawnFullSpeedProbeProjectile(ctx, engine, ship, weapon)
                }
                if (tppProjectileFirstSeenAt >= 0f && ctx.elapsed - tppProjectileFirstSeenAt >= TPP_PRE_PAUSE_FLIGHT_SECONDS) {
                    tppCapturePending = "BeforePause"
                    tppPhase = TPP_PHASE_PAUSE_ARMED
                }
            }

            TPP_PHASE_PAUSE_ARMED -> {
                if (tppCapturePending == null) {
                    engine.isPaused = true
                    tppPhaseStartedAt = ctx.elapsed
                    tppPhase = TPP_PHASE_PAUSED
                    ctx.log.info("[ASTD-Automation] TPP paused at elapsed=${ctx.elapsed}")
                }
            }

            TPP_PHASE_PAUSED -> {
                // 对照组：-Dastd.tpp.pauseSeconds=0 时暂停仅持续一帧（VFX 状态等价于无暂停直通）。
                val pauseSeconds = System.getProperty("astd.tpp.pauseSeconds")?.toFloatOrNull() ?: TPP_PAUSE_SECONDS
                if (ctx.elapsed - tppPhaseStartedAt >= pauseSeconds) {
                    tppCapturePending = "DuringPause"
                    tppPhase = TPP_PHASE_RESUME_ARMED
                }
            }

            TPP_PHASE_RESUME_ARMED -> {
                if (tppCapturePending == null) {
                    engine.isPaused = false
                    tppPhaseStartedAt = ctx.elapsed
                    tppPhase = TPP_PHASE_RESUMED
                    ctx.log.info("[ASTD-Automation] TPP resumed at elapsed=${ctx.elapsed}")
                }
            }

            TPP_PHASE_RESUMED -> {
                if (ctx.elapsed - tppPhaseStartedAt >= TPP_POST_RESUME_FLIGHT_SECONDS) {
                    tppCapturePending = "AfterResume"
                    tppPhase = TPP_PHASE_FINISH_ARMED
                }
            }

            TPP_PHASE_FINISH_ARMED -> {
                if (tppCapturePending == null) {
                    ctx.writeDiagnostics("Completed", ship)
                    ctx.writeTelemetry("Completed", ship, weapon)
                    tppPhase = TPP_PHASE_DONE
                    ctx.log.info("[ASTD-Automation] TPP completed at elapsed=${ctx.elapsed}")
                }
            }
        }
    }

    override fun renderCapture(ctx: AutomationCombatContext) {
        // 截图只能在渲染帧内取 framebuffer（helper 仅对 "Completed" 状态抓帧，最多 3 帧）：
        // 相位机把捕获点写成 pending 标签，这里消费并各抓一帧 BeforePause/DuringPause/AfterResume。
        val label = tppCapturePending ?: return
        tppCapturePending = null
        val ship = ctx.starfallEcho.findStageShip(ctx.engine)
        ctx.writeDiagnostics(label, ship)
        ctx.writeTelemetry(
            "Completed",
            ship,
            ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
        )
        ctx.log.info("[ASTD-Automation] TPP captured $label at elapsed=${ctx.elapsed}")
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        appendRuntimeTelemetryDiagnostics(json, vfxTelemetry)
    }

    private companion object {
        // trail_pause_probe 探针：相位机与计时窗口（秒）。
        private const val TPP_PHASE_FIRE = "FIRE"
        private const val TPP_PHASE_PAUSE_ARMED = "PAUSE_ARMED"
        private const val TPP_PHASE_PAUSED = "PAUSED"
        private const val TPP_PHASE_RESUME_ARMED = "RESUME_ARMED"
        private const val TPP_PHASE_RESUMED = "RESUMED"
        private const val TPP_PHASE_FINISH_ARMED = "FINISH_ARMED"
        private const val TPP_PHASE_DONE = "DONE"
        private const val TPP_PRE_PAUSE_FLIGHT_SECONDS = 0.1f
        private const val TPP_PAUSE_SECONDS = 1.2f

        // 恢复后观测窗：2880su/s 下 0.05s = 144su，保证弹体仍在取景内（视口右界 x≈633）。
        private const val TPP_POST_RESUME_FLIGHT_SECONDS = 0.05f
    }
}
