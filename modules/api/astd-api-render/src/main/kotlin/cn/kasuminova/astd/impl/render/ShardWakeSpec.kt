package cn.kasuminova.astd.impl.render

/**
 * 三角碎片航迹发射器（持续型，首发：坠星残响）：弹体飞行中按节拍喷一撮同色三角碎片，
 * 碎片自弹体位置小散布飞出、沿飞行方向慢飞，渲染后端为统一粒子池（TriShardComponent 共享池）。
 *
 * 与一次性爆发（锥面冲击/爆炸）不同，本层是**持续发射器**：节拍累积器在弹体 Active 期间逐帧推进，
 * 弹体淡出/移除即自然停喷（无需移交）；已喷出的碎片由池包络自然寿终。
 *
 * 碎片外观字段镜像渲染实现侧 TriShardSpec 的旋钮子集（api 层不依赖 astd-render 内部类，
 * 组装时逐字段映射）；发射行为字段（节拍/散布/速度域/张角）为本层独有。
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
