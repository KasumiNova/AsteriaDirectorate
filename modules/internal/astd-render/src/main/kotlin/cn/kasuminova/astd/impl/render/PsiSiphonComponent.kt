package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PSI-Ω 命中端的「灵魂虹吸」回流特效（迁移计划 §3.4 impact 节点，Psi 专属）：
 * 从命中点沿束回流至炮口的紫色发光粒子（带小拖尾、随机转向）+ 周期性烟雾涟漪环。
 *
 * 无常驻 BoxUtil 句柄——纯每帧向引擎抛射短寿命粒子（`addSmoothParticle`/`addNebulaParticle`），
 * 故仅在 firing（[RenderContext.frame].active）时抛射；停火即停，已抛出的引擎粒子自行消散。
 * 数学从旧 `PsiOmegaBeamVfx.updateHitFx`/`spawnSmokeRipples` 原样移植，几何源改读 [RenderContext.frame]。
 */
class PsiSiphonComponent(
    id: String,
    layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    renderOrder: Int = RENDER_ORDER,
) : RenderEntityImpl(id, layer, renderOrder) {

    private data class ReturnParticle(
        val pos: Vector2f,
        val vel: Vector2f,
        val prev: Vector2f,
        var life: Float,
        val lifeMax: Float,
        var steerT: Float,
        var steerInterval: Float,
        var steerRad: Float,
        val swirlSign: Float,
        var trailT: Float,
    )

    private val returnParticles = ArrayList<ReturnParticle>()
    private var returnSpawnAcc = 0f
    private var smokeT = 0f
    private var smokeIntervalCur = 1.6f
    private val v0 = Vector2f(0f, 0f)

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        if (!frame.active) return
        updateHitFx(engine, amount, frame.origin, frame.facing, frame.length.coerceAtLeast(16f), frame.intensity.coerceIn(0f, 1f))
    }

    override fun onDetachSelf() {
        returnParticles.clear()
    }

    private fun updateHitFx(engine: CombatEngineAPI, amount: Float, start: Vector2f, facing: Float, length: Float, ramp: Float) {
        val fx = ramp.coerceIn(0f, 1f)

        val dx = BeamMath.facingUnitX(facing)
        val dy = BeamMath.facingUnitY(facing)
        val hitX = start.x + dx * length
        val hitY = start.y + dy * length
        val hit = Vector2f(hitX, hitY)

        val spawnRate = BeamMath.lerp(HIT_RETURN_SPAWN_PER_SEC_MIN, HIT_RETURN_SPAWN_PER_SEC_MAX, fx)
        returnSpawnAcc += amount * spawnRate
        val spawnN = returnSpawnAcc.toInt().coerceAtMost(6)
        if (spawnN > 0) returnSpawnAcc -= spawnN.toFloat()

        repeat(spawnN) {
            if (returnParticles.size >= HIT_RETURN_MAX) return@repeat
            val sign = if (BeamMath.rand01() < 0.5f) -1f else 1f
            val perpX = -dy
            val perpY = dx
            val jitter = BeamMath.randSigned01() * 9f
            val p = Vector2f(hitX + perpX * jitter, hitY + perpY * jitter)

            val avgSpeed = HIT_RETURN_SPEED * 0.85f
            val travelNeed = (length / avgSpeed) * HIT_RETURN_LIFE_MUL + HIT_RETURN_LIFE_EXTRA
            val lifeMax = (travelNeed * (1.0f + 0.20f * BeamMath.rand01())).coerceAtMost(HIT_RETURN_LIFE_CAP)
            val interval = BeamMath.lerp(HIT_RETURN_TURN_INTERVAL_MIN, HIT_RETURN_TURN_INTERVAL_MAX, BeamMath.rand01())
            val steerRad = Math.toRadians((BeamMath.randSigned01() * HIT_RETURN_TURN_DEG).toDouble()).toFloat()

            val vx = (-dx + perpX * (HIT_RETURN_SWIRL * sign))
            val vy = (-dy + perpY * (HIT_RETURN_SWIRL * sign))
            val nd = BeamMath.normalize(vx, vy)
            val v = Vector2f(nd.first * HIT_RETURN_SPEED, nd.second * HIT_RETURN_SPEED)

            returnParticles.add(ReturnParticle(pos = p, vel = v, prev = Vector2f(p.x, p.y), life = 0f, lifeMax = lifeMax, steerT = 0f, steerInterval = interval, steerRad = steerRad, swirlSign = sign, trailT = 0f))
        }

        val headColor = Color(210, 130, 255, 255)
        val trailColor = Color(165, 75, 235, 175)
        val trailColor2 = Color(120, 40, 190, 120)

        var i = 0
        while (i < returnParticles.size) {
            val p = returnParticles[i]
            p.life += amount

            val toSX = start.x - p.pos.x
            val toSY = start.y - p.pos.y
            val dist2 = toSX * toSX + toSY * toSY
            if (p.life >= p.lifeMax || dist2 <= 20f * 20f) {
                returnParticles.removeAt(i)
                continue
            }

            p.prev.set(p.pos)

            p.steerT += amount
            if (p.steerT >= p.steerInterval) {
                p.steerT = 0f
                p.steerInterval = BeamMath.lerp(HIT_RETURN_TURN_INTERVAL_MIN, HIT_RETURN_TURN_INTERVAL_MAX, BeamMath.rand01())
                p.steerRad = Math.toRadians((BeamMath.randSigned01() * HIT_RETURN_TURN_DEG).toDouble()).toFloat()
            }

            val axX = -dx
            val axY = -dy
            val proj = (p.pos.x - start.x) * dx + (p.pos.y - start.y) * dy
            val cx = start.x + dx * proj
            val cy = start.y + dy * proj
            val offX = p.pos.x - cx
            val offY = p.pos.y - cy
            val offLen = sqrt((offX * offX + offY * offY).coerceAtLeast(0.000001f))
            val invOff = 1f / offLen
            val radInX = -offX * invOff
            val radInY = -offY * invOff
            val tanX = -offY * invOff * p.swirlSign
            val tanY = offX * invOff * p.swirlSign

            val swirlK = HIT_RETURN_SWIRL * (0.65f + 0.35f * (offLen / 60f).coerceIn(0f, 1f))
            val pullK = 0.85f * (offLen / 45f).coerceIn(0f, 1f)

            var desX = axX + tanX * swirlK + radInX * pullK
            var desY = axY + tanY * swirlK + radInY * pullK
            val dn = BeamMath.normalize(desX, desY)
            desX = dn.first
            desY = dn.second
            val rr = BeamMath.rotate2D(desX, desY, p.steerRad)
            desX = rr.first
            desY = rr.second

            val vLen = sqrt((p.vel.x * p.vel.x + p.vel.y * p.vel.y).coerceAtLeast(0.000001f))
            var curX = p.vel.x / vLen
            var curY = p.vel.y / vLen
            val turnK = (amount * 6.5f).coerceIn(0f, 1f)
            curX += (desX - curX) * turnK
            curY += (desY - curY) * turnK
            val cn = BeamMath.normalize(curX, curY)
            curX = cn.first
            curY = cn.second

            val sp = HIT_RETURN_SPEED * (0.86f + 0.22f * sin(p.life * 11.7f + p.swirlSign))
            p.vel.set(curX * sp, curY * sp)
            p.pos.x += p.vel.x * amount
            p.pos.y += p.vel.y * amount

            val fade = (1f - (p.life / p.lifeMax)).coerceIn(0f, 1f)
            val headSize = 6f + 3f * fade
            engine.addSmoothParticle(p.pos, v0, headSize, 1.35f, 0.18f, Color(headColor.red, headColor.green, headColor.blue, (headColor.alpha * fade).toInt().coerceIn(0, 255)))
            engine.addNebulaParticle(p.pos, v0, 9f, 1.35f, 0.05f, 0.08f, 0.20f, Color(170, 80, 240, (90f * fade).toInt().coerceIn(0, 255)))

            p.trailT += amount
            if (p.trailT >= 0.030f) {
                p.trailT -= 0.030f
                val tx = p.prev.x
                val ty = p.prev.y
                engine.addSmoothParticle(Vector2f(tx, ty), v0, 6f, 0.65f, 0.42f, Color(trailColor.red, trailColor.green, trailColor.blue, (trailColor.alpha * fade).toInt().coerceIn(0, 255)))
                engine.addSmoothParticle(Vector2f(tx, ty), v0, 10f, 0.42f, 0.55f, Color(trailColor2.red, trailColor2.green, trailColor2.blue, (trailColor2.alpha * fade).toInt().coerceIn(0, 255)))
            }
            i++
        }

        smokeT += amount
        if (smokeT >= smokeIntervalCur) {
            smokeT = 0f
            spawnSmokeRipples(engine, hit, fx)
            val base = BeamMath.lerp(HIT_SMOKE_INTERVAL_SLOW, HIT_SMOKE_INTERVAL_FAST, fx)
            val jitter = BeamMath.randSigned01() * 0.25f
            smokeIntervalCur = (base + jitter).coerceIn(HIT_SMOKE_INTERVAL_FAST, HIT_SMOKE_INTERVAL_SLOW)
        }
    }

    private fun spawnSmokeRipples(engine: CombatEngineAPI, hit: Vector2f, fx: Float) {
        val baseSpeed = BeamMath.lerp(HIT_SMOKE_SPEED_MIN, HIT_SMOKE_SPEED_MAX, fx)
        val sizeBase = BeamMath.lerp(HIT_SMOKE_SIZE_MIN, HIT_SMOKE_SIZE_MAX, fx)
        val c0 = Color(120, 45, 170, 85)
        val c1 = Color(90, 30, 130, 60)

        for (ring in 0 until HIT_SMOKE_RINGS) {
            val ringR = 12f + ring * 18f + BeamMath.rand01() * 8f
            for (idx in 0 until HIT_SMOKE_PUFFS_PER_RING) {
                val a = (idx.toFloat() / HIT_SMOKE_PUFFS_PER_RING.toFloat()) * (2f * PI.toFloat()) + BeamMath.randSigned01() * 0.12f
                val ux = cos(a)
                val uy = sin(a)
                val loc = Vector2f(hit.x + ux * ringR, hit.y + uy * ringR)
                val sp = baseSpeed * (0.70f + 0.55f * BeamMath.rand01())
                val vel = Vector2f(ux * sp, uy * sp)
                val size = sizeBase * (0.75f + 0.55f * BeamMath.rand01())
                val col = if (BeamMath.rand01() < 0.55f) c0 else c1
                engine.addNebulaParticle(loc, vel, size, HIT_SMOKE_END_SIZE_MUL, HIT_SMOKE_RAMP_UP, HIT_SMOKE_FULL, HIT_SMOKE_FADE, col)
            }
        }
    }

    companion object {
        /** 命中回流在树内的次级绘制序（其粒子实际由引擎独立绘制，此值仅定树内推进/绘制次序）。 */
        const val RENDER_ORDER = 300

        private const val HIT_RETURN_SPAWN_PER_SEC_MIN = 2.0f
        private const val HIT_RETURN_SPAWN_PER_SEC_MAX = 4.0f
        private const val HIT_RETURN_MAX = 96
        private const val HIT_RETURN_SPEED = 430f
        private const val HIT_RETURN_SWIRL = 0.52f
        private const val HIT_RETURN_TURN_DEG = 30f
        private const val HIT_RETURN_TURN_INTERVAL_MIN = 0.08f
        private const val HIT_RETURN_TURN_INTERVAL_MAX = 0.18f
        private const val HIT_RETURN_LIFE_EXTRA = 0.35f
        private const val HIT_RETURN_LIFE_MUL = 1.35f
        private const val HIT_RETURN_LIFE_CAP = 30f

        private const val HIT_SMOKE_INTERVAL_SLOW = 2.0f
        private const val HIT_SMOKE_INTERVAL_FAST = 1.0f
        private const val HIT_SMOKE_PUFFS_PER_RING = 22
        private const val HIT_SMOKE_RINGS = 3
        private const val HIT_SMOKE_SPEED_MIN = 18f
        private const val HIT_SMOKE_SPEED_MAX = 65f
        private const val HIT_SMOKE_SIZE_MIN = 22f
        private const val HIT_SMOKE_SIZE_MAX = 42f
        private const val HIT_SMOKE_END_SIZE_MUL = 2.15f
        private const val HIT_SMOKE_RAMP_UP = 0.25f
        private const val HIT_SMOKE_FULL = 1.10f
        private const val HIT_SMOKE_FADE = 1.15f
    }
}
