package cn.kasuminova.astd.combat.effect.lens.iceshard

import com.fs.starfarer.api.Global
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.random.Random

object IceShardMirvMath {
    private val log = Global.getLogger(IceShardMirvMath::class.java)

    fun allocateShardDamages(
        random: Random,
        count: Int = IceShardMirvDifficulty.SHARD_COUNT,
        min: Float = IceShardMirvDifficulty.SHARD_DAMAGE_MIN,
        max: Float = IceShardMirvDifficulty.SHARD_DAMAGE_MAX,
        total: Float = IceShardMirvDifficulty.TOTAL_DAMAGE,
    ): FloatArray {
        val out = FloatArray(count) { min + random.nextFloat() * (max - min) }
        repeat(100) {
            val diff = total - out.sum()
            if (abs(diff) < 0.01f) return out
            val adjustable = out.indices.filter { i ->
                if (diff > 0f) out[i] < max - 1e-4f else out[i] > min + 1e-4f
            }
            if (adjustable.isEmpty()) return@repeat
            val share = diff / adjustable.size
            for (i in adjustable) out[i] = (out[i] + share).coerceIn(min, max)
        }
        val residual = total - out.sum()
        if (abs(residual) >= 0.01f) {
            val idx = out.indices.firstOrNull { i ->
                val v = out[i] + residual
                v in min..max
            }
            if (idx != null) {
                out[idx] += residual
            } else {
                log.warn("冰晶伤害分配尾差修复失败（residual=$residual），本批合计 ${out.sum()} 偏离总额 $total")
            }
        }
        return out
    }

    fun shardSpeed(damage: Float): Float {
        val t = ((damage - IceShardMirvDifficulty.SHARD_DAMAGE_MIN) /
                (IceShardMirvDifficulty.SHARD_DAMAGE_MAX - IceShardMirvDifficulty.SHARD_DAMAGE_MIN))
            .coerceIn(0f, 1f)
        return IceShardMirvDifficulty.SHARD_BASE_SPEED * (0.75f + 0.5f * t)
    }

    fun tickDamage(shardDamage: Float, dotRatio: Float): Float =
        shardDamage * dotRatio / (IceShardMirvDifficulty.ATTACH_DURATION / IceShardMirvDifficulty.TICK_INTERVAL)

    fun canSplit(flightTime: Float, distanceToTarget: Float): Boolean =
        flightTime >= IceShardMirvDifficulty.SPLIT_IMMUNITY_SECONDS &&
                distanceToTarget <= IceShardMirvDifficulty.SPLIT_RANGE

    fun attachLocalOffset(shipLocation: Vector2f, shipFacing: Float, hitPoint: Vector2f): Vector2f =
        VectorUtils.rotate(Vector2f(hitPoint).apply {
            x -= shipLocation.x
            y -= shipLocation.y
        }, -shipFacing, Vector2f())

    fun attachWorldPoint(shipLocation: Vector2f, shipFacing: Float, localOffset: Vector2f): Vector2f {
        val rotated = VectorUtils.rotate(localOffset, shipFacing, Vector2f())
        return Vector2f(shipLocation.x + rotated.x, shipLocation.y + rotated.y)
    }
}
