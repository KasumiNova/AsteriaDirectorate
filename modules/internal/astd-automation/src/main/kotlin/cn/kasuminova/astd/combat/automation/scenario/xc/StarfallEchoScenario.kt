package cn.kasuminova.astd.combat.automation.scenario.xc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot

/**
 * xc_001 坠星残响默认场景：未命中任何具体场景时主干按本场景运转（拆分前 if-else 链的 else 分支）。
 *
 * 舞台状态（锚点/兜底弹体/取景曲线）由 [cn.kasuminova.astd.combat.automation.base.StarfallEchoStage]
 * 托管并经 [AutomationCombatContext.starfallEcho] 开放——trail_pause_probe 探针与诊断 JSON 尾部
 * 共享同一舞台，本类只持有相位机外的流程逻辑与 fireMechanism 记录。
 */
class StarfallEchoScenario : AbstractAutomationScenario() {

    /** 开火机制记录（setForceFireOneFrame / spawnProjectileFallback），拆分前枢纽字段，仅赋值供排障阅读。 */
    private var fireMechanism: String? = null

    override val scenarioId: String = ASTDInGameAutomationScenario.SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.SKIP_WHEN_PAUSED

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isEnabled()

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
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.SCENARIO_ID} combat plugin initialized")
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine
        val ship = ctx.starfallEcho.findStageShip(engine)
        val weapon = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID }
        ctx.starfallEcho.lockCamera(engine)
        ctx.starfallEcho.arrangeShips(engine, ship)
        ctx.starfallEcho.alignProjectilesForEvidence(ctx, engine)
        if (ctx.completed && ctx.elapsed - ctx.completedAt >= 0.75f) return

        if (ship != null) {
            engine.setPlayerShipExternal(ship)
            ship.shipAI = null
            ship.setControlsLocked(false)
            ship.isHoldFireOneFrame = false
            ship.shipTarget = null
        }

        if (!ctx.completed && ship != null && weapon != null && ctx.elapsed >= 0.5f) {
            weapon.setRemainingCooldownTo(0f)
            weapon.setForceFireOneFrame(true)
            fireMechanism = fireMechanism ?: "setForceFireOneFrame"
        }

        if (!ctx.completed && ship != null && weapon != null && ctx.elapsed >= 1.5f &&
            !ctx.starfallEcho.projectileObserved(engine) && !ctx.starfallEcho.fallbackSpawned
        ) {
            ctx.starfallEcho.fallbackSpawned = true
            ctx.starfallEcho.spawnFallbackProjectile(ctx, engine, ship, weapon)
            fireMechanism = "spawnProjectileFallback"
        }

        val state = if (ctx.completed) "Completed" else ctx.starfallEcho.currentState(ctx, engine, ship, weapon)
        if (state == "Completed") {
            if (!ctx.completed) {
                ctx.markCompleted()
                ctx.log.info("[ASTD-Automation] Completed: xc_001/starfall_echo/${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID}/VFX observed")
            }
            return
        }

        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, ship)
            ctx.writeTelemetry(state, ship, weapon)
        }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        appendRuntimeTelemetryDiagnostics(json, vfxTelemetry)
    }
}
