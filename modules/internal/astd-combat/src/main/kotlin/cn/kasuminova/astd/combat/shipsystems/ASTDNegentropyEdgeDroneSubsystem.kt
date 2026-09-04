package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.renderer.effect.hullmods.ASTDNegentropyEdgeVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import org.magiclib.subsystems.CombatUI.SpriteDimWrapper
import org.magiclib.subsystems.drones.MagicDroneSubsystem

class ASTDNegentropyEdgeDroneSubsystem(ship: ShipAPI) : MagicDroneSubsystem(ship) {
    private var lastShiftFrom: Vector2f? = null
    private var lastShiftFacing: Float = 0f
    private var lastShiftDrones: List<ShipAPI> = emptyList()

    override fun getBaseInDuration(): Float = 0.1f
    override fun getBaseActiveDuration(): Float = 0.1f
    override fun getBaseOutDuration(): Float = 0.1f
    override fun getBaseCooldownDuration(): Float = 0.1f
    override fun getMaxCharges(): Int = 0
    override fun shouldActivateAI(amount: Float): Boolean = false
    override fun getDisplayText(): String = "共轭终端"
    override fun getStateText(): String = "轨道同步"
    override fun getMaxDeployedDrones(): Int = 2
    override fun getMaxDroneCharges(): Int = 2
    override fun getDroneCreationTime(): Float = 8f
    override fun getDroneVariant(): String = "astd_conjugate_terminal_wing"
    override fun getDroneDimWrapper(): SpriteDimWrapper = SpriteDimWrapper(droneSprite())
    override fun usesChargesOnActivate(): Boolean = false
    override fun hasSeparateDroneCharges(): Boolean = true
    override fun shouldSpawnDrone(): Boolean = ship.isAlive && !ship.isHulk

    fun prepareForCollapseShift() {
        lastShiftFrom = Vector2f(ship.location)
        lastShiftFacing = ship.facing
        lastShiftDrones = collectActiveDroneMembers()
    }

    fun syncDronesAfterCollapseShift() {
        val from = lastShiftFrom ?: return
        lastShiftFrom = null
        val deltaFacing = Misc.getAngleDiff(lastShiftFacing, ship.facing)
        val drones = lastShiftDrones
        lastShiftDrones = emptyList()
        for (drone in drones) {
            if (!drone.isAlive || drone.isHulk) continue
            val offset = Vector2f.sub(drone.location, from, null)
            val rotatedOffset = VectorUtils.rotate(offset, deltaFacing, Vector2f())
            val oldLoc = Vector2f(drone.location)
            val newLoc = Vector2f(ship.location.x + rotatedOffset.x, ship.location.y + rotatedOffset.y)
            drone.location.set(newLoc)
            drone.facing = normalizeAngle(drone.facing + deltaFacing)
            drone.velocity.set(VectorUtils.rotate(drone.velocity, deltaFacing, Vector2f()))
            ASTDNegentropyEdgeVfx.spawnLargeShiftDistortion(Global.getCombatEngine(), oldLoc, 0.35f)
            ASTDNegentropyEdgeVfx.spawnLargeShiftDistortion(Global.getCombatEngine(), newLoc, 0.45f)
        }
    }

    private fun collectActiveDroneMembers(): List<ShipAPI> {
        val out = LinkedHashSet<ShipAPI>()
        for (leader in activeWings.keys) {
            if (leader.isAlive && !leader.isHulk) out += leader
            val members = try { leader.wing?.wingMembers } catch (_: Throwable) { null }
            if (members != null) {
                for (member in members) {
                    if (member.isAlive && !member.isHulk) out += member
                }
            }
        }
        return out.toList()
    }

    private fun droneSprite(): SpriteAPI = try {
        Global.getSettings().getSprite("graphics/ships/astd_conjugate_terminal.png")
    } catch (_: Throwable) {
        Global.getSettings().getSprite("ui", "ship_arrow")
    }

    private fun normalizeAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f
}