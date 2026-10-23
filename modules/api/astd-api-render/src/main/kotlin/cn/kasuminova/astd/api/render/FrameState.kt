package cn.kasuminova.astd.api.render

import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 弹体或光束在一帧内的状态。驱动每帧构造一次并放入 [RenderContext.frame]。
 *
 * 节点必须读取本接口中的值，不得重新计算宿主时间、位置或生命周期。
 */
interface FrameState {

    /** 宿主已存在的秒数。 */
    val elapsed: Float

    /** 逻辑时钟已推进的秒数，暂停时保持不变。 */
    val logicElapsed: Float

    /** 本帧步长，单位为秒。 */
    val amountThisFrame: Float

    /** 弹体位置或光束起点。 */
    val origin: Vector2f

    /** 朝向角，单位为度。 */
    val facing: Float

    /** 弹体可见拖尾长度或光束长度。 */
    val length: Float

    /** 光束命中端；没有固定终点时为 null。 */
    val endpoint: Vector2f?

    /** 本帧命中的目标，未命中或宿主为弹体时为 null。 */
    val hitTarget: CombatEntityAPI?

    /** 本帧是否命中护盾。 */
    val isShieldHit: Boolean

    /** 每个像素对应的世界单位数。 */
    val worldUnitsPerPixel: Float

    /** 弹体是否仍在飞行，或光束是否仍在发射。 */
    val active: Boolean

    /** 弹体透明度或光束强度，范围为 0 至 1。 */
    val intensity: Float

    /** 淡出系数，范围为 0 至 1；弹体恒为 1，光束停火后由宿主逐帧更新。 */
    val fadeMul: Float

    /** 当前生命周期阶段。 */
    val phase: RenderPhase

    /** 弹体飞行进度，范围为 0 至 1。 */
    val flightProgress: Float

    /** 消散进度，范围为 0 至 1。 */
    val dissolve: Float

    /** 淡出原因，未淡出时为 null。 */
    val fadeReason: FadeReason?
}

/** 宿主生命周期阶段。 */
enum class RenderPhase {
    /** 活动中。 */
    Active,

    /** 淡出中。 */
    FadingOut,

    /** 已移除（等待 onDetach 释放）。 */
    Removed,
}
