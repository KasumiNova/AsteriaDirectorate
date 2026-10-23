package cn.kasuminova.astd.combat.effect.arc.sevenstars

import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.ceil

object SevenStarsVfx {
    private val ARC_FRINGE = Color(0x6F, 0xB4, 0xFF)

    private val ARC_CORE = Color(0xF0, 0xF8, 0xFF)

    private val PATH_NEBULA = Color(0x47, 0x8F, 0xEB, 60)

    private val DISSIPATE_NEBULA = Color(0x47, 0x8F, 0xEB, 45)

    private const val PATH_NEBULA_SPACING = 90f

    private const val PATH_NEBULA_MIN = 3
    private const val PATH_NEBULA_MAX = 5

    private const val DISSIPATE_NEBULA_COUNT = 3

    private const val ARC_THICKNESS = 4f

    fun teleport(
        engine: CombatEngineAPI,
        from: Vector2f,
        to: Vector2f,
        fromEntity: ShipAPI?,
        toEntity: ShipAPI?,
    ) {
        EmpArcEntityAPI.EmpArcParams().apply {
            segmentLengthMult = 5f
            zigZagReductionFactor = 0.12f
            fadeOutDist = 72f
            minFadeOutMult = 12f
            flickerRateMult = 0.30f
        }
        engine.spawnEmpArcVisual(from, fromEntity, to, toEntity, ARC_THICKNESS, ARC_FRINGE, ARC_CORE)
        val distance = MathUtils.getDistance(from, to)
        if (distance >= PATH_NEBULA_SPACING * 0.6f) {
            val count = ceil(distance / PATH_NEBULA_SPACING).toInt().coerceIn(PATH_NEBULA_MIN, PATH_NEBULA_MAX)
            for (i in 1 until count) {
                val u = i.toFloat() / count
                val loc = Vector2f(from.x + (to.x - from.x) * u, from.y + (to.y - from.y) * u)
                val size = 10f + Misc.random.nextFloat() * 14f
                engine.addNebulaParticle(loc, ZERO_VEL, size, 1.5f, 0.05f, 0.1f, 0.35f, PATH_NEBULA)
            }
            engine.addSmoothParticle(to, ZERO_VEL, 26f, 1.6f, 0.14f, ARC_CORE)
        }
    }

    fun riftWindup(engine: CombatEngineAPI, at: Vector2f, scale: Float) {
        RiftExplosionVfx.spawnWindup(engine, at, RiftExplosionVfx.DEFAULT_RADIUS * scale)
        Global.getSoundPlayer().playSound(RiftExplosionVfx.SOUND_WINDUP, 1f, 0.9f, at, ZERO_VEL)
    }

    fun riftBlast(engine: CombatEngineAPI, at: Vector2f, scale: Float) {
        RiftExplosionVfx.riftExplosion(engine, at, radius = RiftExplosionVfx.DEFAULT_RADIUS * scale)
        Global.getSoundPlayer().playSound(RiftExplosionVfx.SOUND_EXPLOSION, 1f, 1f, at, ZERO_VEL)
    }

    fun dissipate(engine: CombatEngineAPI, at: Vector2f) {
        for (i in 0 until DISSIPATE_NEBULA_COUNT) {
            val angle = Misc.random.nextFloat() * 360f
            val dist = Misc.random.nextFloat() * 18f
            val loc = MathUtils.getPointOnCircumference(at, dist, angle)
            val size = 8f + Misc.random.nextFloat() * 10f
            engine.addNebulaParticle(loc, ZERO_VEL, size, 1.4f, 0.05f, 0.1f, 0.4f, DISSIPATE_NEBULA)
        }
    }

    fun terminalSegmentArc(engine: CombatEngineAPI, from: Vector2f, ship: ShipAPI) {
        val candidates = ArrayList<Vector2f>()
        ship.allWeapons?.forEach { candidates += Vector2f(it.location) }
        ship.engineController?.shipEngines
            ?.filter { !it.isPermanentlyDisabled }
            ?.forEach { candidates += Vector2f(it.location) }
        val to = if (candidates.isEmpty()) {
            Vector2f(ship.location)
        } else {
            candidates[Misc.random.nextInt(candidates.size)]
        }
        engine.spawnEmpArcVisual(from, null, to, ship, ARC_THICKNESS, ARC_FRINGE, ARC_CORE)
    }

    private val ZERO_VEL = Vector2f(0f, 0f)
}
