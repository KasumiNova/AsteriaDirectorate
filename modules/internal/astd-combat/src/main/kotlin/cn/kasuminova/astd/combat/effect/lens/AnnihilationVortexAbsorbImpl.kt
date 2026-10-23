package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.combat.AbsorbOutcome
import cn.kasuminova.astd.api.combat.AbsorbedShot
import cn.kasuminova.astd.api.combat.AnnihilationVortexAbsorb
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

class AnnihilationVortexAbsorbImpl(
    private val coarseQuery: (CombatEngineAPI, Vector2f, Float) -> List<CombatEntityAPI> = { _, center, radius ->
        CombatUtils.getEntitiesWithinRange(center, radius)
    },
) : AnnihilationVortexAbsorb {

    private var warnedBadRadius = false

    private var warnedCollision = false

    override fun advance(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        absorbRadius: Float,
        sourceOwner: Int,
        amount: Float,
        onAbsorbedFx: (Vector2f) -> Unit,
    ): AbsorbOutcome {
        var r = radius
        if (r <= 0f) {
            if (!warnedBadRadius) {
                warnedBadRadius = true
                log.warn("[ASTD] 湮灭涡旋半径输入非正（$radius），clamp 到 ${AnnihilationVortexDifficulty.ABSORB_RADIUS_MIN}（配置错误）")
            }
            r = AnnihilationVortexDifficulty.ABSORB_RADIUS_MIN
        }

        val absorbed = ArrayList<AbsorbedShot>(4)
        var pulled = 0
        for (e in coarseQuery(engine, center, r)) {
            if (e == null) continue
            if (e !is DamagingProjectileAPI) continue
            val owner = try {
                e.owner
            } catch (t: Throwable) {
                log.warn("[ASTD] 湮灭涡旋读取弹体归属失败，跳过该弹体: ${t.javaClass.simpleName}: ${t.message}")
                continue
            }
            if (owner == sourceOwner) continue

            val loc = e.location ?: continue
            val dist = MathUtils.getDistance(center, loc)
            val collision = try {
                e.collisionRadius
            } catch (t: Throwable) {
                if (!warnedCollision) {
                    warnedCollision = true
                    log.warn("[ASTD] 湮灭涡旋读取弹体碰撞半径失败，按 0 计（吸收判定退回圆心距）: ${t.javaClass.simpleName}: ${t.message}")
                }
                0f
            }.coerceAtLeast(0f)
            val reach = dist - collision

            if (reach <= absorbRadius) {
                engine.removeEntity(e)
                onAbsorbedFx(Vector2f(loc))
                absorbed += AbsorbedShot(e.damageType, e.baseDamageAmount, Vector2f(loc))
                continue
            }

            if (dist > 1f) {
                val k = pullAccel(dist, r) * amount / dist
                if (k > 0f) {
                    // velocity 为 live reference，直接改写即生效。
                    val v = e.velocity
                    if (v != null) {
                        v.x += (center.x - loc.x) * k
                        v.y += (center.y - loc.y) * k
                        pulled++
                    }
                }
            }
        }
        return AbsorbOutcome(absorbed, pulled)
    }

    companion object {
        private val log = Global.getLogger(AnnihilationVortexAbsorbImpl::class.java)

        fun pullAccel(dist: Float, radius: Float): Float =
            AnnihilationVortexDifficulty.PULL_ACCEL_MAX * (1f - dist / radius).coerceIn(0f, 1f)
    }
}
