package cn.kasuminova.astd.combat.effect.lens.iceshard

import com.fs.starfarer.api.Global
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.random.Random

/**
 * 源生冰晶 MIRV 分裂的纯计算面（purple/30-superlative.md §机制）：15 枚冰晶的
 * 伤害分配（单枚 200~800、合计恒 6000）与伤害相关弹速推导，不触碰战斗引擎，供单测完整驱动。
 *
 * 0 值防线：
 * - [allocateShardDamages] 总额在参数区间内必然可行（count×min ≤ total ≤ count×max），
 *   迭代修复不收敛时记 WARN 并把残差压入仍有调整余量的元素（不静默产出错误总额）；
 * - [shardSpeed] 入参越界一律 clamp 到伤害区间再折算。
 */
object IceShardMirvMath {
    private val log = Global.getLogger(IceShardMirvMath::class.java)

    /**
     * 冰晶伤害分配：均匀随机出 [count] 枚 [min]~[max] 的初值，迭代均摊修复到合计恰为 [total]。
     * 均值 400 恰为区间中点，等份额修复在有限步内收敛（迭代上限 100 次兜底）。
     */
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
        // 尾差修复：迭代余量均摊后仍存的微小残差，压入第一个能完整吃下的元素
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

    /**
     * 伤害相关弹速：伤害归一进度 t∈[0,1] → 弹速 = 基准 ×（0.75 + 0.5t），
     * 即基准弹速的 ×0.75~×1.25 区间浮动。
     */
    fun shardSpeed(damage: Float): Float {
        val t = ((damage - IceShardMirvDifficulty.SHARD_DAMAGE_MIN) /
                (IceShardMirvDifficulty.SHARD_DAMAGE_MAX - IceShardMirvDifficulty.SHARD_DAMAGE_MIN))
            .coerceIn(0f, 1f)
        return IceShardMirvDifficulty.SHARD_BASE_SPEED * (0.75f + 0.5f * t)
    }

    /** 冻结伤害单周期量：子射弹伤害 × 难度缩放（附着期总能量伤害的比例锚点）÷ 周期数。 */
    fun tickDamage(shardDamage: Float, dotRatio: Float): Float =
        shardDamage * dotRatio / (IceShardMirvDifficulty.ATTACH_DURATION / IceShardMirvDifficulty.TICK_INTERVAL)

    /** 分裂门控：发射满 [IceShardMirvDifficulty.SPLIT_IMMUNITY_SECONDS] 豁免期且距目标进入分裂距离。 */
    fun canSplit(flightTime: Float, distanceToTarget: Float): Boolean =
        flightTime >= IceShardMirvDifficulty.SPLIT_IMMUNITY_SECONDS &&
                distanceToTarget <= IceShardMirvDifficulty.SPLIT_RANGE

    /**
     * 附着点的舰体局部偏移（附着时刻快照）：世界命中点 − 舰心，再按当前朝向反旋进舰体系。
     * 与 [attachWorldPoint] 互为正反换算（CollapseShiftSystemStats 同款范式）。
     */
    fun attachLocalOffset(shipLocation: Vector2f, shipFacing: Float, hitPoint: Vector2f): Vector2f =
        VectorUtils.rotate(Vector2f(hitPoint).apply {
            x -= shipLocation.x
            y -= shipLocation.y
        }, -shipFacing, Vector2f())

    /** 附着点世界坐标回算：局部偏移按舰船**当前朝向**正旋出舰体系后加舰心。 */
    fun attachWorldPoint(shipLocation: Vector2f, shipFacing: Float, localOffset: Vector2f): Vector2f {
        val rotated = VectorUtils.rotate(localOffset, shipFacing, Vector2f())
        return Vector2f(shipLocation.x + rotated.x, shipLocation.y + rotated.y)
    }
}
