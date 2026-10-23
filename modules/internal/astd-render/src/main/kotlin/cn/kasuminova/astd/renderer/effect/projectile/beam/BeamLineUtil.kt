package cn.kasuminova.astd.renderer.effect.projectile.beam

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object BeamLineUtil {

    data class BeamLine(
        val from: Vector2f,
        val to: Vector2f,
        val facing: Float,
        val length: Float,
        val dirUnit: Vector2f,
        val perpUnit: Vector2f,
    )

    fun fromPoints(from: Vector2f, to: Vector2f): BeamLine? {
        val f = Vector2f(from)
        val t = Vector2f(to)
        val facing = try {
            VectorUtils.getAngle(f, t)
        } catch (_: Throwable) {
            0f
        }
        return build(f, t, facing)
    }

    fun fromBeamOrWeapon(weapon: WeaponAPI, beam: BeamAPI?, fallbackRange: Float? = null): BeamLine? {
        val (from, to, facing) = if (beam != null) {
            val f = Vector2f(beam.from)
            val t = Vector2f(beam.to)
            val ang = try {
                VectorUtils.getAngle(f, t)
            } catch (_: Throwable) {
                weapon.currAngle
            }
            Triple(f, t, ang)
        } else {
            val r = fallbackRange ?: return null
            val f = try {
                weapon.getFirePoint(0)
            } catch (_: Throwable) {
                null
            }
                ?: try {
                    Vector2f(weapon.location)
                } catch (_: Throwable) {
                    null
                }
                ?: return null

            val ang = try {
                weapon.currAngle
            } catch (_: Throwable) {
                0f
            }
            val rad = Math.toRadians(ang.toDouble())
            val dir = Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
            val t = Vector2f(f.x + dir.x * r, f.y + dir.y * r)
            Triple(f, t, ang)
        }

        return build(from, to, facing)
    }

    private fun build(from: Vector2f, to: Vector2f, facing: Float): BeamLine? {
        val dx = (to.x - from.x)
        val dy = (to.y - from.y)
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 0.01f) return null

        val inv = 1f / len
        val dirUnit = Vector2f(dx * inv, dy * inv)
        val perpUnit = Vector2f(-dirUnit.y, dirUnit.x)

        return BeamLine(
            from = from,
            to = to,
            facing = facing,
            length = len,
            dirUnit = dirUnit,
            perpUnit = perpUnit,
        )
    }
}
