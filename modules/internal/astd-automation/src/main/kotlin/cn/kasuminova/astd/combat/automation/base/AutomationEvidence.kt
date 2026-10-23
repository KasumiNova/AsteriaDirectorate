package cn.kasuminova.astd.combat.automation.base

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f

/**
 * 自动化舞台的通用基础设施（纯函数集合，无战斗状态）。
 *
 * 诊断 JSON 的格式化与转义、舰船钉位与武器组自动开火开关、取景相机锁定、
 * hullmod 与文本键解析判定及 tooltip 核对，供抽象基类委派与各处理器直接调用
 * （枢纽诊断尾部亦经此处读 tooltip 证据）。
 */
object AutomationEvidence {

    /**
     * 武器组自动开火开关（按 weaponIds 泛用于各场景）：
     * Starsector 0.98 中 `setForceFireOneFrame` 对无舰 AI 的舞台舰不生效，
     * 改用原版武器组 autofire 管线：toggleOn 后组内武器 AutofireAI 自行瞄准 shipTarget 开火。
     * 注意：不得每帧 `setRemainingCooldownTo(0f)`：该调用会把武器开火周期反复重置导致零弹体
     * （坠星残响场景因此依赖 spawnProjectile 产出备用弹体）。
     */
    fun setWeaponGroupAutofire(ship: ShipAPI?, enabled: Boolean, weaponIds: Set<String>) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id in weaponIds }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /** 按 hullId 找第一艘在场舰船（含残骸）。 */
    fun findShipByHull(engine: CombatEngineAPI, hullId: String): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.hullSpec?.hullId == hullId }

    /** 钉位：位置/朝向/速度归零、命令封锁；[allowFire]=false 时追加开火封锁，[preserveAI]=true 时保留舰 AI 与目标。 */
    fun stabilizeShip(ship: ShipAPI, location: Vector2f, facing: Float, allowFire: Boolean, preserveAI: Boolean = false) {
        ship.location.set(location)
        ship.velocity.set(0f, 0f)
        ship.facing = facing
        ship.angularVelocity = 0f
        if (!preserveAI) {
            ship.shipAI = null
            ship.shipTarget = null
        }
        ship.setControlsLocked(false)
        ship.isHoldFireOneFrame = !allowFire
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE)
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS)
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT)
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT)
        ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT)
        ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT)
        if (!allowFire) ship.blockCommandForOneFrame(ShipCommand.FIRE)
    }

    /** 取景相机锁定：以 [center] 为中心、[visibleHeight] 世界单位视高（横向按显示宽高比展开），接管视口。 */
    fun lockCameraAt(engine: CombatEngineAPI, center: Vector2f, visibleHeight: Float) {
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
        val visibleWidth = visibleHeight * displayAspect

        viewport.isExternalControl = true
        viewport.set(
            center.x - visibleWidth * 0.5f,
            center.y - visibleHeight * 0.5f,
            visibleWidth,
            visibleHeight,
        )
        viewport.isEverythingNearViewport = true
    }

    /** ARC production / 决明级场景共享相机（中心 (-40,-20)，视高 980su）。 */
    fun lockArcProductionCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, ARC_PRODUCTION_CAMERA_CENTER, ARC_PRODUCTION_CAMERA_VISIBLE_HEIGHT)
    }

    fun hasHullmod(ship: ShipAPI, hullmodId: String): Boolean =
        try {
            ship.variant?.hasHullMod(hullmodId) == true
        } catch (_: Throwable) {
            false
        }

    fun jsonString(value: String?): String = value?.let { "\"${escapeJson(it)}\"" } ?: "null"

    fun jsonStringList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

    fun formatFloat(value: Float): String = "%.4f".format(java.util.Locale.ROOT, value)

    fun distanceSquared(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    fun safeBool(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (_: Throwable) {
            false
        }

    fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    /** ARC production 共享相机中心。 */
    val ARC_PRODUCTION_CAMERA_CENTER: Vector2f = Vector2f(-40f, -20f)

    /** ARC production 共享相机视高。 */
    const val ARC_PRODUCTION_CAMERA_VISIBLE_HEIGHT: Float = 980f
}
