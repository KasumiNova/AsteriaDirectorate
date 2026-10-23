package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.impl.combat.CombatRandom.seedOf
import com.fs.starfarer.api.Global
import java.util.Random

/**
 * 战斗结算的确定性随机。禁止改用 `Misc.getRandom`：`Misc` 静态初始化依赖 `Global.getSettings()`，
 * 无头单元测试会在类初始化阶段失败。
 */
object CombatRandom {
    private val log = Global.getLogger(CombatRandom::class.java)

    /** `Misc.getRandom(0, ...)` 会走全局随机而非确定性序列，seed 为 0 时改用固定替代种子保持确定性。 */
    private const val ZERO_SEED_SUBSTITUTE = 0xA57D_5EEDL

    fun seedOf(shipId: String, slotId: String): Long = shipId.hashCode().toLong() * 31L + slotId.hashCode().toLong()

    fun nextFloatIn(seed: Long, callIndex: Int, range: ClosedFloatingPointRange<Float>): Float {
        var effectiveCallIndex = callIndex
        if (effectiveCallIndex < 0) {
            log.warn("CombatRandom callIndex 为负（$callIndex），属调用方状态位损坏，clamp 到 0: seed=$seed")
            effectiveCallIndex = 0
        }

        var start = range.start
        var end = range.endInclusive
        if (end < start) {
            log.warn("CombatRandom range 起止倒置（[$start, $end]），属调用方错误，交换后取值: seed=$seed")
            val tmp = start; start = end; end = tmp
        }
        if (start == end) return start

        val t = deriveRandom(if (seed == 0L) ZERO_SEED_SUBSTITUTE else seed, effectiveCallIndex).nextFloat()
        return start + (end - start) * t
    }

    internal fun deriveRandom(seed: Long, callIndex: Int): Random {
        val r = Random(seed)
        repeat(callIndex) { r.nextLong() }
        return Random(r.nextLong())
    }
}
