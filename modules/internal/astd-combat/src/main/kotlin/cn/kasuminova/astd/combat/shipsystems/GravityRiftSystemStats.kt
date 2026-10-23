package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.render.BloomFlareVfx
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats.Companion.ANCHOR_SAMPLES
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats.Companion.DRONE_LINGER_SECONDS
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats.Companion.TELEMETRY_BEAM_KEY
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats.Companion.TELEMETRY_MINES_KEY
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats.Companion.TELEMETRY_TARGET_KEY
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats.Companion.TELEMETRY_VORTEX_KEY
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BloomFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.explosion.BloomFlareVfxImpl
import cn.kasuminova.astd.renderer.effect.lens.GravityRiftVortexVisual
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.loading.WeaponGroupSpec
import com.fs.starfarer.api.loading.WeaponGroupType
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class GravityRiftSystemStats : BaseShipSystemScript() {

    private class RiftActivation(
        val target: ShipAPI,
        val anchorOffset: Vector2f,
        val vortex: GravityRiftVortexVisual,
    ) {
        var beamDrone: ShipAPI? = null
    }

    private var cachedDamageReduction: Float? = null

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        when (state) {
            ShipSystemStatsScript.State.IN -> {
                prepareActivation(engine, ship)
                applySustainEffects(stats, ship, id, state, effectLevel)
                suppressPhaseCloak(ship)
            }
            ShipSystemStatsScript.State.ACTIVE -> {
                fireBeam(engine, ship)
                applySustainEffects(stats, ship, id, state, effectLevel)
                suppressPhaseCloak(ship)
            }
            ShipSystemStatsScript.State.OUT -> {
                applySustainEffects(stats, ship, id, state, effectLevel)
            }
            else -> Unit
        }
    }

    private fun applySustainEffects(
        stats: MutableShipStatsAPI,
        ship: ShipAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val values = GravityRiftTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        cachedDamageReduction = values.damageTakenReduction
        val damageMult = GravityRiftTuning.damageTakenMult(values.damageTakenReduction)

        stats.hullDamageTakenMult.modifyMult(id, damageMult)
        stats.armorDamageTakenMult.modifyMult(id, damageMult)
        stats.shieldDamageTakenMult.modifyMult(id, damageMult)
        stats.empDamageTakenMult.modifyMult(id, damageMult)

        val (jitterLevel, rangeBonus) = GravityRiftTuning.jitterLevelAndRange(
            state, effectLevel, ship.system.chargeUpDur,
        )
        ship.setJitter(id, JITTER_COLOR, jitterLevel, 3, 0f, rangeBonus)
        ship.setJitterUnder(id, JITTER_UNDER_COLOR, jitterLevel, 25, 0f, 7f + rangeBonus)
    }

    // 钉 cooldownRemaining 使 COOLDOWN 态下按键不可激活（原版 ChargeTracker 行为）
    private fun suppressPhaseCloak(ship: ShipAPI) {
        val cloak = ship.phaseCloak ?: return
        val target = GravityRiftTuning.phaseSuppressCooldown(
            cloak.cooldownRemaining,
            cloak.state == ShipSystemAPI.SystemState.COOLDOWN
        ) ?: return
        cloak.setCooldownRemaining(target)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return

        stats.hullDamageTakenMult.unmodify(id)
        stats.armorDamageTakenMult.unmodify(id)
        stats.shieldDamageTakenMult.unmodify(id)
        stats.empDamageTakenMult.unmodify(id)

        val engine = Global.getCombatEngine() ?: return
        val activation = engine.customData[activationKey(ship)] as? RiftActivation ?: return

        activation.beamDrone?.let { if (it.isAlive) engine.removeEntity(it) }
        activation.vortex.forceFadeOut()
        engine.customData.remove(activationKey(ship))
    }

    private fun prepareActivation(engine: CombatEngineAPI, ship: ShipAPI) {
        if (engine.customData[activationKey(ship)] != null) return

        val target = findTarget(ship)
        if (target == null) {
            log.error("引力裂隙发生器激活瞬间无有效目标（ship=${ship.id}），isUsable 闸门被绕过？")
            return
        }

        val anchorOffset = sampleVortexAnchor(target)
        val vortexRadius = 65f
        val vortex = GravityRiftVortexVisual.spawn(engine, target, anchorOffset, vortexRadius)

        engine.customData[activationKey(ship)] = RiftActivation(target, anchorOffset, vortex)
        engine.customData[TELEMETRY_TARGET_KEY + ship.id] = target.id
        engine.customData[TELEMETRY_VORTEX_KEY + ship.id] = 1
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (state != ShipSystemStatsScript.State.IN &&
            state != ShipSystemStatsScript.State.ACTIVE &&
            state != ShipSystemStatsScript.State.OUT
        ) return null
        return when (index) {
            0 -> cachedDamageReduction?.let {
                ShipSystemStatsScript.StatusData(
                    I18n.t(
                        I18n.Categories.MOD,
                        "ui.grav_rift.status.damage_reduction",
                        "rd" to Math.round(it * 100f),
                    ),
                    false,
                )
            }
            1 -> if (state == ShipSystemStatsScript.State.OUT) null else ShipSystemStatsScript.StatusData(
                I18n[I18n.Categories.MOD, "ui.grav_rift.status.phase_disabled"],
                true,
            )
            else -> null
        }
    }

    // spawnProjectile 不支持 specClass=beam，真实光束经隐藏武器挂载在 FX drone（dem_drone）上
    private fun fireBeam(engine: CombatEngineAPI, ship: ShipAPI) {
        val activation = engine.customData[activationKey(ship)] as? RiftActivation ?: return
        if (activation.beamDrone != null) return

        val hullSpec = Global.getSettings().getHullSpec(FX_DRONE_HULL_ID)
        val variant = Global.getSettings().createEmptyVariant(FX_DRONE_HULL_ID, hullSpec)
        variant.addWeapon(FX_DRONE_SLOT_ID, BEAM_WEAPON_ID)
        val group = WeaponGroupSpec(WeaponGroupType.LINKED)
        group.addSlot(FX_DRONE_SLOT_ID)
        variant.addWeaponGroup(group)

        val drone = engine.createFXDrone(variant)
        drone.mutableStats.hullDamageTakenMult.modifyMult(FX_DRONE_MOD_ID, 0f)
        drone.mutableStats.energyWeaponRangeBonus.applyMods(ship.mutableStats.energyWeaponRangeBonus)
        drone.mutableStats.beamWeaponRangeBonus.applyMods(ship.mutableStats.beamWeaponRangeBonus)
        drone.mutableStats.energyWeaponDamageMult.applyMods(ship.mutableStats.energyWeaponDamageMult)
        drone.mutableStats.beamWeaponDamageMult.applyMods(ship.mutableStats.beamWeaponDamageMult)
        drone.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP, Float.MAX_VALUE, ship)
        drone.owner = ship.owner
        drone.collisionClass = CollisionClass.NONE
        drone.giveCommand(ShipCommand.SELECT_GROUP, null, 0)
        drone.isDrone = true

        @Suppress("UNCHECKED_CAST")
        val beamWeapon = drone.allWeapons[0] as WeaponAPI
        val anchor = activation.vortex.currentAnchor()
        drone.location.set(anchor)
        drone.facing = Misc.getAngleInDegrees(anchor, activation.target.location)
        drone.addListener(
            BeamDroneController(
                engine, drone, beamWeapon, activation.target, activation.vortex,
            ),
        )
        engine.addEntity(drone)

        activation.beamDrone = drone
        engine.customData[TELEMETRY_BEAM_KEY + ship.id] = 1
    }

    private class BeamDroneController(
        private val engine: CombatEngineAPI,
        private val drone: ShipAPI,
        private val weapon: WeaponAPI,
        private val target: ShipAPI,
        private val vortex: GravityRiftVortexVisual,
    ) : AdvanceableListener {
        private var elapsed = 0f
        private var lastFacing = drone.facing
        private var flaresSpawned = false

        override fun advance(amount: Float) {
            elapsed += amount
            if (elapsed >= GravityRiftTuning.BEAM_DURATION + DRONE_LINGER_SECONDS || !drone.isAlive) {
                if (drone.isAlive) engine.removeEntity(drone)
                return
            }
            val from = vortex.currentAnchor()
            drone.location.set(from)
            if (target.isAlive && !target.isHulk) {
                lastFacing = Misc.getAngleInDegrees(from, target.location)
            }
            drone.facing = lastFacing
            weapon.currAngle = lastFacing
            if (!flaresSpawned) {
                flaresSpawned = true
                spawnAnchorFlares()
            }
            if (elapsed < GravityRiftTuning.BEAM_DURATION) {
                weapon.setForceFireOneFrame(true)
            }
            weapon.updateBeamFromPoints()
        }

        private fun spawnAnchorFlares() {
            val follow = BloomFlareVfx.FlareFollow { out ->
                out.set(vortex.currentAnchor())
                lastFacing + 90f
            }
            val flare1 = BloomFlareSpec(
                style = BoxFlareStyle.SMOOTH,
                sizeStart = 50f, sizeEnd = 50f,
                heightStart = 50f, heightEnd = 50f,
                facingDeg = lastFacing + 90f,
                coreColor = ASTDColor(colorToLong(FLARE_CORE_COLOR)),
                fringeColor = ASTDColor(colorToLong(FLARE_FRINGE_COLOR)),
                glowPower = 0.7f,
                noisePower = 0f,
                flicker = true,
            )
            val flare2 = BloomFlareSpec(
                style = BoxFlareStyle.SMOOTH_DISC,
                sizeStart = 240f, sizeEnd = 240f,
                heightStart = 25f, heightEnd = 25f,
                facingDeg = lastFacing + 90f,
                coreColor = ASTDColor(colorToLong(FLARE_CORE_COLOR)),
                fringeColor = ASTDColor(colorToLong(FLARE_FRINGE_COLOR)),
                glowPower = 1f,
                noisePower = 0f,
                flicker = true,
            )
            BloomFlareVfxImpl.spawn(
                engine, vortex.currentAnchor(),
                GravityRiftTuning.BEAM_DURATION + FLARE_FADE_TAIL_SECONDS,
                listOf(flare1, flare2),
                follow = follow,
            )
        }
    }

    private fun sampleVortexAnchor(target: ShipAPI): Vector2f {
        var first: Vector2f? = null
        repeat(ANCHOR_SAMPLES) {
            val angle = MathUtils.getRandomNumberInRange(0f, 360f)
            val radiusFrac = MathUtils.getRandomNumberInRange(ANCHOR_RADIUS_MIN_FRAC, ANCHOR_RADIUS_MAX_FRAC)
            val offset = Misc.getUnitVectorAtDegreeAngle(angle)
            offset.scale(target.collisionRadius * radiusFrac)
            if (first == null) first = Vector2f(offset)
            val point = Vector2f.add(target.location, offset, null)
            val shield = target.shield
            if (shield == null || !shield.isOn || !shield.isWithinArc(point)) {
                return Vector2f(offset)
            }
        }
        return first ?: Vector2f(0f, 0f)
    }

    private fun findTarget(ship: ShipAPI): ShipAPI? {
        var target: ShipAPI? = null
        if (ship.shipAI != null && ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM) as? ShipAPI
        }
        if (target == null) target = ship.shipTarget
        if (!isValidTarget(ship, target)) return null

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravityRiftTuning.SYSTEM_RANGE)
        val dist = Misc.getDistance(ship.location, target!!.location)
        val radSum = ship.collisionRadius + target.collisionRadius
        return if (dist <= range + radSum) target else null
    }

    private fun isValidTarget(ship: ShipAPI, target: ShipAPI?): Boolean =
        target != null && target !== ship && target.owner != ship.owner &&
                !target.isFighter && !target.isHulk && !target.isPhased && target.isAlive

    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean =
        findTarget(ship) != null

    override fun getInfoText(system: ShipSystemAPI, ship: ShipAPI): String? {
        if (system.isOutOfAmmo) return null
        if (system.state != ShipSystemAPI.SystemState.IDLE) return null
        if (findTarget(ship) != null) return null
        val raw = ship.shipTarget
        return if (raw != null && raw !== ship && raw.owner != ship.owner && raw.isAlive && !raw.isHulk && !raw.isFighter) {
            I18n[I18n.Categories.MOD, "ui.grav_rift.info.out_of_range"]
        } else {
            I18n[I18n.Categories.MOD, "ui.grav_rift.info.no_target"]
        }
    }

    companion object {
        private val log = Global.getLogger(GravityRiftSystemStats::class.java)

        private const val BEAM_WEAPON_ID = "astd_grav_rift_beam"

        private const val FX_DRONE_HULL_ID = "dem_drone"
        private const val FX_DRONE_SLOT_ID = "WS 001"

        private const val FX_DRONE_MOD_ID = "astd_grav_rift_fx"

        private const val DRONE_LINGER_SECONDS = 0.5f

        private const val ANCHOR_SAMPLES = 8
        private const val ANCHOR_RADIUS_MIN_FRAC = 2f
        private const val ANCHOR_RADIUS_MAX_FRAC = 2.5f

        private val JITTER_COLOR = Color(255, 80, 80, 55)
        private val JITTER_UNDER_COLOR = Color(255, 40, 40, 155)

        private val FLARE_CORE_COLOR = Color(255, 255, 255, 255)
        private val FLARE_FRINGE_COLOR = Color(255, 60, 60, 255)

        private const val FLARE_FADE_TAIL_SECONDS = 0.3f

        private fun colorToLong(c: Color): Long =
            ((c.alpha.toLong() and 0xFF) shl 24) or
                    ((c.red.toLong() and 0xFF) shl 16) or
                    ((c.green.toLong() and 0xFF) shl 8) or
                    (c.blue.toLong() and 0xFF)

        private const val ACTIVATION_KEY = "astd_grav_rift_activation:"

        const val TELEMETRY_TARGET_KEY = "astd_grav_rift_telemetry_target:"
        const val TELEMETRY_VORTEX_KEY = "astd_grav_rift_telemetry_vortex:"
        const val TELEMETRY_BEAM_KEY = "astd_grav_rift_telemetry_beam:"
        const val TELEMETRY_MINES_KEY = "astd_grav_rift_telemetry_mines:"

        private fun activationKey(ship: ShipAPI): String = "$ACTIVATION_KEY${ship.id}"
    }
}
