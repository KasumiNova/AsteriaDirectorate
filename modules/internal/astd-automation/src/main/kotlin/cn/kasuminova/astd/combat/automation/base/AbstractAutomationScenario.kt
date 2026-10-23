package cn.kasuminova.astd.combat.automation.base

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 场景处理器抽象基类：持有运行上下文，并向处理器体开放 [AutomationEvidence] 的共享工具方法委派。
 */
abstract class AbstractAutomationScenario : AutomationScenario {

    /** 运行上下文（init 注入；一次战斗一个实例）。 */
    protected lateinit var ctx: AutomationCombatContext

    override fun init(ctx: AutomationCombatContext) {
        this.ctx = ctx
    }

    /** 完成帧捕获公共模板：间隔节流后执行 [stageFrame] 取景并写出 Completed 证据帧。 */
    protected fun renderCompletedFrames(intervalSeconds: Float, telemetryShip: () -> ShipAPI?, stageFrame: () -> Unit) {
        if (!ctx.completed || ctx.visualFramesWritten >= 3) return
        if (ctx.visualFramesWritten > 0 && ctx.elapsed - ctx.lastVisualFrameAt < intervalSeconds) return
        stageFrame()
        ctx.lastVisualFrameAt = ctx.elapsed
        ctx.visualFramesWritten++
        val ship = telemetryShip()
        ctx.writeDiagnostics("Completed", ship)
        ctx.writeTelemetry("Completed", ship, null)
    }

    protected fun stabilizeShip(ship: ShipAPI, location: Vector2f, facing: Float, allowFire: Boolean, preserveAI: Boolean = false) =
        AutomationEvidence.stabilizeShip(ship, location, facing, allowFire, preserveAI)

    protected fun findShipByHull(engine: CombatEngineAPI, hullId: String): ShipAPI? =
        AutomationEvidence.findShipByHull(engine, hullId)

    protected fun setWeaponGroupAutofire(ship: ShipAPI?, enabled: Boolean, weaponIds: Set<String>) =
        AutomationEvidence.setWeaponGroupAutofire(ship, enabled, weaponIds)

    protected fun lockCameraAt(engine: CombatEngineAPI, center: Vector2f, visibleHeight: Float) =
        AutomationEvidence.lockCameraAt(engine, center, visibleHeight)

    protected fun lockArcProductionCamera(engine: CombatEngineAPI) =
        AutomationEvidence.lockArcProductionCamera(engine)

    protected fun hasHullmod(ship: ShipAPI, hullmodId: String): Boolean =
        AutomationEvidence.hasHullmod(ship, hullmodId)

    protected fun formatFloat(value: Float): String = AutomationEvidence.formatFloat(value)

    protected fun jsonString(value: String?): String = AutomationEvidence.jsonString(value)

    protected fun jsonStringList(values: List<String>): String = AutomationEvidence.jsonStringList(values)

    protected fun safeBool(block: () -> Boolean): Boolean = AutomationEvidence.safeBool(block)

    protected fun distanceSquared(a: Vector2f, b: Vector2f): Float = AutomationEvidence.distanceSquared(a, b)

    /**
     * 运行期遥测三字段诊断段（runtimeElapsedSeconds/TrackedCount/LastProjectileSpecId）。
     * xc_001、trail_pause_probe 与 hip 场景共用。
     */
    protected fun appendRuntimeTelemetryDiagnostics(json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        json.appendLine("  \"runtimeElapsedSeconds\": ${formatFloat(vfxTelemetry.lastElapsed)},")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
    }
}
