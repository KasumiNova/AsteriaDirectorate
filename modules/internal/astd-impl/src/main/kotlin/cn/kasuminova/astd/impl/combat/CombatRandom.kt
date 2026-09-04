package cn.kasuminova.astd.impl.combat

import com.fs.starfarer.api.Global
import java.util.Random

/**
 * 战斗确定性随机工具（规格 00-共享基建 §4.1）。
 *
 * 动机：结算随机（装药倍率、EMP 触发概率）必须在「同一帧多次调用不重掷、同事件重放结果一致」。
 * 算法逐位对齐 `Misc.getRandom(seed, numCalls)`（jar 反编译已核实：
 * `new Random(seed)` 丢弃 numCalls 个 nextLong，再以 `new Random(r.nextLong())` 出序列）；
 * 因 `Misc` 类静态初始化依赖游戏内 `Global.getSettings()`（Misc.java:196，裸单测环境 NPE、
 * 类初始化直接失败），无法在「无需启动游戏」的单元测试中触达，故在此处复现该纯函数算法——
 * 游戏内产出序列与直接调用 `Misc.getRandom` 完全一致。
 * 一次性纯视觉随机（粒子散布等不影响结算的）不在此列，直接用 `Misc.random` / `MathUtils` 惯例。
 *
 * 使用口径：每武器实例一个确定性序列，seed 由 [seedOf] 派生（战斗内稳定），
 * 调用序 callIndex 用 BuffHost 的 Weapon 级状态位记录并自增；禁止同帧对同一事件二次取值。
 */
object CombatRandom {
    private val log = Global.getLogger(CombatRandom::class.java)

    /**
     * seed 归一化替代值：`Misc.getRandom(0, *)` 会返回全局共享随机（非确定性，jar 反编译已核实），
     * 故 seed 恰为 0 时改用固定非零常量，保持确定性语义不破。
     */
    private const val ZERO_SEED_SUBSTITUTE = 0xA57D_5EEDL

    /**
     * 派生武器实例级确定性种子：`shipId.hashCode() * 31 + slotId.hashCode()`（Long 算术避免 Int 溢出碰撞），
     * 同一艘船同一槽位在战斗内恒定；同船两件同型武器因 slotId 不同而天然隔离。
     */
    fun seedOf(shipId: String, slotId: String): Long = shipId.hashCode().toLong() * 31L + slotId.hashCode().toLong()

    /**
     * 取 [range] 内的确定性随机浮点：同 (seed, callIndex) 恒同值，不同 callIndex 不同值。
     *
     * 0 值/异常防线（记 WARN 不静默）：
     * - [callIndex] < 0 属调用方状态位损坏，clamp 到 0；
     * - [range] 起止倒置属调用方错误，交换后取值；
     * - [range] 退化为单点（start == endInclusive）时直接返回该点，不消耗随机语义。
     */
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

    /**
     * `Misc.getRandom(seed, numCalls)` 的逐位复现（纯函数，语义见类注释）。
     */
    internal fun deriveRandom(seed: Long, callIndex: Int): Random {
        val r = Random(seed)
        repeat(callIndex) { r.nextLong() }
        return Random(r.nextLong())
    }
}
