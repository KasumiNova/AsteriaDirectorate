package cn.kasuminova.astd.combat.automation.scenario.production

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.hullmods.lens.LENS_DUAL_MODE_CONFIG
import cn.kasuminova.astd.combat.hullmods.lens.LensArrayCoreHullModIds
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

/**
 * 决明级 phase1 实机场景。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class LensPhase1Scenario : AbstractAutomationScenario() {
    private var lensMarksInjected = false
    private val lensAnchor = Vector2f(-260f, 0f)

    override val scenarioId: String = ASTDInGameAutomationScenario.LENS_PHASE1_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isLensPhase1Enabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockArcProductionCamera(engine)
        // 与 ARC production 一致：reserves 部署放到 advance()，init 阶段战斗渲染器尚未就绪，
        // 此时调用 spawnFleetMember -> setPlayerShip 会触发原版 arcRenderer NPE。
        arrangeLensPhase1Ships(engine)
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.LENS_PHASE1_SCENARIO_ID} combat plugin initialized")
    }

    private fun deployLensPhase1ReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployLensPhase1Side(engine, FleetSide.PLAYER)
        deployLensPhase1Side(engine, FleetSide.ENEMY)
    }

    private fun deployLensPhase1Side(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.isSuppressDeploymentMessages = true
        val reserves = manager.reservesCopy.toList()
        if (reserves.isEmpty()) return

        var allyIndex = 0
        var enemyIndex = 0
        for (member in reserves) {
            val hullId = member.hullId ?: continue
            if (findShipByHull(engine, hullId) != null && hullId == LensArrayCoreHullModIds.HULL_ID) {
                manager.removeFromReserves(member)
                continue
            }

            val anchor = when {
                side == FleetSide.ENEMY -> Vector2f(900f + enemyIndex++ * 170f, 20f)
                hullId == LensArrayCoreHullModIds.HULL_ID -> lensAnchor
                else -> Vector2f(-520f, -260f + allyIndex++ * 150f)
            }
            val facing = if (side == FleetSide.ENEMY) 180f else 0f
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = side == FleetSide.ENEMY)
        }
    }

    private fun arrangeLensPhase1Ships(engine: CombatEngineAPI) {
        val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
        lens?.let { stabilizeShip(it, lensAnchor, 0f, allowFire = false) }
        var allyIndex = 0
        engine.ships
            .filter { it !== lens && it.owner == lens?.owner && !it.isFighter }
            .forEach { stabilizeShip(it, Vector2f(-520f, -260f + allyIndex++ * 150f), 0f, allowFire = false) }
        engine.ships
            .filter { ship -> lens != null && ship.owner != lens.owner && !ship.isFighter }
            .forEachIndexed { index, ship ->
                stabilizeShip(ship, Vector2f(900f + index * 170f, 20f), 180f, allowFire = false, preserveAI = true)
            }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployLensPhase1ReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeLensPhase1Ships(engine)

        val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
        lens?.let { engine.setPlayerShipExternal(it) }
        lens?.shield?.let { if (!it.isOn) it.toggleOn() }

        // 对决明级自身维持 3 层误差/深水标记，验证 applier 真的改了承伤。
        // 标记每层 5s 会过期，且本场景 setDoNotEndCombat 后会长时间运行（观测点在很晚），
        // 故每帧把不足的层数补齐到 3（applyOrRefresh 同时刷新时长），保证稳态诊断读到 3 层。
        if (lens != null && ctx.elapsed > 1.0f) {
            val driftNeeded = LENS_SELF_MARK_STACKS - LensMarks.driftStacks(lens)
            if (driftNeeded > 0) LensMarks.applyDriftMark(engine, lens, lens, driftNeeded)
            val deepWaterNeeded = LENS_SELF_MARK_STACKS - LensMarks.deepWaterStacks(lens)
            if (deepWaterNeeded > 0) LensMarks.applyDeepWaterMark(engine, lens, lens, deepWaterNeeded)
            if (!lensMarksInjected) {
                lensMarksInjected = true
                ctx.log.info("[ASTD-Automation] lens self marks injected: drift=$LENS_SELF_MARK_STACKS, deepWater=$LENS_SELF_MARK_STACKS")
            }
        }

        val state = when {
            lens != null && lensMarksInjected && ctx.elapsed > 2.0f -> "Completed"
            lens == null && ctx.elapsed > 8f -> {
                ctx.failureReason = "gravitational lens missing: ${LensArrayCoreHullModIds.HULL_ID}"
                "Failed"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: lens_phase1_foundation evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, lens)
            ctx.writeTelemetry(state, lens, null)
        }
    }

    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.18f, { findShipByHull(ctx.engine, LensArrayCoreHullModIds.HULL_ID) }) {
            lockArcProductionCamera(ctx.engine)
            arrangeLensPhase1Ships(ctx.engine)
        }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
        val lensVariant = try {
            lens?.variant
        } catch (_: Throwable) {
            null
        }
        val lensShield = try {
            lens?.shield
        } catch (_: Throwable) {
            null
        }
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": 0,")
        json.appendLine("  \"runtimeLastProjectileSpecId\": null,")
        json.appendLine("  \"lensDeployedShipIds\": ${jsonStringList(lensDeployedShipIds(engine))},")
        json.appendLine("  \"lensCoreHullmod\": ${safeBool { lensVariant?.hasHullMod(LensArrayCoreHullModIds.CORE) == true }},")
        json.appendLine("  \"lensNanoHullmod\": ${safeBool { lensVariant?.hasHullMod("astd_nano_restoration_protocol") == true }},")
        json.appendLine("  \"lensSwitcherHullmod\": ${safeBool { lensVariant?.hasHullMod(LENS_DUAL_MODE_CONFIG.switcherId) == true }},")
        json.appendLine("  \"lensCrewedModeHullmod\": ${safeBool { lensVariant?.hasHullMod(LensArrayCoreHullModIds.MODE_CREWED) == true }},")
        json.appendLine("  \"lensShieldOn\": ${safeBool { lensShield?.isOn == true }},")
        json.appendLine(
            "  \"lensShieldArc\": ${
                formatFloat(
                    try {
                        lensShield?.arc ?: lens?.hullSpec?.shieldSpec?.arc ?: 0f
                    } catch (_: Throwable) {
                        0f
                    }
                )
            },"
        )
        json.appendLine(
            "  \"lensFighterBays\": ${
                try {
                    lens?.hullSpec?.fighterBays ?: 0
                } catch (_: Throwable) {
                    0
                }
            },"
        )
        json.appendLine("  \"lensSelfDriftStacks\": ${lens?.let { LensMarks.driftStacks(it) } ?: 0},")
        json.appendLine("  \"lensSelfDeepWaterStacks\": ${lens?.let { LensMarks.deepWaterStacks(it) } ?: 0},")
        json.appendLine(
            "  \"lensSelfHullDamageTakenMult\": ${
                formatFloat(
                    try {
                        lens?.mutableStats?.hullDamageTakenMult?.modifiedValue ?: 0f
                    } catch (_: Throwable) {
                        0f
                    }
                )
            },"
        )
    }

    private companion object {
        // 决明级自标记验收层数（spec：误差/深水各叠 3 层）。
        private const val LENS_SELF_MARK_STACKS = 3
    }
}
