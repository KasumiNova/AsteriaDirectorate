package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.units.standard.entity.DistortionEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val POOL_KEY = "astd_starfall_wing_distortion_pool"
private const val DRIVER_KEY = "astd_starfall_wing_distortion_driver"
private const val CAP_LOG_KEY = "astd_starfall_wing_distortion_cap_logged"

private const val FADE_IN_SECONDS = 0.02f
private const val FULL_SECONDS = 0.04f
private const val FADE_OUT_SECONDS = 0.18f
private const val LIFETIME_SECONDS = FADE_IN_SECONDS + FULL_SECONDS + FADE_OUT_SECONDS

private const val IDLE_FULL_SECONDS = 1e7f

private const val SIZE_IN = 10f
private const val SIZE_FULL_MULT = 0.65f
private const val SIZE_OUT_MULT = 1f
private const val INNER_IN = 0.2f
private const val INNER_FULL = 0.4f
private const val INNER_OUT = 0.7f
private const val RING_HARDNESS = 0.5f
private const val INNER_HARDNESS = 0.6f
private const val POWER_IN = 0.15f
private const val POWER_FULL = 0.1f
private const val POWER_OUT = 0f

private const val MAX_ENTITIES = 512

private class DistortionLease(
    val entity: DistortionEntity,
    val sizeFull: Float,
    val sizeOut: Float,
) {
    var age = 0f
}

private class DistortionPool {

    private val idle = ArrayDeque<DistortionEntity>()
    private val active = ArrayList<DistortionLease>()
    private var created = 0

    fun checkout(engine: CombatEngineAPI, point: Vector2f, visualRadius: Float): Boolean {
        while (true) {
            val reused = idle.removeLastOrNull() ?: break
            if (reused.hasDelete()) {
                created--
                continue
            }
            lease(reused, point, visualRadius)
            return true
        }
        if (created >= MAX_ENTITIES) {
            if (engine.customData[CAP_LOG_KEY] != true) {
                engine.customData[CAP_LOG_KEY] = true
                AstdLog.logger.warn("星梭命中扭曲达到池化上限 $MAX_ENTITIES，后续命中不再新建扭曲实体")
            }
            return false
        }
        val createdEntity = DistortionEntity()
        if (BoxUtilCombatVfx.addEntity(engine, createdEntity) != 0) {
            createdEntity.delete()
            AstdLog.logger.warn("星梭命中扭曲注册失败（addEntity 返回非零）：本次命中缺少扭曲效果")
            return false
        }
        created++
        lease(createdEntity, point, visualRadius)
        return true
    }

    fun advance(amount: Float) {
        if (active.isEmpty()) return
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val lease = iterator.next()
            val entity = lease.entity
            if (entity.hasDelete()) {
                iterator.remove()
                created--
                continue
            }
            lease.age += amount
            if (lease.age >= LIFETIME_SECONDS) {
                hide(entity)
                iterator.remove()
                idle.addLast(entity)
                continue
            }
            applyEnvelope(lease)
        }
    }

    private fun lease(entity: DistortionEntity, point: Vector2f, visualRadius: Float) {
        val sizeFull = SIZE_FULL_MULT * visualRadius
        val sizeOut = SIZE_OUT_MULT * visualRadius
        entity.reset()
        entity.setLocation(Vector2f(point))
        entity.setGlobalTimer(0f, IDLE_FULL_SECONDS, 0f)
        entity.setSizeIn(SIZE_IN, SIZE_IN)
        entity.setSizeFull(sizeFull, sizeFull)
        entity.setSizeOut(sizeOut, sizeOut)
        entity.setInnerIn(INNER_IN, INNER_IN)
        entity.setInnerFull(INNER_FULL, INNER_FULL)
        entity.setInnerOut(INNER_OUT, INNER_OUT)
        entity.innerHardness = INNER_HARDNESS
        entity.ringHardness = RING_HARDNESS
        entity.powerIn = POWER_IN
        entity.powerOut = POWER_OUT
        val lease = DistortionLease(entity, sizeFull, sizeOut)
        applyEnvelope(lease)
        active += lease
    }

    private fun applyEnvelope(lease: DistortionLease) {
        val entity = lease.entity
        val sizeFull = lease.sizeFull
        val age = lease.age
        when {
            age < FADE_IN_SECONDS -> {
                val progress = age / FADE_IN_SECONDS
                val mix = progress
                entity.setSizeFull(lerp(SIZE_IN, sizeFull, mix), lerp(SIZE_IN, sizeFull, mix))
                entity.setInnerFull(lerp(INNER_IN, INNER_FULL, mix), lerp(INNER_IN, INNER_FULL, mix))
                entity.powerFull = lerp(POWER_IN, POWER_FULL, mix)
            }
            age < FADE_IN_SECONDS + FULL_SECONDS -> {
                entity.setSizeFull(sizeFull, sizeFull)
                entity.setInnerFull(INNER_FULL, INNER_FULL)
                entity.powerFull = POWER_FULL
            }
            else -> {
                val progress = ((age - FADE_IN_SECONDS - FULL_SECONDS) / FADE_OUT_SECONDS).coerceIn(0f, 1f)
                val mix = 1f - progress
                entity.setSizeFull(lerp(lease.sizeOut, sizeFull, mix), lerp(lease.sizeOut, sizeFull, mix))
                entity.setInnerFull(lerp(INNER_OUT, INNER_FULL, mix), lerp(INNER_OUT, INNER_FULL, mix))
                entity.powerFull = lerp(POWER_OUT, POWER_FULL, mix)
            }
        }
    }

    // 强度归零后着色器把顶点移出屏幕，实体保持长驻计时器等待复用。
    private fun hide(entity: DistortionEntity) {
        entity.powerIn = 0f
        entity.powerFull = 0f
        entity.powerOut = 0f
        entity.setGlobalTimer(0f, IDLE_FULL_SECONDS, 0f)
    }

    private fun lerp(from: Float, to: Float, mix: Float): Float = from + (to - from) * mix
}

