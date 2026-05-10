package cn.kasuminova.astd.renderer.effect.projectile.beam

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 小工具：把“beam/weapon 的真实朝向与端点”统一成一条线段。
 *
 * 设计目标：
 * - VFX 一律跟随【实际】朝向（优先 BeamAPI 的 from/to，其次 WeaponAPI.currAngle）。
 * - 避免用“请求角度/预期角度”去画效果，导致表现与真实光束不一致。
 */
internal object BeamLineUtil {

    data class BeamLine(
        val from: Vector2f,
        val to: Vector2f,
        val facing: Float,
        val length: Float,
        val dirUnit: Vector2f,
        val perpUnit: Vector2f,
    )

    /**
     * 直接由两点生成一条线段（适用于 ship system / combat plugin 等“没有 Weapon/Beam 对象”的场景）。
     */
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

    /**
     * 优先用 [beam] 的 from/to。
     *
     * 若 beam 为空，可用 weapon.currAngle + [fallbackRange] 生成一条“估算线”，
     * 用于充能期/beam 尚未建立时的纯视觉效果。
     */
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
