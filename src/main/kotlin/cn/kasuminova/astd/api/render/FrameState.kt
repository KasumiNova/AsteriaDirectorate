package cn.kasuminova.astd.api.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 一帧的宿主中立数据源（设计 D2/D6）。
 *
 * 动机：整棵树需要一份稳定、唯一的取数入口——弹体已存在时间、当前坐标、当前生命周期状态只有一个真相源。
 * 由驱动每帧计算一次并塞入 [RenderContext.frame]；节点一律从这里读取，**不得**各自去调
 * `projectile.getElapsed()` / `getLocation()` 或自行重算。字段做宿主中立，弹体与光束共用同一份。
 */
interface FrameState {

    // ---- 时间 ----

    /** 宿主已存在的秒数。 */
    val elapsed: Float

    /** 逻辑时钟已存在秒数（暂停时不推进），用于与暂停无关/相关的两类动画区分。 */
    val logicElapsed: Float

    /** 本帧步长（秒）。 */
    val amountThisFrame: Float

    // ---- 空间（宿主中立）----

    /** 起点：弹体位置 / 光束炮口。 */
    val origin: Vector2f

    /** 朝向角（度）。 */
    val facing: Float

    /** 长度：弹体可见拖尾长 / 光束到命中点长。 */
    val length: Float

    /** 终点：光束命中端；弹体无固定终点时为 null。 */
    val endpoint: Vector2f?

    /**
     * 本帧命中的目标实体；未命中或弹体宿主为 null。与 [endpoint] 同属"每帧接触数据"，供 impact 类节点选点
     * （如 StellarJet 命中端粒子/弧线以其为锚）。放此而非宿主接口：命中随光束扫描逐帧变化，非宿主恒定量。
     */
    val hitTarget: CombatEntityAPI?

    /** 本帧是否护盾命中；影响命中特效表现（护盾/装甲配色）。未命中为 false。 */
    val isShieldHit: Boolean

    /** 世界单位/像素，供节点做与缩放无关的采样。 */
    val worldUnitsPerPixel: Float

    /**
     * 采样历史节点（世界坐标 + 朝向 + 时间），供网格类节点（body/glow/mist）构建中线曲线追踪真实飞行路径。
     * 由驱动每帧从飞行历史取一份；无历史的宿主（如光束或首帧）为空列表。
     */
    val historyNodes: List<ASTDProjectileHistoryNode>

    // ---- 连续信号（覆盖光束停火淡出 / 复火拉回）----

    /** 宿主处于活动态：弹体飞行中 / 光束 firing。 */
    val active: Boolean

    /** 连续强度 0..1：弹体 alpha / 光束 strength（ramp/level）。节点据此做连续淡入淡出。 */
    val intensity: Float

    /**
     * 淡出包络 0..1：宿主停火后视觉整体收束的连续系数（与 [intensity] 正交）。
     * 弹体不用（恒 1）；光束停火淡出时由宿主逐帧传入——束体据此在保持 strength 观感的同时整体变细/变淡到消失，
     * 沿束粒子据此按密度收敛。GravityCollapse 的 0.65s 平滑淡出即靠此实现（[intensity]=level 控宽/密度，本值控淡出）。
     */
    val fadeMul: Float

    // ---- 终止（一次性）----

    /** 生命周期阶段，仅用于一次性终止判断。 */
    val phase: RenderPhase

    /** 飞行进度 0..1（弹体）。 */
    val flightProgress: Float

    /** 消散进度 0..1。 */
    val dissolve: Float

    /** 已进入淡出时的原因；未淡出为 null。 */
    val fadeReason: FadeReason?
}

/** 宿主生命周期阶段（设计 D2）。 */
enum class RenderPhase {
    /** 活动中。 */
    Active,

    /** 淡出中。 */
    FadingOut,

    /** 已移除（等待 onDetach 释放）。 */
    Removed,
}