private class DistortionPoolDriver(
    private val combatEngine: CombatEngineAPI,
    private val pool: DistortionPool,
) : BaseEveryFrameCombatPlugin() {
    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (combatEngine.isPaused) return
        pool.advance(amount)
    }
}

object StarfallWingVfx {

    private val shardPool = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shard_tri.png",
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    )

    fun color(type: StarfallWingTuning.ShuttleType): Color = when (type) {
        StarfallWingTuning.ShuttleType.BLUE -> Color(90, 175, 255)
        StarfallWingTuning.ShuttleType.RED -> Color(255, 80, 75)
        StarfallWingTuning.ShuttleType.GOLD -> Color(255, 205, 70)
        StarfallWingTuning.ShuttleType.PURPLE -> Color(195, 100, 255)
    }

    private val shockPool = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shockwave_ring.png",
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    )

    fun explosion(engine: CombatEngineAPI, point: Vector2f, type: StarfallWingTuning.ShuttleType) {
        BoxUtilCombatVfx.ensureReady(engine)
        val color = color(type)
        val visualRadius = type.radius
        val visualDiameter = visualRadius * 2f
        val growthTime = 0.35f
        PooledCombatVfx.spawnSprite(
            engine, shockPool, point.x, point.y,
            scaleX = visualRadius * 0.1f, scaleY = visualRadius * 0.1f,
            scaleRateX = visualRadius * 0.9f / growthTime, scaleRateY = visualRadius * 0.9f / growthTime,
            color = Color(color.red, color.green, color.blue, 170),
            emissiveColor = Color(color.red, color.green, color.blue, 60),
            fadeIn = 0.02f, full = 0.06f, fadeOut = growthTime - 0.08f,
        )
        repeat(Random.nextInt(3, 7)) {
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val distance = Random.nextFloat() * visualRadius * 0.08f
            val location = Vector2f(point.x + cos(angle) * distance, point.y + sin(angle) * distance)
            val velocity = Vector2f(cos(angle) * visualRadius * 0.05f, sin(angle) * visualRadius * 0.05f)
            BoxUtilCombatVfx.addNebulaParticle(
                engine, location, velocity, visualDiameter * (0.65f + Random.nextFloat() * 0.2f) / 1.6f,
                1.6f, 0.1f, 0.25f, 0.45f + Random.nextFloat() * 0.25f,
                Color(color.red, color.green, color.blue, 100),
            )
        }
        repeat(9) {
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val speed = visualRadius * (0.4f + Random.nextFloat() * 0.6f) / 0.35f
            val size = visualRadius * (0.015f + Random.nextFloat() * 0.02f)
            PooledCombatVfx.spawnSprite(
                engine, shardPool, point.x, point.y,
                velX = cos(angle) * speed, velY = sin(angle) * speed,
                facingDeg = Random.nextFloat() * 360f,
                turnRateDeg = Random.nextFloat() * 360f - 180f,
                scaleX = size, scaleY = size * 0.65f,
                color = Color(color.red, color.green, color.blue, 190),
                emissiveColor = Color(color.red, color.green, color.blue, 70),
                fadeIn = 0.02f, full = 0.08f, fadeOut = 0.25f,
            )
        }
        spawnDistortion(engine, point, visualRadius)
    }

    private fun spawnDistortion(engine: CombatEngineAPI, point: Vector2f, visualRadius: Float) {
        val pool = poolOf(engine)
        if (!pool.checkout(engine, point, visualRadius)) return
        ensureDriver(engine, pool)
    }

    private fun poolOf(engine: CombatEngineAPI): DistortionPool =
        engine.customData[POOL_KEY] as? DistortionPool
            ?: DistortionPool().also { engine.customData[POOL_KEY] = it }

    private fun ensureDriver(engine: CombatEngineAPI, pool: DistortionPool) {
        if (engine.customData[DRIVER_KEY] == true) return
        engine.customData[DRIVER_KEY] = true
        engine.addPlugin(DistortionPoolDriver(engine, pool))
    }
}
