package cn.kasuminova.astd.combat.automation.base

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.StarfallEchoStageAccess
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxLayout
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f

/**
 * 坠星残响取景舞台（[StarfallEchoStageAccess] 实现，战斗级生命周期）。
 *
 * 持有 xc_001 默认场景与 trail_pause_probe 探针共享的舞台状态：锚点、兜底弹体
 * （武器拒射时 spawnProjectile 直出）与合成截图曲线取景参数。诊断 JSON 尾部对所有场景
 * 读取 [fallbackProjectile] 三字段，故本舞台由枢纽持有并随上下文开放。
 */
class StarfallEchoStage : StarfallEchoStageAccess {

    private val captureCenter = Vector2f(100f, 0f)
    private val playerAnchor = Vector2f(-260f, 0f)
    private val projectilePreviewAnchor = Vector2f(40f, 0f)
    private val enemyAnchor = Vector2f(900f, 0f)

    override var fallbackSpawned = false

    override var fallbackProjectile: DamagingProjectileAPI? = null
        private set

    private var fallbackProjectileSpawnedAt = -1f

    override fun lockCamera(engine: CombatEngineAPI) {
        AutomationEvidence.lockCameraAt(engine, captureCenter, 600f)
    }

    override fun findStageShip(engine: CombatEngineAPI): ShipAPI? {
        return engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == ASTDInGameAutomationScenario.SHIP_ID ||
                    ship.variant?.hullVariantId == ASTDInGameAutomationScenario.VARIANT_ID
        }
    }

    override fun arrangeShips(engine: CombatEngineAPI, playerShip: ShipAPI?) {
        playerShip?.let { AutomationEvidence.stabilizeShip(it, playerAnchor, 0f, allowFire = true) }
        engine.ships
            .filter { it !== playerShip && it.owner != playerShip?.owner }
            .forEach { AutomationEvidence.stabilizeShip(it, enemyAnchor, 180f, allowFire = false) }
    }

    override fun alignProjectilesForEvidence(ctx: AutomationCombatContext, engine: CombatEngineAPI) {
        fallbackProjectile?.let { alignProjectileForEvidence(ctx, it) }
        engine.projectiles
            .filter { it.projectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID }
            .forEach { projectile ->
                if (projectile is DamagingProjectileAPI) alignProjectileForEvidence(ctx, projectile)
            }
    }

    override fun spawnFallbackProjectile(ctx: AutomationCombatContext, engine: CombatEngineAPI, ship: ShipAPI, weapon: WeaponAPI) {
        val location = Vector2f(projectilePreviewAnchor)
        val velocity = Vector2f(ship.velocity ?: Vector2f())
        val projectile = engine.spawnProjectile(
            ship,
            weapon,
            ASTDInGameAutomationScenario.WEAPON_ID,
            location,
            weapon.currAngle,
            velocity,
        ) as? DamagingProjectileAPI

        if (projectile != null) {
            fallbackProjectile = projectile
            fallbackProjectileSpawnedAt = ctx.elapsed
            projectile.velocity.x = projectile.moveSpeed
            projectile.velocity.y = 0f
            alignFallbackProjectileForEvidence(ctx)
            ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine)
            ctx.log.info("[ASTD-Automation] fallback spawned ${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID} through ${ASTDInGameAutomationScenario.WEAPON_ID}")
        }
    }

    /** 探针专用全速 spawn：不做取景曲线对齐，弹体按 spec 弹速自由飞行（正对 +x）。 */
    override fun spawnFullSpeedProbeProjectile(ctx: AutomationCombatContext, engine: CombatEngineAPI, ship: ShipAPI, weapon: WeaponAPI) {
        val projectile = engine.spawnProjectile(
            ship,
            weapon,
            ASTDInGameAutomationScenario.WEAPON_ID,
            Vector2f(projectilePreviewAnchor),
            0f,
            Vector2f(),
        ) as? DamagingProjectileAPI ?: return
        projectile.velocity.x = projectile.moveSpeed
        projectile.velocity.y = 0f
        ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine)
        ctx.log.info("[ASTD-Automation] TPP spawned ${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID} at full spec speed ${projectile.moveSpeed}")
    }

    override fun projectileObserved(engine: CombatEngineAPI): Boolean {
        val telemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
        if (telemetry.lastProjectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID) return true
        return engine.projectiles.any { it.projectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID }
    }

    private fun vfxObserved(engine: CombatEngineAPI): Boolean {
        val telemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
        return telemetry.trackedCount > 0 &&
                telemetry.lastProjectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID
    }

    private fun evidenceReady(engine: CombatEngineAPI): Boolean {
        val telemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
        // Static Trail 迁移（2026-09）后拖尾长度由 BoxUtil 托管、不再有可视长度遥测；
        // 截图成熟度按飞行时长判定（拖尾三段时长远短于本窗口）。
        return telemetry.lastElapsed >= SCREENSHOT_FLIGHT_SECONDS
    }

    override fun currentState(ctx: AutomationCombatContext, engine: CombatEngineAPI, ship: ShipAPI?, weapon: WeaponAPI?): String {
        ctx.failureReason = null
        if (ship == null) {
            ctx.failureReason = "xc_001 ship not found in combat"
            return if (ctx.elapsed > 10f) "Failed" else "CombatReady"
        }
        if (weapon == null) {
            ctx.failureReason = "starfall_echo weapon not found on xc_001"
            return if (ctx.elapsed > 10f) "Failed" else "CombatReady"
        }
        if (projectileObserved(engine) && vfxObserved(engine) && evidenceReady(engine)) return "Completed"
        if (projectileObserved(engine)) return "FireObserved"
        return "CombatReady"
    }

    override fun renderDefaultCapture(ctx: AutomationCombatContext) {
        if (!ctx.completed || ctx.visualFramesWritten >= 3) return
        if (ctx.visualFramesWritten > 0 && ctx.elapsed - ctx.lastVisualFrameAt < 0.18f) return

        val engine = ctx.engine
        lockCamera(engine)
        val ship = findStageShip(engine)
        arrangeShips(engine, ship)
        alignProjectilesForEvidence(ctx, engine)
        ctx.lastVisualFrameAt = ctx.elapsed
        ctx.visualFramesWritten++
        ctx.writeDiagnostics("Completed", ship)
        ctx.writeTelemetry(
            "Completed",
            ship,
            ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
        )
        if (ctx.visualFramesWritten == 3) {
            ctx.log.info("[ASTD-Automation] visual evidence frames written after render")
        }
    }

    private fun alignFallbackProjectileForEvidence(ctx: AutomationCombatContext) {
        val projectile = fallbackProjectile ?: return
        driveFallbackProjectileCurve(ctx, projectile)
    }

    private fun alignProjectileForEvidence(ctx: AutomationCombatContext, projectile: DamagingProjectileAPI) {
        if (projectile === fallbackProjectile) {
            driveFallbackProjectileCurve(ctx, projectile)
            return
        }
        projectile.location.y = curvePositionAt(0f).y
        projectile.facing = 0f
    }

    private fun driveFallbackProjectileCurve(ctx: AutomationCombatContext, projectile: DamagingProjectileAPI) {
        val age = (ctx.elapsed - fallbackProjectileSpawnedAt).coerceAtLeast(0f)
        projectile.location.set(curvePositionAt(age))
        val velocity = curveVelocityAt(age)
        projectile.velocity.set(velocity)
        projectile.facing = org.lazywizard.lazylib.VectorUtils.getFacing(velocity)
    }

    private fun curvePositionAt(age: Float): Vector2f {
        val track = automationPreviewTrack(age)
        val scale = automationReferenceWorldUnitsPerPixel()
        return Vector2f(
            projectilePreviewAnchor.x + track.headOffset.x * scale,
            projectilePreviewAnchor.y + track.headOffset.y * scale,
        )
    }

    private fun curveVelocityAt(age: Float): Vector2f {
        val step = 1f / 120f
        val previous = curvePositionAt((age - step).coerceAtLeast(0f))
        val next = curvePositionAt(age + step)
        return Vector2f((next.x - previous.x) / (step * 2f), (next.y - previous.y) / (step * 2f))
    }

    /**
     * 坠星残响合成截图场景的参考参数（旧管线 spec 的数值，2026-09 Static Trail 迁移后 policy 不再建模这些字段，
     * 按场景常量固化——本曲线只服务截图取景，与运行期拖尾无关）。
     */
    private fun automationPreviewTrack(age: Float): ASTDProjectileVfxLayout.PreviewFlightTrack {
        return ASTDProjectileVfxLayout.previewFlightTrack(
            trailStartWidth = AUTOMATION_REF_TRAIL_START_WIDTH,
            elapsed = age,
            durationSeconds = AUTOMATION_REF_DURATION_SECONDS,
            flightEndRatio = AUTOMATION_FLIGHT_END_RATIO,
            dissolveStartRatio = AUTOMATION_REF_DISSOLVE_START_RATIO,
            preDissolveFraction = AUTOMATION_PRE_DISSOLVE_FRACTION,
            captureWidth = AUTOMATION_REF_CAPTURE_WIDTH,
            captureHeight = AUTOMATION_REFERENCE_CAPTURE_HEIGHT,
            curveAmount = AUTOMATION_CURVE_AMOUNT,
            curveFrequency = AUTOMATION_CURVE_FREQUENCY,
            curved = true,
        )
    }

    private fun automationReferenceWorldUnitsPerPixel(): Float {
        val pixelHeight = try {
            Display.getHeight().toFloat().takeIf { it > 0f } ?: 1f
        } catch (_: Throwable) {
            1f
        }
        return ASTDProjectileVfxLayout.referenceWorldUnitsPerPixel(pixelHeight)
    }

    private companion object {
        private const val AUTOMATION_CURVE_AMOUNT = 96f
        private const val AUTOMATION_CURVE_FREQUENCY = 0.8f
        private const val AUTOMATION_REFERENCE_CAPTURE_HEIGHT = 600f

        private const val AUTOMATION_FLIGHT_END_RATIO = 0.6f
        private const val AUTOMATION_PRE_DISSOLVE_FRACTION = 0.82f
        private const val SCREENSHOT_FLIGHT_SECONDS = 0.13333334f

        private const val AUTOMATION_REF_TRAIL_START_WIDTH = 96f
        private const val AUTOMATION_REF_DURATION_SECONDS = 1.25f
        private const val AUTOMATION_REF_DISSOLVE_START_RATIO = 0.6f
        private const val AUTOMATION_REF_CAPTURE_WIDTH = 1846f
    }
}
