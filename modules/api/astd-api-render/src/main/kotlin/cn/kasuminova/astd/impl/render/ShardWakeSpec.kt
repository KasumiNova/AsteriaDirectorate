package cn.kasuminova.astd.impl.render

/**
 * 弹体活动期间周期性发射的三角碎片参数。弹体停止活动后停止生成，已生成的碎片继续播放至寿命结束。
 */
data class ShardWakeSpec(
    /** 发射节拍（秒）：每满一拍喷 [perTick] 颗。 */
    val interval: Float,
    /** 每节拍颗数。 */
    val perTick: Int,
    /** 发射点散布半径（世界单位，绕弹体位置圆内随机）。 */
    val scatterRadius: Float,
    /** 碎片初速域（世界单位/秒，方向 = 弹体 facing ± [spreadDeg]）。 */
    val speedMin: Float,
    val speedMax: Float,
    /** 飞行方向随机张角（±度）。 */
    val spreadDeg: Float,
    /** 尺寸基准长度（世界单位）：碎片边长 = clamp(本值×sizeMul, sizeMin, sizeMax) × 抖动。 */
    val shardLength: Float,
    /** 提亮色（占比由实现侧 TriShardSpec.coreRatio 定）与底色。 */
    val coreColor: ASTDColor,
    val fringeColor: ASTDColor,
    /** 边长派生（乘 [shardLength] 后钳位）。 */
    val sizeMul: Float = 0.2f,
    val sizeMin: Float = 4f,
    val sizeMax: Float = 9f,
    /** 自旋角速度幅度区间（度/秒，方向 ± 随机）。 */
    val spinMin: Float = 90f,
    val spinMax: Float = 360f,
    /** 碎片 alpha 随机域（0..255）。 */
    val alphaLo: Int = 120,
    val alphaHi: Int = 180,
    /** 满亮相时长随机域与淡出时长（秒；淡入沿用实现侧默认 0.02）。 */
    val timerFullLo: Float = 0.15f,
    val timerFullHi: Float = 0.3f,
    val timerFadeOut: Float = 0.3f,
)
